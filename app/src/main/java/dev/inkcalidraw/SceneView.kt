package dev.inkcalidraw

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Renders the committed ("dry") scene. Freedraw elements use the exact perfect-freehand outline
 * Excalidraw uses, so what you see here is what you'll see on excalidraw.com / Obsidian.
 * Other element types get a simple, non-sketchy rendering (the prototype only *authors* freedraw;
 * everything else is preserved untouched).
 */
class SceneView(context: Context) : View(context) {
    var scene: ExcalidrawScene = ExcalidrawScene.empty()
        set(value) { field = value; pathCache.clear(); invalidate() }

    /** world (scene) -> screen */
    val viewMatrix = Matrix()

    private val pathCache = HashMap<String, Path>()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)

    fun elementsChanged() = invalidate()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(parseColor(scene.backgroundColor, Color.WHITE))
        canvas.save()
        canvas.concat(viewMatrix)
        for (el in scene.elements) {
            if (el.isDeleted) continue
            try { drawElement(canvas, el) } catch (_: Exception) { /* never let one odd element kill the canvas */ }
        }
        canvas.restore()
    }

    private fun drawElement(c: Canvas, el: Element) {
        val alpha = (el.opacity / 100.0 * 255).toInt().coerceIn(0, 255)
        val color = parseColor(el.strokeColor, Color.BLACK)
        c.save()
        c.translate(el.x.toFloat(), el.y.toFloat())
        if (el.angle != 0.0) c.rotate(Math.toDegrees(el.angle).toFloat(), (el.width / 2).toFloat(), (el.height / 2).toFloat())
        when (el.type) {
            "freedraw" -> {
                val key = "${el.id}:${el.version}"
                val path = pathCache.getOrPut(key) { freedrawPath(el) }
                fill.color = color; fill.alpha = alpha
                c.drawPath(path, fill)
            }
            "rectangle", "diamond", "ellipse" -> {
                val r = RectF(0f, 0f, el.width.toFloat(), el.height.toFloat())
                val bg = el.backgroundColor
                if (bg != "transparent") {
                    fill.color = parseColor(bg, Color.TRANSPARENT); fill.alpha = alpha
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
            "text" -> {
                val fontSize = (el.json["fontSize"] as? JsonPrimitive)?.doubleOrNull ?: 20.0
                val content = (el.json["text"] as? JsonPrimitive)?.content ?: ""
                text.color = color; text.alpha = alpha; text.textSize = fontSize.toFloat()
                content.split('\n').forEachIndexed { i, line ->
                    c.drawText(line, 0f, (fontSize * (i + 1) * 1.0).toFloat(), text)
                }
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

    private fun freedrawPath(el: Element): Path {
        val path = Path()
        PerfectFreehand.emitPath(el.freedrawOutline(), object : PerfectFreehand.PathSink {
            override fun moveTo(x: Double, y: Double) = path.moveTo(x.toFloat(), y.toFloat())
            override fun quadTo(cx: Double, cy: Double, x: Double, y: Double) = path.quadTo(cx.toFloat(), cy.toFloat(), x.toFloat(), y.toFloat())
            override fun lineTo(x: Double, y: Double) = path.lineTo(x.toFloat(), y.toFloat())
            override fun close() = path.close()
        })
        return path
    }

    companion object {
        fun parseColor(s: String, fallback: Int): Int = try {
            if (s == "transparent") Color.TRANSPARENT else Color.parseColor(if (s.length == 4 && s.startsWith("#")) "#" + s.drop(1).map { "$it$it" }.joinToString("") else s)
        } catch (_: Exception) { fallback }
    }
}
