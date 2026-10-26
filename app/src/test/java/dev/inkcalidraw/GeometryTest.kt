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

class PagesTest {
    private fun pdfNote(n: Int): ExcalidrawScene {
        val s = ExcalidrawScene.empty()
        for (i in 0 until n) Pages.addPage(s, PageOrientation.PORTRAIT, PageDirection.VERTICAL, pdfFile = "lecture.pdf", pdfPage = i)
        return s
    }

    @Test fun insertingIntoAPdfKeepsEveryPdfPointer() {
        val s = pdfNote(4)
        val p3 = Pages.pages(s)[2]
        s.elements.add(ExcalidrawScene.newFreedraw(listOf(floatArrayOf(100f, (p3.y + 100).toFloat(), .5f), floatArrayOf(200f, (p3.y + 120).toFloat(), .5f)), "#000", 2.0, FractionalIndex.after(s.lastIndex()), frameId = p3.id))
        val inserted = Pages.insertPage(s, 2, PageDirection.VERTICAL, paper = "lined")

        val ps = Pages.pages(s)
        assertEquals(listOf(1, 2, 3, 4, 5), ps.map { it.number })
        val frames = ps.map { p -> s.elements.first { it.id == p.id } }
        assertEquals(listOf(0, null, 1, 2, 3), frames.map { it.pdfPage })       // pointers unchanged, new page has none
        assertEquals("lined", frames[1].backgroundKind)
        assertEquals(inserted.id, frames[1].id)
        // layout: contiguous, no overlaps, ink moved with its page
        ps.zipWithNext().forEach { (a, b) -> assertEquals(a.y + a.h + Pages.GAP, b.y, 1e-9) }
        val ink = s.elements.first { it.type == "freedraw" }
        val newP3 = ps.first { it.id == p3.id }
        assertEquals(newP3.y - p3.y, ink.y - (p3.y + 100), 1e-3)
    }

    @Test fun deleteDuplicateAndMove() {
        val s = pdfNote(3)
        val ids = Pages.pages(s).map { it.id }
        Pages.duplicatePage(s, ids[0], PageDirection.VERTICAL)
        assertEquals(listOf(0, 0, 1, 2), Pages.pages(s).map { p -> s.elements.first { it.id == p.id }.pdfPage })
        Pages.deletePage(s, ids[1], PageDirection.VERTICAL)
        assertEquals(listOf(0, 0, 2), Pages.pages(s).map { p -> s.elements.first { it.id == p.id }.pdfPage })
        Pages.movePage(s, ids[2], -1, PageDirection.VERTICAL)
        assertEquals(listOf(0, 2, 0), Pages.pages(s).map { p -> s.elements.first { it.id == p.id }.pdfPage })
        assertEquals(listOf(1, 2, 3), Pages.pages(s).map { it.number })
        assertEquals(listOf("Page 1", "Page 2", "Page 3"), Pages.pages(s).map { p -> s.elements.first { it.id == p.id }.name })
    }
}
