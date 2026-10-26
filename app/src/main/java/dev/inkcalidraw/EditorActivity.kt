package dev.inkcalidraw

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.input.motionprediction.MotionEventPredictor
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.Executors
import kotlin.math.hypot

class EditorActivity : ComponentActivity(), InProgressStrokesFinishedListener {

    enum class Tool { PEN, HIGHLIGHTER, SELECT, ERASER }

    private lateinit var store: NoteStore
    private lateinit var sceneView: SceneView
    private lateinit var inkView: InProgressStrokesView
    private lateinit var predictor: MotionEventPredictor
    private lateinit var titleView: TextView
    private lateinit var selectionBar: View
    private val toolButtons = mutableMapOf<Tool, TextView>()

    private var scene = ExcalidrawScene.empty()
    private var noteUri: Uri? = null
    private var dirty = false
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val autosave = Runnable { saveNow() }

    private val undo = ArrayDeque<List<Element>>()
    private val redo = ArrayDeque<List<Element>>()

    // Tool state
    private var tool = Tool.PEN
    private var color = "#1e1e1e"
    private var strokeWidth = 2 // Excalidraw: 1 thin, 2 bold, 4 extra bold

    // Stylus gesture state
    private enum class Gesture { NONE, INK, ERASE, LASSO, DRAG, RESIZE }
    private var gesture = Gesture.NONE
    private var stylusPointer = -1
    private var strokeId: InProgressStrokeId? = null
    private var eraseSnapshotTaken = false
    private val lasso = ArrayList<FloatArray>()
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var resizeIndex = -1

    // Finger pan/zoom state
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var lastSpan = 0f
    private var lastFingerCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NoteStore(this)
        sceneView = SceneView(this)
        inkView = InProgressStrokesView(this)
        inkView.addFinishedStrokesListener(this)
        predictor = MotionEventPredictor.newInstance(inkView)
        inkView.setOnTouchListener { v, ev -> onTouch(v, ev); true }
        selectionBar = buildSelectionBar()

        val canvasLayer = FrameLayout(this).apply {
            addView(sceneView, FrameLayout.LayoutParams(-1, -1))
            addView(inkView, FrameLayout.LayoutParams(-1, -1))
            addView(selectionBar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(12) })
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
        setupDragAndDrop(canvasLayer)

