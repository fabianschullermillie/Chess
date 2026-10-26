package dev.inkcalidraw

import android.app.UiAutomation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Drives the editor on an emulator with synthetic S Pen input (tool type, pressure, side button) and
 * finger input, checks the resulting .excalidraw data, and saves screenshots for review.
 */
@RunWith(AndroidJUnit4::class)
class EditorDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val auto: UiAutomation get() = inst.uiAutomation
    private val device = UiDevice.getInstance(inst)
    private val shots = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
    private val report = StringBuilder()

    @Before fun setup() {
        Ui.setThemeSetting(ctx, "light")
        ctx.getSharedPreferences("ui", 0).edit().putBoolean("penOnly", true).commit()
    }

    // ------------------------------------------------------------------ helpers

    private val store get() = NoteStore(ctx)

    private fun note(json: String, name: String): Uri = store.create(store.root(), name, json).uri

    private fun launch(uri: Uri): ActivityScenario<EditorActivity> {
        val s = ActivityScenario.launch<EditorActivity>(Intent(ctx, EditorActivity::class.java).setData(uri).putExtra(EditorActivity.EXTRA_FOLDER, store.root().uri.toString()))
        SystemClock.sleep(1500)
        return s
    }

    private fun <T> field(a: EditorActivity, name: String): T {
        val f = EditorActivity::class.java.getDeclaredField(name); f.isAccessible = true
        @Suppress("UNCHECKED_CAST") return f.get(a) as T
    }

    private fun scene(s: ActivityScenario<EditorActivity>): ExcalidrawScene {
        var out: ExcalidrawScene? = null
        s.onActivity { out = field(it, "scene") }
        return out!!
    }

    private fun live(s: ActivityScenario<EditorActivity>, type: String) = scene(s).elements.count { it.type == type && !it.isDeleted }

    /** Screen rect of the drawing surface. */
    private fun surfaceRect(s: ActivityScenario<EditorActivity>): Rect {
        var r: Rect? = null
        s.onActivity { a ->
            val v: CanvasSurface = field(a, "surface")
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            r = Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        }
        return r!!
    }

    private fun event(action: Int, down: Long, x: Float, y: Float, pressure: Float, tool: Int, buttons: Int = 0): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; this.pressure = pressure; size = 0.01f })
        val source = if (tool == MotionEvent.TOOL_TYPE_FINGER) InputDevice.SOURCE_TOUCHSCREEN else InputDevice.SOURCE_STYLUS
        return MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, props, coords, 0, buttons, 1f, 1f, 0, 0, source, 0)
    }

    private fun inject(e: MotionEvent) { auto.injectInputEvent(e, true); e.recycle() }

    /** Draws a polyline with the given tool; returns without lifting if [lift] is false. */
    private fun gesture(points: List<FloatArray>, tool: Int = MotionEvent.TOOL_TYPE_STYLUS, buttons: Int = 0, lift: Boolean = true, stepMs: Long = 4): Long {
        val down = SystemClock.uptimeMillis()
        inject(event(MotionEvent.ACTION_DOWN, down, points[0][0], points[0][1], points[0][2], tool, buttons))
        for (p in points.drop(1)) { SystemClock.sleep(stepMs); inject(event(MotionEvent.ACTION_MOVE, down, p[0], p[1], p[2], tool, buttons)) }
        if (lift) inject(event(MotionEvent.ACTION_UP, down, points.last()[0], points.last()[1], points.last()[2], tool, buttons))
        return down
    }

    private fun wave(r: Rect, y: Float, n: Int = 90): List<FloatArray> = (0 until n).map { i ->
        val t = i / (n - 1f)
        floatArrayOf(r.left + r.width() * (0.38f + 0.24f * t), y + 40f * sin(t * 4 * PI).toFloat(), (0.2f + 0.7f * sin(t * PI).toFloat()))
    }

    private fun shot(name: String): Bitmap {
        SystemClock.sleep(400)
        var b: Bitmap? = null
        for (i in 0 until 5) { b = auto.takeScreenshot(); if (b != null) break; SystemClock.sleep(300) }
        b!!
        File(shots, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return b
    }

    private fun log(s: String) { Log.i("InkcalidrawTest", s); report.appendLine(s); File(shots, "report.txt").appendText(s + "\n") }

    // ------------------------------------------------------------------ tests

    /** The stroke must look the same while drawing (front buffer) and after lifting (committed). */
    @Test fun wetInkMatchesCommittedStroke() {
        val s = launch(note(Pages.newPagedScene(PageOrientation.PORTRAIT, PageDirection.VERTICAL).toJson(), "wet-vs-dry"))
        val r = surfaceRect(s)
        val pts = wave(r, r.top + r.height() * 0.45f)
        val down = gesture(pts, lift = false)
        // let the predictor settle on the last point, then capture the wet stroke
        repeat(6) { SystemClock.sleep(8); inject(event(MotionEvent.ACTION_MOVE, down, pts.last()[0], pts.last()[1], pts.last()[2], MotionEvent.TOOL_TYPE_STYLUS)) }
        val wet = shot("01-wet-stroke")
        inject(event(MotionEvent.ACTION_UP, down, pts.last()[0], pts.last()[1], pts.last()[2], MotionEvent.TOOL_TYPE_STYLUS))
        val dry = shot("02-committed-stroke")
        assertEquals(1, live(s, "freedraw"))

        // Compare the stroke area pixel by pixel (ignore the pen tip, where prediction may still differ).
        val box = Rect(pts.minOf { it[0] }.toInt() - 30, pts.minOf { it[1] }.toInt() - 30, pts.maxOf { it[0] }.toInt() + 30, pts.maxOf { it[1] }.toInt() + 30)
        var inked = 0; var differ = 0
        for (y in box.top until box.bottom step 1) for (x in box.left until box.right step 1) {
            // Skip the pen tip: emulator screenshots can lag the front buffer by a frame there.
            if (hypot(x - pts.last()[0], y - pts.last()[1]) < 50 || x > pts.last()[0] - 150) continue
            val a = wet.getPixel(x, y); val b = dry.getPixel(x, y)
            val dark = Color.red(b) < 200 || Color.red(a) < 200
            if (dark) inked++
            val d = maxOf(abs(Color.red(a) - Color.red(b)), abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b)))
            if (d > 48) differ++
        }
        val frac = differ.toDouble() / maxOf(1, inked)
        log("wet-vs-dry: inked=$inked differing=$differ (${"%.2f".format(frac * 100)}%)")
        assertTrue("stroke drawn", inked > 500)
        assertTrue("wet and committed stroke differ in ${frac * 100}% of stroke pixels", frac < 0.03)
        s.close()
    }

    private fun abs(i: Int) = kotlin.math.abs(i)

    /** Side button = eraser, and a fast swipe whose samples skip over the stroke still erases it. */
    @Test fun sideButtonErasesWithFastSwipe() {
        val s = launch(note(ExcalidrawScene.empty().toJson(), "erase"))
        val r = surfaceRect(s)
        val y = r.top + r.height() * 0.5f
        gesture(wave(r, y))
        SystemClock.sleep(300)
        assertEquals(1, live(s, "freedraw"))
        // three samples, 300 px apart, crossing the stroke vertically
        val x = r.left + r.width() * 0.5f
        gesture(listOf(floatArrayOf(x, y - 300, .5f), floatArrayOf(x, y + 10, .5f), floatArrayOf(x, y + 320, .5f)), buttons = MotionEvent.BUTTON_STYLUS_PRIMARY, stepMs = 16)
        SystemClock.sleep(300)
        shot("03-after-button-erase")
        log("button erase: freedraw left = ${live(s, "freedraw")}")
        assertEquals(0, live(s, "freedraw"))
        s.close()
    }

    /** Holding the side button still opens the select/paste menu instead of erasing. */
    @Test fun buttonHoldOpensMenu() {
        val s = launch(note(ExcalidrawScene.empty().toJson(), "menu"))
        val r = surfaceRect(s)
        val x = r.exactCenterX(); val y = r.exactCenterY()
        val down = SystemClock.uptimeMillis()
        inject(event(MotionEvent.ACTION_DOWN, down, x, y, .5f, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_PRIMARY))
        SystemClock.sleep(800)
        val menu = device.wait(Until.findObject(By.text("Lasso select")), 2000)
        shot("04-pen-button-menu")
        inject(event(MotionEvent.ACTION_UP, down, x, y, .5f, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_PRIMARY))
        assertNotNull("menu with 'Lasso select' shown", menu)
        assertNotNull(device.findObject(By.text("Paste here")))
        menu.click()
        SystemClock.sleep(300)
        // the next pen stroke is a lasso: draw one, then a circle around nothing selects nothing but must not ink
        gesture((0..40).map { i -> val a = i / 40.0 * 2 * PI; floatArrayOf((x + 120 * kotlin.math.cos(a)).toFloat(), (y + 120 * sin(a)).toFloat(), .5f) })
        SystemClock.sleep(300)
        assertEquals("lasso stroke must not be inked", 0, live(s, "freedraw"))
        s.close()
    }

    /** Pen-only mode: a finger drag scrolls, it never draws. */
    @Test fun fingerNavigatesInPenOnlyMode() {
        val s = launch(note(ExcalidrawScene.empty().toJson(), "finger"))
        val r = surfaceRect(s)
        var before = FloatArray(9)
        s.onActivity { before = FloatArray(9).also { v -> field<android.graphics.Matrix>(it, "view").getValues(v) } }
        gesture(wave(r, r.exactCenterY(), 30), tool = MotionEvent.TOOL_TYPE_FINGER, stepMs = 10)
        SystemClock.sleep(300)
        var after = FloatArray(9)
        s.onActivity { after = FloatArray(9).also { v -> field<android.graphics.Matrix>(it, "view").getValues(v) } }
        assertEquals(0, live(s, "freedraw"))
        assertTrue("finger moved the canvas", hypot(after[2] - before[2], after[5] - before[5]) > 50)
        s.close()
    }

    /** Paste an image from the clipboard via the toolbar button. */
    @Test fun pasteImageFromClipboard() {
        val png = File(ctx.cacheDir, "clip.png")
        Bitmap.createBitmap(400, 240, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.parseColor("#a5d8ff"))
            android.graphics.Canvas(this).drawText("clipboard image", 40f, 120f, Paint().apply { textSize = 36f })
        }.compress(Bitmap.CompressFormat.PNG, 100, png.outputStream())
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", png)
        val s = launch(note(Pages.newPagedScene(PageOrientation.PORTRAIT, PageDirection.VERTICAL).toJson(), "paste"))
        s.onActivity { it.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newUri(it.contentResolver, "img", uri)) }
        device.findObject(By.desc("Paste image")).click()
        SystemClock.sleep(1500)
        shot("05-pasted-image")
        val sc = scene(s)
        log("paste: images=${live(s, "image")} files=${sc.files.size}")
        assertEquals(1, live(s, "image"))
        assertEquals(1, sc.files.size)
        assertNotNull("pasted onto the page", sc.elements.first { it.type == "image" }.frameId)
        s.close()
    }

    /** PDF import -> annotate -> export round trip, plus dark mode rendering of the same note. */
    @Test fun pdfAnnotateAndExport() {
        val pdf = File(ctx.cacheDir, "lecture.pdf")
        PdfDocument().apply {
            for (i in 1..3) {
                val p = startPage(PdfDocument.PageInfo.Builder(595, 842, i).create())
                p.canvas.drawText("Lecture slide $i", 60f, 100f, Paint().apply { textSize = 32f })
                p.canvas.drawRect(60f, 140f, 535f, 400f, Paint().apply { color = Color.parseColor("#1971c2") })
                finishPage(p)
            }
            writeTo(pdf.outputStream()); close()
        }
        val scene = ExcalidrawScene.empty()
        val n = PdfSupport.import(ctx, store, store.root(), Uri.fromFile(pdf), "lecture.pdf", scene, PageDirection.VERTICAL)
        NoteMeta(paged = true).writeTo(scene)
        assertEquals(3, n)
        val s = launch(note(scene.toJson(), "lecture"))
        val r = surfaceRect(s)
        gesture(wave(r, r.top + r.height() * 0.6f))
        SystemClock.sleep(400)
        shot("06-pdf-annotated")
        val sc = scene(s)
        assertEquals(3, Pages.pages(sc).size)
        assertNotNull("annotation bound to a page", sc.elements.last { it.type == "freedraw" }.frameId)

        val out = File(ctx.cacheDir, "export.pdf")
        val pdfs = PdfPages(ctx) { name -> store.findFile(store.root(), name) }
        out.outputStream().use { PdfSupport.export(sc, pdfs, it) }
        pdfs.close()
        PdfRenderer(ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY)).use { assertEquals(3, it.pageCount) }
        log("pdf: imported=$n exportedBytes=${out.length()}")
        s.close()

        Ui.setThemeSetting(ctx, "dark")
        val d = launch(note(sc.toJson(), "lecture-dark"))
        shot("07-dark-mode")
        d.close()
        Ui.setThemeSetting(ctx, "light")
    }

    /** A 300-page PDF must import and open quickly (pages are rendered on demand, not copied into the note). */
    @Test fun largePdfImportsFast() {
        val pdf = File(ctx.cacheDir, "big.pdf")
        PdfDocument().apply {
            val paint = Paint().apply { textSize = 28f }
            for (i in 1..300) { val p = startPage(PdfDocument.PageInfo.Builder(595, 842, i).create()); p.canvas.drawText("Page $i", 60f, 100f, paint); finishPage(p) }
            writeTo(pdf.outputStream()); close()
        }
        val t0 = SystemClock.uptimeMillis()
        val scene = ExcalidrawScene.empty()
        val n = PdfSupport.import(ctx, store, store.root(), Uri.fromFile(pdf), "big.pdf", scene, PageDirection.VERTICAL)
        NoteMeta(paged = true).writeTo(scene)
        val json = scene.toJson()
        val importMs = SystemClock.uptimeMillis() - t0
        val t1 = SystemClock.uptimeMillis()
        val s = launch(note(json, "big"))
        val openMs = SystemClock.uptimeMillis() - t1 - 1500 // minus launch()'s fixed wait
        shot("12-big-pdf")
        log("big pdf: pages=$n import=${importMs}ms noteSize=${json.length / 1024}KB open≈${openMs}ms")
        assertEquals(300, n)
        assertTrue("note stays small (${json.length} chars)", json.length < 400_000)
        assertTrue("import took $importMs ms", importMs < 15_000)
        s.close()
    }

    /** Eraser on a busy page: time spent handling a fast erase gesture. */
    @Test fun eraserStaysFastOnBusyNotes() {
        val scene = ExcalidrawScene.empty()
        for (i in 0 until 1500) {
            val ox = (i % 30) * 70f; val oy = (i / 30) * 60f
            scene.elements.add(ExcalidrawScene.newFreedraw((0 until 120).map { k -> floatArrayOf(ox + k * 0.5f, oy + 15 * sin(k / 8.0).toFloat(), .5f) }, "#1e1e1e", 1.5, FractionalIndex.after(scene.lastIndex())))
        }
        val s = launch(note(scene.toJson(), "busy"))
        SystemClock.sleep(1500)
        val r = surfaceRect(s)
        val pts = (0 until 120).map { i -> floatArrayOf(r.left + r.width() * (0.1f + 0.8f * i / 119f), r.top + r.height() * (0.3f + 0.4f * sin(i / 10.0).toFloat()), .5f) }
        s.onActivity { it.touchNanos = 0; it.touchEvents = 0 }
        val t0 = SystemClock.uptimeMillis()
        gesture(pts, buttons = MotionEvent.BUTTON_STYLUS_PRIMARY, stepMs = 0)
        val ms = SystemClock.uptimeMillis() - t0
        SystemClock.sleep(500)
        shot("13-busy-erase")
        val left = live(s, "freedraw")
        var mainMs = 0.0; var n = 0
        s.onActivity { mainMs = it.touchNanos / 1e6; n = it.touchEvents }
        log("busy erase: $n events, app main-thread time ${"%.2f".format(mainMs / n)} ms/event (wall ${"%.1f".format(ms / 120.0)} ms/event incl. emulator software GPU), erased ${1500 - left} strokes")
        assertTrue("erased something", left < 1500)
        // Budget: a 120 Hz frame is 8.3 ms; the app's own handling must stay well below that.
        assertTrue("eraser handling too slow: ${mainMs / n} ms/event", mainMs / n < 4.0)
        s.close()
    }

    /** Overview screenshots of the toolbar and its submenus. */
    @Test fun toolbarAndSubmenus() {
        val s = launch(note(Pages.newPagedScene(PageOrientation.LANDSCAPE, PageDirection.HORIZONTAL).toJson(), "toolbar"))
        val r = surfaceRect(s)
        gesture(wave(r, r.exactCenterY()))
        shot("08-toolbar")
        device.findObject(By.desc("Color")).click(); device.wait(Until.hasObject(By.text("Palette")), 3000); shot("09-color-popup"); device.pressBack()
        device.findObject(By.desc("Thickness")).click(); SystemClock.sleep(500); shot("10-thickness-popup"); device.pressBack()
        device.findObject(By.desc("Add page")).click(); SystemClock.sleep(800)
        assertEquals(2, Pages.pages(scene(s)).size)
        shot("11-second-page")
        device.findObject(By.desc("Pen")).click(); SystemClock.sleep(500); shot("14-pen-types"); device.pressBack()
        device.findObject(By.desc("Fullscreen")).click(); SystemClock.sleep(800); shot("15-fullscreen")
        device.findObject(By.desc("Exit fullscreen")).click(); SystemClock.sleep(500)
        s.close()
    }
}
