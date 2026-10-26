package dev.inkcalidraw

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** The committed ("dry") scene plus selection overlays. Wet ink lives in an InProgressStrokesView on top. */
class SceneView(context: Context) : View(context) {
    var scene: ExcalidrawScene = ExcalidrawScene.empty()
        set(value) { field = value; renderer.clearCache(); invalidate() }

    /** world (scene) -> screen */
    val viewMatrix = Matrix()
    val renderer = SceneRenderer()

    // Selection overlay state (world coordinates)
    var selectedIds: Set<String> = emptySet()
    var dragDx = 0f
    var dragDy = 0f
    val lassoPath = Path()
    var showLasso = false
    var showHandle = false
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }

    /** World-space rect of the resize handle, if shown. */
    fun handleRectWorld(): RectF? {
        if (!showHandle) return null
        val b = selectionBounds() ?: return null
        val r = 9f / scale()
        return RectF(b.right - r, b.bottom - r, b.right + r, b.bottom + r)
    }

    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#6965db"); strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val lassoFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(24, 105, 101, 219) }
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#6965db"); strokeWidth = 2f }

    fun scale(): Float { val v = FloatArray(9); viewMatrix.getValues(v); return v[Matrix.MSCALE_X] }

    /** World-space bounds of the current selection, including any in-flight drag. */
    fun selectionBounds(): RectF? {
        val sel = scene.elements.filter { it.id in selectedIds && !it.isDeleted }
        val r = SceneRenderer.contentBounds(sel) ?: return null
        r.offset(dragDx, dragDy)
        return r
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(SceneRenderer.parseColor(scene.backgroundColor, Color.WHITE))
        canvas.save()
        canvas.concat(viewMatrix)
        renderer.draw(canvas, scene, selectedIds, dragDx, dragDy)
        val s = scale()
        if (showLasso) {
            lassoPaint.strokeWidth = 2f / s
            lassoPaint.pathEffect = DashPathEffect(floatArrayOf(10f / s, 8f / s), 0f)
            canvas.drawPath(lassoPath, lassoFill)
            canvas.drawPath(lassoPath, lassoPaint)
        }
        selectionBounds()?.let { r ->
            r.inset(-6f / s, -6f / s)
            selPaint.strokeWidth = 2f / s
            canvas.drawRoundRect(r, 6f / s, 6f / s, selPaint)
        }
        handleRectWorld()?.let { h ->
            canvas.drawOval(h, handleFill); canvas.drawOval(h, selPaint)
        }
        canvas.restore()
    }
}
