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
