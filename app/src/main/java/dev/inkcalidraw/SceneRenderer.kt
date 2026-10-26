package dev.inkcalidraw

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Base64
import android.util.LruCache
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Draws an Excalidraw scene onto any Canvas (render thread for the editor, background thread for
 * thumbnails, PdfDocument pages for export). Freedraw uses the exact perfect-freehand outline
 * Excalidraw uses; elements with a frameId are clipped to their frame like Excalidraw does.
 *
 * Not thread-safe per instance: give each thread its own renderer. Decoded bitmaps are shared.
 */
class SceneRenderer(private val dark: Boolean = false, private val syncImages: Boolean = false) {

    class Input(
        val elements: List<Element>,
        val fileDataUrl: (String) -> String?,
        val background: String,
        /** Visible world rect for culling, or null to draw everything. */
        val visible: Geometry.Box? = null,
        val offsetIds: Set<String> = emptySet(),
        val dx: Float = 0f,
        val dy: Float = 0f,
        /** Paged notes: draw a desk colour outside and white sheets for pages. */
        val paged: Boolean = false,
        val deskColor: Int = Color.LTGRAY,
    )

    /** Called (from a background thread) when an image finished decoding and a redraw would show it. */
    var onImageReady: (() -> Unit)? = null

    private val pathCache = HashMap<String, Path>()
    private val boundsCache = HashMap<String, Geometry.Box>()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 0, 0, 0) }

    fun color(c: Int) = if (dark) Ui.darkTransform(c) else c

    fun draw(c: Canvas, input: Input) {
        val bg = color(parseColor(input.background, Color.WHITE))
        val pages = if (input.paged) Pages.pages(input.elements) else emptyList()
        c.drawColor(if (input.paged) input.deskColor else bg)
        for (p in pages) {
            val r = RectF(p.x.toFloat(), p.y.toFloat(), (p.x + p.w).toFloat(), (p.y + p.h).toFloat())
            if (input.offsetIds.isEmpty() || true) {
                c.drawRect(RectF(r.left + 2f, r.top + 3f, r.right + 2f, r.bottom + 4f), shadow)
            }
            fill.color = bg; fill.alpha = 255
            c.drawRect(r, fill)
        }
        val frames = HashMap<String, RectF>()
        for (el in input.elements) if (el.type == "frame" && !el.isDeleted) {
            frames[el.id] = RectF(el.x.toFloat(), el.y.toFloat(), (el.x + el.width).toFloat(), (el.y + el.height).toFloat())
        }
        for (el in input.elements) {
            if (el.isDeleted || el.type == "frame") continue
            if (input.visible != null && !boundsOf(el).intersects(input.visible)) continue
            val shifted = el.id in input.offsetIds
            val clip = el.frameId?.let { frames[it] }
            c.save()
            if (clip != null) c.clipRect(clip)
            if (shifted) c.translate(input.dx, input.dy)
            try { drawElement(c, el, input) } catch (_: Exception) { /* one odd element must not kill the canvas */ }
            c.restore()
        }
        // Frames that aren't our pages: Excalidraw draws an outline + name.
        for (el in input.elements) if (el.type == "frame" && !el.isDeleted && el.pageNumber == null) {
            stroke.color = color(Color.parseColor("#bbbbbb")); stroke.strokeWidth = 1f; stroke.alpha = 255
            c.drawRect(frames[el.id]!!, stroke)
            text.color = color(Color.parseColor("#999999")); text.textSize = 14f
            c.drawText(el.name ?: "Frame", el.x.toFloat(), el.y.toFloat() - 6f, text)
        }
    }

    fun boundsOf(el: Element): Geometry.Box = boundsCache.getOrPut("${el.id}:${el.version}") { Geometry.bounds(el) }

    fun freedrawPath(el: Element): Path = pathCache.getOrPut("${el.id}:${el.version}") { outlinePath(el.freedrawOutline()) }

    private fun drawElement(c: Canvas, el: Element, input: Input) {
        val alpha = (el.opacity / 100.0 * 255).toInt().coerceIn(0, 255)
        val col = color(parseColor(el.strokeColor, Color.BLACK))
        c.translate(el.x.toFloat(), el.y.toFloat())
        if (el.angle != 0.0) c.rotate(Math.toDegrees(el.angle).toFloat(), (el.width / 2).toFloat(), (el.height / 2).toFloat())
        when (el.type) {
            "freedraw" -> { fill.color = col; fill.alpha = alpha; c.drawPath(freedrawPath(el), fill) }
            "rectangle", "diamond", "ellipse" -> {
                val r = RectF(0f, 0f, el.width.toFloat(), el.height.toFloat())
                if (el.backgroundColor != "transparent") {
                    fill.color = color(parseColor(el.backgroundColor, Color.TRANSPARENT)); fill.alpha = alpha
                    shape(c, el.type, r, fill)
                }
                stroke.color = col; stroke.alpha = alpha; stroke.strokeWidth = el.strokeWidth.toFloat()
                shape(c, el.type, r, stroke)
            }
            "line", "arrow" -> {
                val pts = el.points
                if (pts.size >= 2) {
                    val p = Path().apply { moveTo(pts[0][0].toFloat(), pts[0][1].toFloat()); pts.drop(1).forEach { lineTo(it[0].toFloat(), it[1].toFloat()) } }
                    stroke.color = col; stroke.alpha = alpha; stroke.strokeWidth = el.strokeWidth.toFloat()
                    c.drawPath(p, stroke)
                }
            }
            "image" -> {
                val r = RectF(0f, 0f, el.width.toFloat(), el.height.toFloat())
                val bmp = el.fileId?.let { bitmapFor(it, input.fileDataUrl) }
                if (bmp == null) {
                    fill.color = color(Color.parseColor("#e9ecef")); fill.alpha = alpha; c.drawRect(r, fill)
                } else {
                    val (sx, sy) = el.imageScale
                    c.scale(sx, sy, r.centerX(), r.centerY())
                    imagePaint.alpha = alpha
                    c.drawBitmap(bmp, null, r, imagePaint) // like Excalidraw, images are not inverted in dark mode
                }
            }
            "text" -> {
                val fontSize = (el.json["fontSize"] as? JsonPrimitive)?.doubleOrNull ?: 20.0
                val content = (el.json["text"] as? JsonPrimitive)?.content ?: ""
                text.color = col; text.alpha = alpha; text.textSize = fontSize.toFloat()
                content.split('\n').forEachIndexed { i, line -> c.drawText(line, 0f, (fontSize * (i + 1)).toFloat(), text) }
            }
            else -> {}
        }
    }

    private fun shape(c: Canvas, type: String, r: RectF, p: Paint) {
        when (type) {
            "rectangle" -> c.drawRect(r, p)
            "ellipse" -> c.drawOval(r, p)
            "diamond" -> c.drawPath(Path().apply {
                moveTo(r.centerX(), r.top); lineTo(r.right, r.centerY()); lineTo(r.centerX(), r.bottom); lineTo(r.left, r.centerY()); close()
            }, p)
        }
    }

    private fun bitmapFor(fileId: String, dataUrl: (String) -> String?): Bitmap? {
        bitmaps.get(fileId)?.let { return it }
        if (syncImages) return decode(fileId, dataUrl)
        if (pending.putIfAbsent(fileId, true) == null) {
            val src = dataUrl(fileId)
            decoder.execute {
                try { if (src != null) decode(fileId) { src } } finally { pending.remove(fileId) }
                onImageReady?.invoke()
            }
        }
        return null
    }

    companion object {
        /** Decoded images, shared by all renderers (native memory; budget in bytes). */
        private val bitmaps = object : LruCache<String, Bitmap>(256 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
        private val pending = ConcurrentHashMap<String, Boolean>()
        private val decoder = Executors.newFixedThreadPool(2)

        private fun decode(fileId: String, dataUrl: (String) -> String?): Bitmap? {
            val url = dataUrl(fileId) ?: return null
            val b64 = url.substringAfter("base64,", "")
            if (b64.isEmpty()) return null
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val opaque = url.startsWith("data:image/jpeg")
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                if (opaque) inPreferredConfig = Bitmap.Config.RGB_565 // halves memory for PDF pages / photos
            }) ?: return null
            bitmaps.put(fileId, bmp)
            return bmp
        }

        fun outlinePath(outline: List<DoubleArray>): Path {
            val path = Path()
            PerfectFreehand.emitPath(outline, object : PerfectFreehand.PathSink {
                override fun moveTo(x: Double, y: Double) = path.moveTo(x.toFloat(), y.toFloat())
                override fun quadTo(cx: Double, cy: Double, x: Double, y: Double) = path.quadTo(cx.toFloat(), cy.toFloat(), x.toFloat(), y.toFloat())
                override fun lineTo(x: Double, y: Double) = path.lineTo(x.toFloat(), y.toFloat())
                override fun close() = path.close()
            })
            return path
        }

        fun parseColor(s: String, fallback: Int): Int = try {
            if (s == "transparent") Color.TRANSPARENT
            else Color.parseColor(if (s.length == 4 && s.startsWith("#")) "#" + s.drop(1).map { "$it$it" }.joinToString("") else s)
        } catch (_: Exception) { fallback }
    }
}
