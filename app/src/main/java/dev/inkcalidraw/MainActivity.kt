package dev.inkcalidraw

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.input.motionprediction.MotionEventPredictor
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.math.hypot
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), InProgressStrokesFinishedListener {

    private lateinit var sceneView: SceneView
    private lateinit var inkView: InProgressStrokesView
    private lateinit var predictor: MotionEventPredictor
    private lateinit var title: TextView

    private var scene = ExcalidrawScene.empty()
    private var currentUri: Uri? = null
    private var dirty = false

    // Undo/redo: snapshots of the element list (elements are immutable; edits replace them).
    private val undo = ArrayDeque<List<Element>>()
    private val redo = ArrayDeque<List<Element>>()

    // Tool state
    private var color = "#1e1e1e"
    private var strokeWidth = 2 // Excalidraw: 1 thin, 2 bold, 4 extra bold
    private var eraserMode = false

    // Stylus state
    private var stylusPointer = -1
    private var strokeId: InProgressStrokeId? = null
    private var erasing = false
    private var eraseSnapshotTaken = false

    // Finger pan/zoom state
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var lastSpan = 0f
    private var lastFingerCount = 0

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { open(it, persist = true) } }
    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { currentUri = it; persist(it); save() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sceneView = SceneView(this)
        inkView = InProgressStrokesView(this)
        inkView.addFinishedStrokesListener(this)
        predictor = MotionEventPredictor.newInstance(inkView)
        inkView.setOnTouchListener { v, ev -> onTouch(v, ev); true }

        val canvasLayer = FrameLayout(this).apply {
            addView(sceneView, FrameLayout.LayoutParams(-1, -1))
            addView(inkView, FrameLayout.LayoutParams(-1, -1))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#f4f4f6"))
            addView(buildToolbar(), LinearLayout.LayoutParams(-1, dp(56)))
            addView(canvasLayer, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        setContentView(root)

        val handled = intent?.data?.let { open(it, persist = false); true } ?: false
        if (!handled) {
            autosaveFile().takeIf { it.exists() }?.let { f ->
                runCatching { loadScene(ExcalidrawScene.parse(f.readText())) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { open(it, persist = false) }
    }

    override fun onPause() {
        super.onPause()
        val json = scene.toJson()
        val uri = currentUri
        val wasDirty = dirty
        Thread {
            runCatching { autosaveFile().writeText(json) }
            if (uri != null && wasDirty) runCatching { writeTo(uri, json) }
        }.start()
        if (uri != null) dirty = false
    }

    // ---------------------------------------------------------------- input

    private fun onTouch(v: View, ev: MotionEvent) {
        val action = ev.actionMasked
        val idx = ev.actionIndex
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val tool = ev.getToolType(idx)
                if (isPen(tool) && stylusPointer == -1) {
                    stylusPointer = ev.getPointerId(idx)
                    val wantsErase = eraserMode || tool == MotionEvent.TOOL_TYPE_ERASER ||
                        (ev.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                    if (wantsErase) {
                        erasing = true; eraseSnapshotTaken = false
                        eraseAt(ev.getX(idx), ev.getY(idx))
                    } else {
                        v.requestUnbufferedDispatch(ev)
                        predictor.record(ev)
                        strokeId = inkView.startStroke(ev, stylusPointer, currentBrush(), worldFromScreen(), Matrix())
                    }
                    return
                }
                if (stylusPointer == -1) resetFingerGesture(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (stylusPointer != -1) {
                    val pIdx = ev.findPointerIndex(stylusPointer)
                    if (pIdx < 0) return
                    if (erasing) {
                        for (h in 0 until ev.historySize) eraseAt(ev.getHistoricalX(pIdx, h), ev.getHistoricalY(pIdx, h))
                        eraseAt(ev.getX(pIdx), ev.getY(pIdx))
                    } else strokeId?.let { id ->
                        predictor.record(ev)
                        val predicted = predictor.predict()
                        try { inkView.addToStroke(ev, stylusPointer, id, predicted) } finally { predicted?.recycle() }
                    }
                    return
                }
                fingerMove(ev)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (ev.getPointerId(idx) == stylusPointer) {
                    if (!erasing) strokeId?.let { id ->
                        if ((ev.flags and MotionEvent.FLAG_CANCELED) != 0) inkView.cancelStroke(id, ev)
                        else inkView.finishStroke(ev, stylusPointer, id)
                    }
                    stylusPointer = -1; strokeId = null; erasing = false
                    return
                }
                if (stylusPointer == -1) resetFingerGesture(ev, excludeIndex = idx)
            }
            MotionEvent.ACTION_CANCEL -> {
                strokeId?.let { inkView.cancelStroke(it, ev) }
                stylusPointer = -1; strokeId = null; erasing = false
            }
        }
    }

    private fun isPen(tool: Int) = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER

    private fun fingerPointers(ev: MotionEvent, excludeIndex: Int = -1) =
        (0 until ev.pointerCount).filter { it != excludeIndex && ev.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER }

    private fun resetFingerGesture(ev: MotionEvent, excludeIndex: Int = -1) {
        val f = fingerPointers(ev, excludeIndex)
        lastFingerCount = f.size
        if (f.isEmpty()) return
        lastFocusX = f.map { ev.getX(it) }.average().toFloat()
        lastFocusY = f.map { ev.getY(it) }.average().toFloat()
        lastSpan = span(ev, f, lastFocusX, lastFocusY)
    }

    private fun span(ev: MotionEvent, f: List<Int>, cx: Float, cy: Float) =
        if (f.size < 2) 0f else f.map { hypot(ev.getX(it) - cx, ev.getY(it) - cy) }.average().toFloat()

    private fun fingerMove(ev: MotionEvent) {
        val f = fingerPointers(ev)
        if (f.isEmpty()) return
        if (f.size != lastFingerCount) { resetFingerGesture(ev); return }
        val fx = f.map { ev.getX(it) }.average().toFloat()
        val fy = f.map { ev.getY(it) }.average().toFloat()
        val s = span(ev, f, fx, fy)
        sceneView.viewMatrix.postTranslate(fx - lastFocusX, fy - lastFocusY)
        if (f.size >= 2 && lastSpan > 0f && s > 0f) {
            val factor = (s / lastSpan)
            val cur = currentScale()
            val clamped = (cur * factor).coerceIn(0.1f, 30f) / cur
            sceneView.viewMatrix.postScale(clamped, clamped, fx, fy)
        }
        lastFocusX = fx; lastFocusY = fy; lastSpan = s
        sceneView.invalidate()
    }

    private fun currentScale(): Float {
        val v = FloatArray(9); sceneView.viewMatrix.getValues(v)
        return sqrt(v[Matrix.MSCALE_X] * v[Matrix.MSCALE_X] + v[Matrix.MSKEW_Y] * v[Matrix.MSKEW_Y])
    }

    private fun worldFromScreen() = Matrix().also { sceneView.viewMatrix.invert(it) }

    // ---------------------------------------------------------------- strokes

    /**
     * Wet-ink brush tuned to approximate Excalidraw's perfect-freehand width curve:
     * width(p) = 2 * size * sin((0.5 - 0.6 * (0.5 - p)) * PI/2), size = strokeWidth * 4.25.
     * At p = 0.5 that is 1.414 * size; at p = 0 it's 0.437x of that, at p = 1 it's 1.345x.
     */
    @OptIn(androidx.ink.brush.ExperimentalInkCustomBrushApi::class)
    private fun currentBrush(): Brush {
        val family = BrushFamily(
            tip = BrushTip(
                behaviors = listOf(
                    BrushBehavior(
                        BrushBehavior.Source.NORMALIZED_PRESSURE, BrushBehavior.Target.WIDTH_MULTIPLIER,
                        0f, 1f, 0.437f, 1.345f,
                    )
                )
            ),
            paint = BrushPaint(),
        )
        val size = (strokeWidth * 4.25f) * 1.414f
        return Brush.createWithColorIntArgb(family, SceneView.parseColor(color, Color.BLACK), size, 0.1f)
    }

    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
        pushUndo()
        var idx = scene.lastIndex()
        for ((_, stroke) in strokes) {
            val inputs = stroke.inputs
            if (inputs.size == 0) continue
            val tmp = StrokeInput()
            val samples = (0 until inputs.size).map { i ->
                inputs.populate(i, tmp)
                val p = if (tmp.hasPressure) tmp.pressure else 0.5f
                floatArrayOf(tmp.x, tmp.y, p)
            }
            idx = FractionalIndex.after(idx)
            scene.elements.add(ExcalidrawScene.newFreedraw(samples, color, strokeWidth, idx))
        }
        dirty = true
        sceneView.elementsChanged()
        inkView.removeFinishedStrokes(strokes.keys)
    }

    private fun eraseAt(sx: Float, sy: Float) {
        val pt = floatArrayOf(sx, sy)
        worldFromScreen().mapPoints(pt)
        val r = 12.0 / currentScale()
        var changed = false
        for (i in scene.elements.indices) {
            val el = scene.elements[i]
            if (el.isDeleted || el.json["locked"]?.let { (it as? JsonPrimitive)?.content == "true" } == true) continue
            if (hits(el, pt[0].toDouble(), pt[1].toDouble(), r)) {
                if (!eraseSnapshotTaken) { pushUndo(); eraseSnapshotTaken = true }
                scene.elements[i] = el.withChanges("isDeleted" to JsonPrimitive(true))
                changed = true
            }
        }
        if (changed) { dirty = true; sceneView.elementsChanged() }
    }

    private fun hits(el: Element, wx: Double, wy: Double, r: Double): Boolean {
        val lx = wx - el.x; val ly = wy - el.y
        if (el.type == "freedraw" || el.type == "line" || el.type == "arrow") {
            val pts = el.points
            val rr = r + el.strokeWidth * 2
            if (pts.size == 1) return hypot(pts[0][0] - lx, pts[0][1] - ly) <= rr
            for (k in 0 until pts.size - 1) if (segDist(lx, ly, pts[k], pts[k + 1]) <= rr) return true
            return false
        }
        return RectF(0f, 0f, el.width.toFloat(), el.height.toFloat()).apply { inset(-r.toFloat(), -r.toFloat()) }.contains(lx.toFloat(), ly.toFloat())
    }

    private fun segDist(px: Double, py: Double, a: DoubleArray, b: DoubleArray): Double {
        val dx = b[0] - a[0]; val dy = b[1] - a[1]
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else (((px - a[0]) * dx + (py - a[1]) * dy) / l2).coerceIn(0.0, 1.0)
        return hypot(px - (a[0] + t * dx), py - (a[1] + t * dy))
    }

    private fun pushUndo() {
        undo.addLast(scene.elements.toList()); if (undo.size > 200) undo.removeFirst()
        redo.clear()
    }

    private fun doUndo() {
        val prev = undo.removeLastOrNull() ?: return
        redo.addLast(scene.elements.toList())
        scene.elements.clear(); scene.elements.addAll(prev)
        dirty = true; sceneView.elementsChanged()
    }

    private fun doRedo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(scene.elements.toList())
        scene.elements.clear(); scene.elements.addAll(next)
        dirty = true; sceneView.elementsChanged()
    }

    // ---------------------------------------------------------------- files

    private fun autosaveFile() = File(filesDir, "autosave.excalidraw")

    private fun persist(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    private fun open(uri: Uri, persist: Boolean) {
        try {
            val text = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            loadScene(ExcalidrawScene.parse(text))
            if (persist) persist(uri)
            currentUri = uri
            dirty = false
            title.text = uri.lastPathSegment?.substringAfterLast('/') ?: "drawing"
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadScene(s: ExcalidrawScene) {
        scene = s
        sceneView.scene = s
        undo.clear(); redo.clear()
        sceneView.post { fitToContent() }
    }

    private fun fitToContent() {
        val live = scene.elements.filter { !it.isDeleted }
        sceneView.viewMatrix.reset()
        if (live.isEmpty() || sceneView.width == 0) { sceneView.invalidate(); return }
        val b = RectF(
            live.minOf { it.x }.toFloat(), live.minOf { it.y }.toFloat(),
            live.maxOf { it.x + it.width }.toFloat(), live.maxOf { it.y + it.height }.toFloat(),
        )
        b.inset(-40f, -40f)
        val s = minOf(sceneView.width / b.width(), sceneView.height / b.height(), 1f)
        sceneView.viewMatrix.postTranslate(-b.left, -b.top)
        sceneView.viewMatrix.postScale(s, s)
        sceneView.invalidate()
    }

    private fun save() {
        val uri = currentUri ?: run { createDoc.launch("drawing.excalidraw"); return }
        try {
            writeTo(uri, scene.toJson()); dirty = false
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun writeTo(uri: Uri, json: String) {
        contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(json) }
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildToolbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        fun button(label: String, onClick: (TextView) -> Unit) = TextView(this).apply {
            text = label; textSize = 15f; setTextColor(Color.parseColor("#1e1e1e"))
            setPadding(dp(12), dp(8), dp(12), dp(8)); setOnClickListener { onClick(this) }
            bar.addView(this)
        }
        title = TextView(this).apply { text = "Inkcalidraw"; textSize = 16f; setPadding(dp(8), 0, dp(16), 0); setTextColor(Color.BLACK) }
        bar.addView(title)
        button("New") { currentUri = null; loadScene(ExcalidrawScene.empty()); title.text = "Untitled" }
        button("Open") { openDoc.launch(arrayOf("*/*")) }
        button("Save") { save() }
        button("Save as") { createDoc.launch("drawing.excalidraw") }
        button("Undo") { doUndo() }
        button("Redo") { doRedo() }
        button("Fit") { fitToContent() }

        // Excalidraw's default stroke palette
        val swatches = mutableListOf<View>()
        for (c in listOf("#1e1e1e", "#e03131", "#2f9e44", "#1971c2", "#f08c00")) {
            val sw = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(c)) }
                setOnClickListener {
                    color = c; eraserMode = false
                    swatches.forEach { it.scaleX = 0.7f; it.scaleY = 0.7f }; scaleX = 1f; scaleY = 1f
                }
                scaleX = if (c == color) 1f else 0.7f; scaleY = scaleX
            }
            swatches += sw
            bar.addView(sw, LinearLayout.LayoutParams(dp(30), dp(30)).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        val widths = mutableListOf<TextView>()
        for ((label, w) in listOf("S" to 1, "M" to 2, "L" to 4)) {
            widths += button(label) { tv ->
                strokeWidth = w; widths.forEach { it.alpha = 0.4f }; tv.alpha = 1f
            }.apply { alpha = if (w == strokeWidth) 1f else 0.4f }
        }
        button("Eraser") { tv -> eraserMode = !eraserMode; tv.alpha = if (eraserMode) 1f else 0.4f }.alpha = 0.4f

        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar, ViewGroup.LayoutParams(-2, -1))
        }
    }
}
