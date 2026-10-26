package dev.inkcalidraw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {
    @Test fun segSegDistance() {
        assertEquals(0.0, Geometry.segSegDist(0.0, 0.0, 10.0, 10.0, 0.0, 10.0, 10.0, 0.0), 1e-9) // crossing
        assertEquals(5.0, Geometry.segSegDist(0.0, 0.0, 10.0, 0.0, 0.0, 5.0, 10.0, 5.0), 1e-9)   // parallel
        assertEquals(5.0, Geometry.segSegDist(0.0, 0.0, 0.0, 10.0, 5.0, 5.0, 20.0, 5.0), 1e-9)
    }

    /** A fast eraser swipe whose samples land on both sides of a stroke must still erase it. */
    @Test fun eraserSweepCatchesFastSwipes() {
        val stroke = ExcalidrawScene.newFreedraw(listOf(floatArrayOf(100f, 0f, .5f), floatArrayOf(100f, 200f, .5f)), "#000", 1.0, "a0")
        assertTrue(Geometry.eraserHits(stroke, 0.0, 100.0, 200.0, 100.0, 5.0))
        assertFalse(Geometry.eraserHits(stroke, 0.0, 100.0, 80.0, 100.0, 5.0))
    }

    @Test fun pointInPolygon() {
        val sq = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(10.0, 0.0), doubleArrayOf(10.0, 10.0), doubleArrayOf(0.0, 10.0))
        assertTrue(Geometry.pointInPolygon(5.0, 5.0, sq)); assertFalse(Geometry.pointInPolygon(15.0, 5.0, sq))
    }
}

class EraserPerfTest {
    /** 2000 handwritten strokes, 2000 eraser sweeps: must stay well within a frame budget per sweep. */
    @Test fun eraserScalesToBigNotes() {
        val r = kotlin.random.Random(1)
        val els = (0 until 2000).map { i ->
            val ox = (i % 40) * 60f; val oy = (i / 40) * 80f
            ExcalidrawScene.newFreedraw((0 until 150).map { k -> floatArrayOf(ox + k * 0.4f, oy + 20 * kotlin.math.sin(k / 10f), .5f) }, "#000", 2.0, null)
        }
        els.forEach { it.bounds } // what the app has cached after the first frame
        val t0 = System.nanoTime()
        var hits = 0
        repeat(2000) { s ->
            val x = r.nextDouble() * 2400; val y = r.nextDouble() * 4000
            for (e in els) if (Geometry.eraserHits(e, x, y, x + 8, y + 3, 10.0)) hits++
        }
        val perSweepUs = (System.nanoTime() - t0) / 1000.0 / 2000
        println("eraser: ${"%.1f".format(perSweepUs)} µs per sweep over 2000 strokes, hits=$hits")
        assertTrue("eraser sweep too slow: $perSweepUs µs", perSweepUs < 2000)
    }
}
