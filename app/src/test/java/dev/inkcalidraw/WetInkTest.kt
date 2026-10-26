package dev.inkcalidraw

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The wet stroke on the front buffer is only cleared/redrawn inside a "dirty" box around the pen tip.
 * This checks, frame by frame, that every part of the outline that changes lies inside that box, so
 * the front buffer always shows exactly the stroke that will be committed.
 */
class WetInkTest {
    private data class Seg(val a: List<Long>)

    private fun segments(o: List<DoubleArray>): Map<Seg, List<DoubleArray>> {
        val out = HashMap<Seg, List<DoubleArray>>()
        val n = o.size
        for (i in 0 until n) {
            val prev = if (i == 0) o[0] else doubleArrayOf((o[i - 1][0] + o[i][0]) / 2, (o[i - 1][1] + o[i][1]) / 2)
            val next = o[(i + 1) % n].let { q -> doubleArrayOf((o[i][0] + q[0]) / 2, (o[i][1] + q[1]) / 2) }
            val pts = listOf(prev, o[i], next)
            out[Seg(pts.flatMap { p -> listOf((p[0] * 1e6).toLong(), (p[1] * 1e6).toLong()) })] = pts
        }
        return out
    }

    private fun stroke(seed: Int, n: Int, speed: Double): List<FloatArray> {
        val r = Random(seed)
        var x = 100.0; var y = 100.0; var a = 0.0
        return (0 until n).map { i ->
            a += (r.nextDouble() - 0.5) * 0.9 + if (i % 37 == 0) PI * 0.9 else 0.0 // occasional sharp turns
            x += cos(a) * speed * (0.3 + r.nextDouble()); y += sin(a) * speed * (0.3 + r.nextDouble())
            floatArrayOf(x.toFloat(), y.toFloat(), (0.2 + 0.7 * r.nextDouble()).toFloat())
        }
    }

    @Test
    fun tailBoxContainsEveryChange() {
        var frames = 0
        for (seed in 1..12) for (width in listOf(1.0, 2.0, 4.0, 8.0)) for (speed in listOf(0.7, 3.0, 12.0)) {
            val pts = stroke(seed, 140, speed)
            val opts = PerfectFreehand.excalidrawOptions(width, simulatePressure = false, last = false)
            var prevOutline: List<DoubleArray>? = null
            var prevTail: Geometry.Box? = null
            for (k in 1..pts.size) {
                val predicted = if (seed % 2 == 0) minOf(4, pts.size - k) else 0 // emulate motion prediction
                val total = k + predicted
                val samples = FloatArray(total * 3).also { a -> for (i in 0 until total) { a[i * 3] = pts[i][0]; a[i * 3 + 1] = pts[i][1]; a[i * 3 + 2] = pts[i][2] } }
                val input = (0 until total).map { doubleArrayOf(samples[it * 3].toDouble(), samples[it * 3 + 1].toDouble(), samples[it * 3 + 2].toDouble()) }.toMutableList()
                if (input.size == 1) input.add(input[0].copyOf())
                val sp = PerfectFreehand.getStrokePoints(input, opts)
                val outline = PerfectFreehand.getStrokeOutlinePoints(sp, opts)
                val tail = if (sp.size < WetInk.FULL_REDRAW_BELOW) {
                    Geometry.Box(outline.minOf { it[0] }, outline.minOf { it[1] }, outline.maxOf { it[0] }, outline.maxOf { it[1] }).inflate(opts.size * 1.2 + 4)
                } else WetInk.tailBox(sp, samples, k, total, opts.size)
                val dirty = prevTail?.union(tail) ?: tail
                prevOutline?.let { po ->
                    val a = segments(po); val b = segments(outline)
                    val changed = (a.keys - b.keys).map { a[it]!! } + (b.keys - a.keys).map { b[it]!! }
                    for (seg in changed) for (p in seg) assertTrue(
                        "seed=$seed w=$width speed=$speed frame=$k: changed outline at (${p[0]}, ${p[1]}) outside dirty $dirty",
                        dirty.inflate(0.01).contains(p[0], p[1]),
                    )
                }
                prevOutline = outline; prevTail = tail; frames++
            }
        }
        println("checked $frames wet-ink frames")
    }
}
