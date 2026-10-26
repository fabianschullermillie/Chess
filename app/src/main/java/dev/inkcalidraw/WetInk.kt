package dev.inkcalidraw

import kotlin.math.max

/**
 * Which part of a growing perfect-freehand stroke can change when samples are appended.
 * Only the last few stroke points (sharp-corner look-ahead, the "skip points within 3px of the end"
 * rule and the end cap) depend on later input, so redrawing a box around the tail is enough.
 * Verified exhaustively in WetInkTest.
 */
object WetInk {
    /** Strokes shorter than this are redrawn whole (pressure warm-up and min-length start). */
    const val FULL_REDRAW_BELOW = 14
    private const val TAIL_POINTS = 8

    /** Box [minX, minY, maxX, maxY] that contains everything that may differ from the previous frame. */
    fun tailBox(strokePoints: List<PerfectFreehand.StrokePoint>, samples: FloatArray, count: Int, total: Int, size: Double): Geometry.Box {
        val predicted = total - count
        val box = Geometry.Box(Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        fun add(x: Double, y: Double) {
            if (x < box.minX) box.minX = x; if (y < box.minY) box.minY = y
            if (x > box.maxX) box.maxX = x; if (y > box.maxY) box.maxY = y
        }
        for (i in max(0, strokePoints.size - TAIL_POINTS - predicted) until strokePoints.size) add(strokePoints[i].point[0], strokePoints[i].point[1])
        for (i in max(0, count - 4) until total) add(samples[i * 3].toDouble(), samples[i * 3 + 1].toDouble())
        return box.inflate(size * 1.2 + 4)
    }
}
