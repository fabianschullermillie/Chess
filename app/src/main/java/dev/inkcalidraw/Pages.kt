package dev.inkcalidraw

import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

/**
 * Page model for A4/PDF notes. A page is an Excalidraw frame element (customData.inkcalidrawPage = n);
 * whatever is drawn on it gets frameId = that frame, so Excalidraw clips and moves it with the page.
 * Pure Kotlin so the layout logic is unit-testable.
 */
object Pages {
    /** A4 in CSS pixels (96 dpi), which is Excalidraw's scene unit. */
    const val A4_SHORT = 794.0
    const val A4_LONG = 1123.0
    const val GAP = 48.0

    data class Page(val id: String, val number: Int, val x: Double, val y: Double, val w: Double, val h: Double) {
        val box get() = Geometry.Box(x, y, x + w, y + h)
        fun contains(px: Double, py: Double) = px >= x && px <= x + w && py >= y && py <= y + h
    }

    fun size(o: PageOrientation) = if (o == PageOrientation.PORTRAIT) A4_SHORT to A4_LONG else A4_LONG to A4_SHORT

    fun pages(scene: ExcalidrawScene): List<Page> = pages(scene.elements)

    fun pages(elements: List<Element>): List<Page> = elements
        .filter { it.type == "frame" && !it.isDeleted && it.pageNumber != null }
        .map { Page(it.id, it.pageNumber!!, it.x, it.y, it.width, it.height) }
        .sortedBy { it.number }

    fun pageAt(pages: List<Page>, x: Double, y: Double): Page? = pages.firstOrNull { it.contains(x, y) }

    /** Page whose centre is closest to (x, y): "the current page" for a viewport centred there. */
    fun nearest(pages: List<Page>, x: Double, y: Double): Int =
        pages.indices.minByOrNull { val p = pages[it]; abs(p.x + p.w / 2 - x) + abs(p.y + p.h / 2 - y) } ?: -1

    /** Where the next page goes, keeping pages centred on a common axis like Samsung Notes. */
    fun nextSlot(pages: List<Page>, w: Double, h: Double, dir: PageDirection, origin: Pair<Double, Double> = 0.0 to 0.0): Pair<Double, Double> {
        val last = pages.lastOrNull() ?: return origin
        val first = pages.first()
        return if (dir == PageDirection.VERTICAL) {
            val cx = first.x + first.w / 2
            (cx - w / 2) to (last.y + last.h + GAP)
        } else {
            val cy = first.y + first.h / 2
            (last.x + last.w + GAP) to (cy - h / 2)
        }
    }

    /** Appends a page; returns the new frame element. */
    fun addPage(scene: ExcalidrawScene, orientation: PageOrientation, dir: PageDirection, w: Double? = null, h: Double? = null): Element {
        val existing = pages(scene)
        val (dw, dh) = size(orientation)
        val pw = w ?: dw; val ph = h ?: dh
        // First page of a note that already has whiteboard content: put the pages next to it, not on top.
        val origin = if (existing.isEmpty()) Geometry.contentBounds(scene.elements)?.let { (it.maxX + 200) to it.minY } ?: (0.0 to 0.0) else 0.0 to 0.0
        val (x, y) = nextSlot(existing, pw, ph, dir, origin)
        val n = (existing.maxOfOrNull { it.number } ?: 0) + 1
        val frame = ExcalidrawScene.newFrame("Page $n", x, y, pw, ph, FractionalIndex.after(scene.lastIndex()), n)
        scene.elements.add(frame)
        return frame
    }

    /** Re-flows all pages along [dir], moving each page's content with it. */
    fun relayout(scene: ExcalidrawScene, dir: PageDirection) {
        val ps = pages(scene)
        if (ps.isEmpty()) return
        val first = ps.first()
        val axis = if (dir == PageDirection.VERTICAL) first.x + first.w / 2 else first.y + first.h / 2
        var cursor = if (dir == PageDirection.VERTICAL) first.y else first.x
        val delta = HashMap<String, Pair<Double, Double>>()
        for (p in ps) {
            val nx: Double; val ny: Double
            if (dir == PageDirection.VERTICAL) { nx = axis - p.w / 2; ny = cursor; cursor += p.h + GAP }
            else { nx = cursor; ny = axis - p.h / 2; cursor += p.w + GAP }
            delta[p.id] = (nx - p.x) to (ny - p.y)
        }
        for (i in scene.elements.indices) {
            val el = scene.elements[i]
            val d = delta[el.id] ?: el.frameId?.let { delta[it] } ?: continue
            if (d.first == 0.0 && d.second == 0.0) continue
            scene.elements[i] = el.withChanges("x" to JsonPrimitive(el.x + d.first), "y" to JsonPrimitive(el.y + d.second))
        }
    }

    /** Assigns frameId by the page that contains the element's centre (or clears it). */
    fun frameFor(pages: List<Page>, el: Element): String? {
        val b = Geometry.bounds(el)
        return pageAt(pages, b.centerX, b.centerY)?.id
    }

    fun newPagedScene(orientation: PageOrientation, dir: PageDirection): ExcalidrawScene {
        val s = ExcalidrawScene.empty()
        addPage(s, orientation, dir)
        NoteMeta(paged = true, orientation = orientation, direction = dir).writeTo(s)
        return s
    }
}