        val uri = intent?.data
        if (uri == null) {
            open(store.create().uri, null)
        } else {
            open(uri, intent.getStringExtra(EXTRA_NAME))
        }
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(autosave)
        saveNow()
    }

    // ---------------------------------------------------------------- input

    private fun onTouch(v: View, ev: MotionEvent) {
        val idx = ev.actionIndex
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val type = ev.getToolType(idx)
                if (isPen(type) && stylusPointer == -1) {
                    stylusPointer = ev.getPointerId(idx)
                    penDown(v, ev, idx, type)
                    return
                }
                if (stylusPointer == -1) resetFingerGesture(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (stylusPointer != -1) {
                    val p = ev.findPointerIndex(stylusPointer)
                    if (p >= 0) penMove(ev, p)
                    return
                }
                fingerMove(ev)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (ev.getPointerId(idx) == stylusPointer) {
                    penUp(ev, idx, canceled = (ev.flags and MotionEvent.FLAG_CANCELED) != 0)
                    return
                }
                if (stylusPointer == -1) resetFingerGesture(ev, excludeIndex = idx)
            }
            MotionEvent.ACTION_CANCEL -> if (stylusPointer != -1) penUp(ev, 0, canceled = true)
        }
    }

    private fun penDown(v: View, ev: MotionEvent, idx: Int, toolType: Int) {
        val sideButton = (ev.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        val w = toWorld(ev.getX(idx), ev.getY(idx))
        val sel = sceneView.selectionBounds()
        gesture = when {
            toolType == MotionEvent.TOOL_TYPE_ERASER || tool == Tool.ERASER -> Gesture.ERASE
            resizeHandleHit(ev.getX(idx), ev.getY(idx)) -> Gesture.RESIZE
            sel?.contains(w[0], w[1]) == true -> Gesture.DRAG // grab the selection with any tool
            sideButton || tool == Tool.SELECT -> Gesture.LASSO
            else -> Gesture.INK
        }
        when (gesture) {
            Gesture.ERASE -> { eraseSnapshotTaken = false; eraseAt(w) }
            Gesture.DRAG -> { dragStartX = w[0]; dragStartY = w[1] }
            Gesture.RESIZE -> {
                pushUndo()
                resizeIndex = scene.elements.indexOfFirst { it.id == sceneView.selectedIds.single() }
            }
            Gesture.LASSO -> {
                setSelection(emptySet())
                lasso.clear(); lasso.add(w)
                sceneView.lassoPath.reset(); sceneView.lassoPath.moveTo(w[0], w[1])
                sceneView.showLasso = true; sceneView.invalidate()
            }
            Gesture.INK -> {
                if (sceneView.selectedIds.isNotEmpty()) setSelection(emptySet())
                v.requestUnbufferedDispatch(ev)
                predictor.record(ev)
                strokeId = inkView.startStroke(ev, stylusPointer, currentBrush(), worldFromScreen(), Matrix())
            }
            Gesture.NONE -> {}
        }
    }

    private fun penMove(ev: MotionEvent, p: Int) {
        when (gesture) {
            Gesture.INK -> strokeId?.let { id ->
                predictor.record(ev)
                val predicted = predictor.predict()
                try { inkView.addToStroke(ev, stylusPointer, id, predicted) } finally { predicted?.recycle() }
            }
            Gesture.ERASE -> {
                for (h in 0 until ev.historySize) eraseAt(toWorld(ev.getHistoricalX(p, h), ev.getHistoricalY(p, h)))
                eraseAt(toWorld(ev.getX(p), ev.getY(p)))
            }
            Gesture.LASSO -> {
                for (h in 0 until ev.historySize) lassoTo(toWorld(ev.getHistoricalX(p, h), ev.getHistoricalY(p, h)))
                lassoTo(toWorld(ev.getX(p), ev.getY(p)))
                sceneView.invalidate()
            }
            Gesture.DRAG -> {
                val w = toWorld(ev.getX(p), ev.getY(p))
                sceneView.dragDx = w[0] - dragStartX; sceneView.dragDy = w[1] - dragStartY
                sceneView.invalidate()
            }
            Gesture.RESIZE -> {
                val i = resizeIndex
                if (i >= 0) {
                    val el = scene.elements[i]
                    val w = toWorld(ev.getX(p), ev.getY(p))
                    val aspect = if (el.width > 0) el.height / el.width else 1.0
                    val nw = maxOf(16.0 / sceneView.scale(), maxOf(w[0] - el.x, (w[1] - el.y) / aspect))
                    scene.elements[i] = el.withChanges("width" to JsonPrimitive(nw), "height" to JsonPrimitive(nw * aspect))
                    sceneView.invalidate()
                }
            }
            Gesture.NONE -> {}
        }
    }

    /** Only a single selected image gets a resize handle (bottom-right corner). */
    private fun singleSelectedImage(): Element? =
        sceneView.selectedIds.singleOrNull()?.let { id -> scene.elements.firstOrNull { it.id == id && it.type == "image" } }

    private fun resizeHandleHit(sx: Float, sy: Float): Boolean {
        val r = sceneView.handleRectWorld() ?: return false
        val pt = floatArrayOf(r.centerX(), r.centerY()); sceneView.viewMatrix.mapPoints(pt)
        return hypot(pt[0] - sx, pt[1] - sy) < dp(28)
    }

    private fun penUp(ev: MotionEvent, idx: Int, canceled: Boolean) {
        when (gesture) {
            Gesture.INK -> strokeId?.let { id ->
                if (canceled) inkView.cancelStroke(id, ev) else inkView.finishStroke(ev, stylusPointer, id)
            }
            Gesture.LASSO -> {
                sceneView.showLasso = false
                if (!canceled) setSelection(elementsInLasso())
                sceneView.invalidate()
            }
            Gesture.DRAG -> commitDrag(canceled)
            Gesture.RESIZE -> { resizeIndex = -1; changed() }
            else -> {}
        }
        gesture = Gesture.NONE; stylusPointer = -1; strokeId = null
    }

    private fun isPen(t: Int) = t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER

    private fun toWorld(sx: Float, sy: Float) = floatArrayOf(sx, sy).also { worldFromScreen().mapPoints(it) }

    private fun worldFromScreen() = Matrix().also { sceneView.viewMatrix.invert(it) }

    // ---------------------------------------------------------------- finger pan / zoom

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
            val cur = sceneView.scale()
            val k = (cur * (s / lastSpan)).coerceIn(0.1f, 30f) / cur
            sceneView.viewMatrix.postScale(k, k, fx, fy)
        }
        lastFocusX = fx; lastFocusY = fy; lastSpan = s
        sceneView.invalidate()
    }

    // ---------------------------------------------------------------- ink

    /**
     * Wet-ink brush tuned to approximate Excalidraw's perfect-freehand width curve:
     * width(p) = 2 * size * sin((0.5 - 0.6 * (0.5 - p)) * PI/2), size = strokeWidth * 4.25.
     */
    @OptIn(ExperimentalInkCustomBrushApi::class)
    private fun currentBrush(): Brush {
        val family = BrushFamily(
            tip = BrushTip(behaviors = listOf(
                BrushBehavior(BrushBehavior.Source.NORMALIZED_PRESSURE, BrushBehavior.Target.WIDTH_MULTIPLIER, 0f, 1f, 0.437f, 1.345f)
            )),
            paint = BrushPaint(),
        )
        val (w, opacity) = penStyle()
        val argb = SceneRenderer.parseColor(color, Color.BLACK)
        val withAlpha = Color.argb(opacity * 255 / 100, Color.red(argb), Color.green(argb), Color.blue(argb))
        return Brush.createWithColorIntArgb(family, withAlpha, w * 4.25f * 1.414f, 0.1f)
    }

    /** (Excalidraw strokeWidth, opacity) for the current tool. */
    private fun penStyle(): Pair<Int, Int> = if (tool == Tool.HIGHLIGHTER) Pair(maxOf(strokeWidth * 3, 6), 40) else Pair(strokeWidth, 100)

    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
        pushUndo()
        var idx = scene.lastIndex()
        val (w, opacity) = penStyle()
        for ((_, stroke) in strokes) {
            val inputs = stroke.inputs
            if (inputs.size == 0) continue
            val tmp = StrokeInput()
            val samples = (0 until inputs.size).map { i ->
                inputs.populate(i, tmp)
                floatArrayOf(tmp.x, tmp.y, if (tmp.hasPressure) tmp.pressure else 0.5f)
            }
            idx = FractionalIndex.after(idx)
            scene.elements.add(ExcalidrawScene.newFreedraw(samples, color, w, idx, opacity))
        }
        changed()
        inkView.removeFinishedStrokes(strokes.keys)
    }

    // ---------------------------------------------------------------- eraser

    private fun eraseAt(w: FloatArray) {
        val r = 12.0 / sceneView.scale()
        var hit = false
        for (i in scene.elements.indices) {
            val el = scene.elements[i]
            if (el.isDeleted || isLocked(el)) continue
            if (hits(el, w[0].toDouble(), w[1].toDouble(), r)) {
                if (!eraseSnapshotTaken) { pushUndo(); eraseSnapshotTaken = true }
                scene.elements[i] = el.withChanges("isDeleted" to JsonPrimitive(true))
                hit = true
            }
        }
        if (hit) changed()
    }

    private fun isLocked(el: Element) = (el.json["locked"] as? JsonPrimitive)?.content == "true"

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

    // ---------------------------------------------------------------- selection

    private fun lassoTo(w: FloatArray) {
        val last = lasso.last()
        if (hypot(w[0] - last[0], w[1] - last[1]) * sceneView.scale() < 2f) return
        lasso.add(w); sceneView.lassoPath.lineTo(w[0], w[1])
    }

    private fun elementsInLasso(): Set<String> {
        if (lasso.size < 3) return emptySet()
        val poly = lasso.toList()
        val ids = mutableSetOf<String>()
        for (el in scene.elements) {
            if (el.isDeleted || isLocked(el)) continue
            val samples: List<FloatArray> = when (el.type) {
                "freedraw", "line", "arrow" -> el.points.map { floatArrayOf((el.x + it[0]).toFloat(), (el.y + it[1]).toFloat()) }
                else -> listOf(floatArrayOf((el.x + el.width / 2).toFloat(), (el.y + el.height / 2).toFloat()))
            }
            if (samples.isEmpty()) continue
            val inside = samples.count { pointInPolygon(it[0], it[1], poly) }
            if (inside >= samples.size * 0.6) ids += el.id
        }
        return ids
    }

    private fun pointInPolygon(x: Float, y: Float, poly: List<FloatArray>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val (xi, yi) = poly[i][0] to poly[i][1]
            val (xj, yj) = poly[j][0] to poly[j][1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    private fun setSelection(ids: Set<String>) {
        sceneView.selectedIds = ids
        sceneView.showHandle = ids.size == 1 && scene.elements.any { it.id in ids && it.type == "image" }
        sceneView.dragDx = 0f; sceneView.dragDy = 0f
        selectionBar.visibility = if (ids.isEmpty()) View.GONE else View.VISIBLE
        (selectionBar.tag as? TextView)?.text = "${ids.size} selected"
        sceneView.invalidate()
    }

    private fun commitDrag(canceled: Boolean) {
        val dx = sceneView.dragDx; val dy = sceneView.dragDy
        sceneView.dragDx = 0f; sceneView.dragDy = 0f
        if (canceled || (dx == 0f && dy == 0f)) { sceneView.invalidate(); return }
        pushUndo()
        val ids = sceneView.selectedIds
        for (i in scene.elements.indices) {
            val el = scene.elements[i]
            if (el.id in ids) scene.elements[i] = el.withChanges("x" to JsonPrimitive(el.x + dx), "y" to JsonPrimitive(el.y + dy))
        }
        changed()
    }

    private fun deleteSelection() {
        val ids = sceneView.selectedIds
        if (ids.isEmpty()) return
        pushUndo()
        for (i in scene.elements.indices) if (scene.elements[i].id in ids) scene.elements[i] = scene.elements[i].withChanges("isDeleted" to JsonPrimitive(true))
        setSelection(emptySet()); changed()
    }

    private fun duplicateSelection() {
        val ids = sceneView.selectedIds
        if (ids.isEmpty()) return
        pushUndo()
        var idx = scene.lastIndex()
        val offset = 24.0 / sceneView.scale()
        val copies = scene.elements.filter { it.id in ids && !it.isDeleted }.map { el ->
            idx = FractionalIndex.after(idx)
            el.copyAsNew(idx).withChanges("x" to JsonPrimitive(el.x + offset), "y" to JsonPrimitive(el.y + offset))
        }
        scene.elements.addAll(copies)
        setSelection(copies.map { it.id }.toSet()); changed()
    }

    private fun recolorSelection() {
        val ids = sceneView.selectedIds
        if (ids.isEmpty()) return
        pushUndo()
        for (i in scene.elements.indices) if (scene.elements[i].id in ids) scene.elements[i] = scene.elements[i].withChanges("strokeColor" to JsonPrimitive(color))
        changed()
    }

    // ---------------------------------------------------------------- images (clipboard, drag & drop)

    private fun pasteFromClipboard() {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) { toast("Clipboard is empty"); return }
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i).uri ?: continue
            val mime = contentResolver.getType(uri) ?: clip.description.let { d -> (0 until d.mimeTypeCount).map { d.getMimeType(it) }.firstOrNull { it.startsWith("image/") } }
            if (mime == null || mime.startsWith("image/")) { insertImage(uri, null); return }
        }
        toast("No image in the clipboard")
    }

    /** Reads, normalises and inserts an image, centred at [at] (world coords) or the middle of the view. */
    private fun insertImage(uri: Uri, at: FloatArray?) {
        val center = at ?: toWorld(sceneView.width / 2f, sceneView.height / 2f)
        val visibleW = sceneView.width / sceneView.scale()
        val visibleH = sceneView.height / sceneView.scale()
        io.execute {
            try {
                val img = ImageImport.load(contentResolver, uri)
                runOnUiThread {
                    pushUndo()
                    scene.addFile(img.fileId, img.mimeType, img.dataUrl)
                    // Fit inside 60% of what's on screen, never upscale.
                    val k = minOf(1.0, visibleW * 0.6 / img.width, visibleH * 0.6 / img.height)
                    val w = img.width * k; val h = img.height * k
                    val el = ExcalidrawScene.newImage(img.fileId, center[0] - w / 2, center[1] - h / 2, w, h, FractionalIndex.after(scene.lastIndex()))
                    scene.elements.add(el)
                    setSelection(setOf(el.id))
                    changed()
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Couldn't paste image: ${e.message}") }
            }
        }
    }

    private fun setupDragAndDrop(target: View) {
        target.setOnDragListener { _, ev ->
            when (ev.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED ->
                    ev.clipDescription?.let { d -> (0 until d.mimeTypeCount).any { d.getMimeType(it).startsWith("image/") } } ?: false
                android.view.DragEvent.ACTION_DROP -> {
                    requestDragAndDropPermissions(ev)
                    val uri = ev.clipData?.let { c -> (0 until c.itemCount).firstNotNullOfOrNull { c.getItemAt(it).uri } }
                    if (uri != null) insertImage(uri, toWorld(ev.x, ev.y))
                    uri != null
                }
                else -> true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (event.isCtrlPressed) when (keyCode) {
            android.view.KeyEvent.KEYCODE_V -> { pasteFromClipboard(); return true }
            android.view.KeyEvent.KEYCODE_Z -> { if (event.isShiftPressed) doRedo() else doUndo(); return true }
            android.view.KeyEvent.KEYCODE_Y -> { doRedo(); return true }
            android.view.KeyEvent.KEYCODE_D -> { duplicateSelection(); return true }
        }
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_PASTE -> { pasteFromClipboard(); return true }
            android.view.KeyEvent.KEYCODE_FORWARD_DEL, android.view.KeyEvent.KEYCODE_DEL ->
                if (sceneView.selectedIds.isNotEmpty()) { deleteSelection(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- undo / persistence

    private fun pushUndo() {
        undo.addLast(scene.elements.toList()); if (undo.size > 200) undo.removeFirst()
        redo.clear()
    }

    private fun doUndo() {
        val prev = undo.removeLastOrNull() ?: return
        redo.addLast(scene.elements.toList())
        scene.elements.clear(); scene.elements.addAll(prev)
        setSelection(emptySet()); changed()
    }

    private fun doRedo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(scene.elements.toList())
        scene.elements.clear(); scene.elements.addAll(next)
        setSelection(emptySet()); changed()
    }

    private fun changed() {
        dirty = true
        sceneView.invalidate()
        main.removeCallbacks(autosave)
        main.postDelayed(autosave, 1500)
    }

    private fun open(uri: Uri, name: String?) {
        try {
            scene = ExcalidrawScene.parse(store.read(uri))
            noteUri = uri
            titleView.text = name ?: NoteStore.displayName(uri.lastPathSegment?.substringAfterLast('/') ?: "Note")
            sceneView.scene = scene
            undo.clear(); redo.clear(); setSelection(emptySet())
            sceneView.post { fitToContent() }
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun saveNow() {
        if (!dirty) return
        val uri = noteUri ?: return
        val json = scene.toJson()
        dirty = false
        io.execute {
            try { store.write(uri, json) } catch (e: Exception) {
                // e.g. a read-only file opened from another app: keep the work as a new note instead.
                runCatching {
                    val copy = store.create(titleView.text.toString(), json)
                    runOnUiThread { noteUri = copy.uri; Toast.makeText(this, "Saved a copy to your notes", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    private fun fitToContent() {
        sceneView.viewMatrix.reset()
        val b = SceneRenderer.contentBounds(scene.elements)
        if (b == null || sceneView.width == 0) { sceneView.invalidate(); return }
        b.inset(-40f, -40f)
        val s = minOf(sceneView.width / b.width(), sceneView.height / b.height(), 1f)
        sceneView.viewMatrix.postTranslate(-b.left, -b.top)
        sceneView.viewMatrix.postScale(s, s)
        sceneView.invalidate()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun selectTool(t: Tool) {
        tool = t
        toolButtons.forEach { (k, b) -> b.background = if (k == t) chip("#e0dfff") else null }
        if (t != Tool.SELECT && sceneView.selectedIds.isNotEmpty()) setSelection(emptySet())
    }

    private fun chip(c: String) = GradientDrawable().apply { setColor(Color.parseColor(c)); cornerRadius = dp(8).toFloat() }

    private fun buildToolbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(8), 0)
        }
        fun button(label: String, onClick: (TextView) -> Unit) = TextView(this).apply {
            text = label; textSize = 15f; setTextColor(Color.parseColor("#1e1e1e"))
            setPadding(dp(12), dp(8), dp(12), dp(8)); setOnClickListener { onClick(this) }
            bar.addView(this, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(2) })
        }
        button("‹ Notes") { finish() }
        titleView = TextView(this).apply { textSize = 16f; setTextColor(Color.BLACK); maxLines = 1; setPadding(dp(4), 0, dp(16), 0) }
        bar.addView(titleView)
        button("Undo") { doUndo() }
        button("Redo") { doRedo() }
        button("Fit") { fitToContent() }
        button("Paste") { pasteFromClipboard() }
        bar.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))

        for ((t, label) in listOf(Tool.PEN to "Pen", Tool.HIGHLIGHTER to "Highlighter", Tool.SELECT to "Select", Tool.ERASER to "Eraser")) {
            toolButtons[t] = button(label) { selectTool(t) }
        }
        bar.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))

        // Excalidraw's default stroke palette
        val swatches = mutableListOf<View>()
        for (c in listOf("#1e1e1e", "#e03131", "#2f9e44", "#1971c2", "#f08c00", "#ffd43b")) {
            val sw = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(c)); setStroke(dp(1), Color.LTGRAY) }
                setOnClickListener {
                    color = c
                    if (tool == Tool.ERASER || tool == Tool.SELECT && sceneView.selectedIds.isEmpty()) selectTool(Tool.PEN)
                    swatches.forEach { s -> s.scaleX = 0.7f; s.scaleY = 0.7f }; scaleX = 1f; scaleY = 1f
                }
                scaleX = if (c == color) 1f else 0.7f; scaleY = scaleX
            }
            swatches += sw
            bar.addView(sw, LinearLayout.LayoutParams(dp(30), dp(30)).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        val widths = mutableListOf<TextView>()
        for ((label, w) in listOf("S" to 1, "M" to 2, "L" to 4)) {
            widths += button(label) { tv -> strokeWidth = w; widths.forEach { it.alpha = 0.4f }; tv.alpha = 1f }
                .apply { alpha = if (w == strokeWidth) 1f else 0.4f }
        }
        selectTool(Tool.PEN)
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar, ViewGroup.LayoutParams(-2, -1))
        }
    }

    private fun buildSelectionBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(22).toFloat(); setStroke(dp(1), Color.parseColor("#d0d0e0")) }
            elevation = dp(4).toFloat()
            setPadding(dp(14), dp(4), dp(6), dp(4))
            visibility = View.GONE
        }
        val count = TextView(this).apply { textSize = 14f; setTextColor(Color.GRAY); setPadding(0, 0, dp(8), 0) }
        bar.addView(count); bar.tag = count
        fun action(label: String, onClick: () -> Unit) = bar.addView(TextView(this).apply {
            text = label; textSize = 15f; setTextColor(Color.parseColor("#6965db"))
            setPadding(dp(12), dp(8), dp(12), dp(8)); setOnClickListener { onClick() }
        })
        action("Recolor") { recolorSelection() }
        action("Duplicate") { duplicateSelection() }
        action("Delete") { deleteSelection() }
        action("Done") { setSelection(emptySet()) }
        return bar
    }

    companion object { const val EXTRA_NAME = "name" }
}
