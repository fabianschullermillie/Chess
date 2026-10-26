package dev.inkcalidraw

import android.content.Context
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.serialization.json.JsonPrimitive
import java.io.OutputStream

/**
 * PDF annotation.
 * Import: the PDF is copied next to the note and each page becomes a page frame that references it
 * (customData.pdfFile/pdfPage). Nothing is rasterised into the note, so huge PDFs import instantly;
 * pages are rendered on screen at the resolution the zoom needs (PdfPages).
 * Export: pages are drawn into a new PDF with the strokes as vectors on top of the page.
 */
object PdfSupport {

    /** Appends the PDF's pages to [scene]; returns the number of pages added. */
    fun import(ctx: Context, store: NoteStore, folder: DocumentFile, src: Uri, displayName: String, scene: ExcalidrawScene, dir: PageDirection): Int {
        val stored = store.importFile(folder, src, displayName.removeSuffix(".pdf").removeSuffix(".PDF"), ".pdf")
        val uri = store.findFile(folder, stored) ?: error("PDF copy missing")
        val sizes = PdfPages(ctx) { null }.run { val s = pageSizes(uri); close(); s }
        sizes.forEachIndexed { i, (w, h) ->
            Pages.addPage(scene, if (w > h) PageOrientation.LANDSCAPE else PageOrientation.PORTRAIT, dir, w, h, pdfFile = stored, pdfPage = i)
        }
        return sizes.size
    }

    /** Writes the note as a PDF: one PDF page per note page, or the whole drawing on one page for whiteboards. */
    fun export(scene: ExcalidrawScene, pdfs: PdfPages?, out: OutputStream) {
        val doc = PdfDocument()
        val elements = scene.snapshot()
        val renderer = SceneRenderer(dark = false, syncImages = true)
        val frames = elements.filter { it.type == "frame" && !it.isDeleted && it.pageNumber != null }.sortedBy { it.pageNumber }
        val rects: List<Pair<RectF, Element?>> = if (frames.isNotEmpty()) {
            frames.map { RectF(it.x.toFloat(), it.y.toFloat(), (it.x + it.width).toFloat(), (it.y + it.height).toFloat()) to it }
        } else {
            val b = Geometry.contentBounds(elements) ?: Geometry.Box(0.0, 0.0, Pages.A4_SHORT, Pages.A4_LONG)
            listOf(RectF(b.minX.toFloat() - 24, b.minY.toFloat() - 24, b.maxX.toFloat() + 24, b.maxY.toFloat() + 24) to null)
        }
        val files = scene.files.toMap()
        val input = SceneRenderer.Input(
            elements, { id -> (files[id]?.get("dataURL") as? JsonPrimitive)?.content }, "#ffffff",
            pageImage = { f, _ -> // ~240 dpi page backgrounds
                val file = f.pdfFile; val page = f.pdfPage
                if (pdfs == null || file == null || page == null) null
                else pdfs.renderNow(file, page, (f.width * 2.5).toInt(), (f.height * 2.5).toInt())
            },
        )
        rects.forEachIndexed { i, (r, _) ->
            val wPt = (r.width() / PdfPages.PX_PER_PT).toInt().coerceAtLeast(1)
            val hPt = (r.height() / PdfPages.PX_PER_PT).toInt().coerceAtLeast(1)
            val page = doc.startPage(PdfDocument.PageInfo.Builder(wPt, hPt, i + 1).create())
            val c = page.canvas
            c.scale((1 / PdfPages.PX_PER_PT).toFloat(), (1 / PdfPages.PX_PER_PT).toFloat())
            c.translate(-r.left, -r.top)
            c.clipRect(r)
            renderer.draw(c, input)
            doc.finishPage(page)
        }
        doc.writeTo(out)
        doc.close()
    }
}
