package dev.inkcalidraw

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Draws an Excalidraw scene onto any Canvas (the live editor and the note thumbnails share this).
 * Freedraw elements use the exact perfect-freehand outline Excalidraw uses. Other element types get a
 * simple, non-sketchy rendering; they are preserved untouched in the file.
 */
class SceneRenderer {
    private val pathCache = HashMap<String, Path>()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)

    private var currentScene: ExcalidrawScene? = null
    private val bitmaps = object : android.util.LruCache<String, android.graphics.Bitmap>(96 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.allocationByteCount
    }
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun clearCache() { pathCache.clear(); bitmaps.evictAll() }

    private fun bitmapFor(fileId: String): android.graphics.Bitmap? {
        bitmaps.get(fileId)?.let { return it }
        val dataUrl = currentScene?.fileDataUrl(fileId) ?: return null
        val b64 = dataUrl.substringAfter("base64,", "")
        if (b64.isEmpty()) return null
        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        bitmaps.put(fileId, bmp)
        return bmp
    }

    /** Draws all live elements; elements whose id is in [offsetIds] are drawn shifted by (dx, dy). */
    fun draw(c: Canvas, scene: ExcalidrawScene, offsetIds: Set<String> = emptySet(), dx: Float = 0f, dy: Float = 0f) {
        currentScene = scene
        for (el in scene.elements) {
            if (el.isDeleted) continue
            val shifted = el.id in offsetIds
            if (shifted) { c.save(); c.translate(dx, dy) }
            try { drawElement(c, el) } catch (_: Exception) { /* one odd element must not kill the canvas */ }
            if (shifted) c.restore()
        }
    }

    fun freedrawPath(el: Element): Path = pathCache.getOrPut("${el.id}:${el.version}") {
        val path = Path()
        PerfectFreehand.emitPath(el.freedrawOutline(), object : PerfectFreehand.PathSink {
            override fun moveTo(x: Double, y: Double) = path.moveTo(x.toFloat(), y.toFloat())
            override fun quadTo(cx: Double, cy: Double, x: Double, y: Double) = path.quadTo(cx.toFloat(), cy.toFloat(), x.toFloat(), y.toFloat())
            override fun lineTo(x: Double, y: Double) = path.lineTo(x.toFloat(), y.toFloat())
            override fun close() = path.close()
        })
        path
    }

    private fun drawElement(c: Canvas, el: Element) {
        val alpha = (el.opacity / 100.0 * 255).toInt().coerceIn(0, 255)
        val color = parseColor(el.strokeColor, Color.BLACK)
        c.save()
        c.translate(el.x.toFloat(), el.y.toFloat())
        if (el.angle != 0.0) c.rotate(Math.toDegrees(el.angle).toFloat(), (el.width / 2).toFloat(), (el.height / 2).toFloat())
        when (el.type) {
            "freedraw" -> {
                fill.color = color; fill.alpha = alpha
                c.drawPath(freedrawPath(el), fill)
            }
            "rectangle", "diamond", "ellipse" -> {
                val r = RectF(0f, 0f, el.width.toFloat(), el.height.toFloat())
                if (el.backgroundColor != "transparent") {
                    fill.color = parseColor(el.backgroundColor, Color.TRANSPARENT); fill.alpha = alpha
                    shape(c, el.type, r, fill)
                }
                stroke.color = color; stroke.alpha = alpha; stroke.strokeWidth = el.strokeWidth.toFloat()
                shape(c, el.type, r, stroke)
            }
            "line", "arrow" -> {
                val pts = el.points
                if (pts.size >= 2) {
                    val p = Path().apply { moveTo(pts[0][0].toFloat(), pts[0][1].toFloat()); pts.drop(1).forEach { lineTo(it[0].toFloat(), it[1].toFloat()) } }
                    stroke.color = color; stroke.alpha = alpha; stroke.strokeWidth = el.strokeWidth.toFloat()
                    c.drawPath(p, stroke)
                }
            }
            "image" -> {
                val r = RectF(0f, 0f, el.width.toFloat(), el.height.toFloat())
                val bmp = el.fileId?.let { bitmapFor(it) }
                if (bmp == null) {
                    // Excalidraw shows a placeholder for missing files; do the same.
                    fill.color = Color.parseColor("#e9ecef"); fill.alpha = alpha; c.drawRect(r, fill)
                } else {
                    val (sx, sy) = el.imageScale
                    c.save()
                    c.scale(sx, sy, r.centerX(), r.centerY())
                    imagePaint.alpha = alpha
                    c.drawBitmap(bmp, null, r, imagePaint)
                    c.restore()
                }
            }
            "text" -> {
                val fontSize = (el.json["fontSize"] as? JsonPrimitive)?.doubleOrNull ?: 20.0
                val content = (el.json["text"] as? JsonPrimitive)?.content ?: ""
                text.color = color; text.alpha = alpha; text.textSize = fontSize.toFloat()
                content.split('\n').forEachIndexed { i, line -> c.drawText(line, 0f, (fontSize * (i + 1)).toFloat(), text) }
            }
            else -> {}
        }
        c.restore()
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

    companion object {
        fun parseColor(s: String, fallback: Int): Int = try {
            if (s == "transparent") Color.TRANSPARENT
            else Color.parseColor(if (s.length == 4 && s.startsWith("#")) "#" + s.drop(1).map { "$it$it" }.joinToString("") else s)
        } catch (_: Exception) { fallback }

        /** Axis-aligned scene-space bounds of an element (ignores rotation, good enough for UI). */
        fun bounds(el: Element): RectF {
            if (el.type == "freedraw" || el.type == "line" || el.type == "arrow") {
                val pts = el.points
                if (pts.isNotEmpty()) {
                    val pad = (el.strokeWidth * 4.25).toFloat()
                    return RectF(
                        (el.x + pts.minOf { it[0] }).toFloat() - pad, (el.y + pts.minOf { it[1] }).toFloat() - pad,
                        (el.x + pts.maxOf { it[0] }).toFloat() + pad, (el.y + pts.maxOf { it[1] }).toFloat() + pad,
                    )
                }
            }
            return RectF(el.x.toFloat(), el.y.toFloat(), (el.x + el.width).toFloat(), (el.y + el.height).toFloat())
        }

        fun contentBounds(elements: List<Element>): RectF? {
            val live = elements.filter { !it.isDeleted }
            if (live.isEmpty()) return null
            val r = RectF(bounds(live[0]))
            live.drop(1).forEach { r.union(bounds(it)) }
            return r
        }
    }
}
