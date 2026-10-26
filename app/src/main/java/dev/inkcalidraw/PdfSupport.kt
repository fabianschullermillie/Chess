package dev.inkcalidraw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * PDF annotation support.
 * Import: each PDF page becomes a page frame with the rendered page as a locked image underneath,
 * so you write on top of it and Excalidraw shows the same thing.
 * Export: every page (frame) is drawn into a real PDF page, strokes stay vector.
 */
object PdfSupport {
    private const val PX_PER_PT = 96.0 / 72.0
    private const val MAX_SIDE = 2400

    /** Appends the PDF's pages to [scene]; returns the number of pages added. */
    fun import(
        ctx: Context, uri: Uri, scene: ExcalidrawScene, dir: PageDirection,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        val pfd: ParcelFileDescriptor = ctx.contentResolver.openFileDescriptor(uri, "r") ?: error("can't open PDF")
        pfd.use {
            PdfRenderer(it).use { pdf ->
                for (i in 0 until pdf.pageCount) {
                    pdf.openPage(i).use { page ->
                        val w = page.width * PX_PER_PT
                        val h = page.height * PX_PER_PT
                        val frame = Pages.addPage(scene, if (w > h) PageOrientation.LANDSCAPE else PageOrientation.PORTRAIT, dir, w, h)
                        val k = minOf(2.0, MAX_SIDE / maxOf(w, h)) // ~192 dpi for A4
                        val bmp = Bitmap.createBitmap((w * k).toInt(), (h * k).toInt(), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        val m = Matrix().apply { setScale((k * PX_PER_PT).toFloat(), (k * PX_PER_PT).toFloat()) }
                        page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val out = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        bmp.recycle()
                        val bytes = out.toByteArray()
                        val id = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { b -> "%02x".format(b) }
                        scene.addFile(id, "image/jpeg", "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
                        scene.elements.add(
                            ExcalidrawScene.newImage(id, frame.x, frame.y, w, h, FractionalIndex.after(scene.lastIndex()), frameId = frame.id, locked = true)
                        )
                        onProgress(i + 1, pdf.pageCount)
                    }
                }
                return pdf.pageCount
            }
        }
    }

    /** Writes the note as a PDF: one PDF page per note page, or the whole drawing on one page for whiteboards. */
    fun export(scene: ExcalidrawScene, out: OutputStream) {
        val doc = PdfDocument()
        val elements = scene.snapshot()
        val renderer = SceneRenderer(dark = false, syncImages = true)
        val pages = Pages.pages(elements)
        val rects: List<RectF> = if (pages.isNotEmpty()) {
            pages.map { RectF(it.x.toFloat(), it.y.toFloat(), (it.x + it.w).toFloat(), (it.y + it.h).toFloat()) }
        } else {
            val b = Geometry.contentBounds(elements) ?: Geometry.Box(0.0, 0.0, Pages.A4_SHORT, Pages.A4_LONG)
            listOf(RectF(b.minX.toFloat() - 24, b.minY.toFloat() - 24, b.maxX.toFloat() + 24, b.maxY.toFloat() + 24))
        }
        val files = scene.files.toMap()
        val input = SceneRenderer.Input(elements, { id -> (files[id]?.get("dataURL") as? kotlinx.serialization.json.JsonPrimitive)?.content }, "#ffffff")
        rects.forEachIndexed { i, r ->
            val wPt = (r.width() / PX_PER_PT).toInt().coerceAtLeast(1)
            val hPt = (r.height() / PX_PER_PT).toInt().coerceAtLeast(1)
            val page = doc.startPage(PdfDocument.PageInfo.Builder(wPt, hPt, i + 1).create())
            val c = page.canvas
            c.scale((1 / PX_PER_PT).toFloat(), (1 / PX_PER_PT).toFloat())
            c.translate(-r.left, -r.top)
            c.clipRect(r)
            renderer.draw(c, input)
            doc.finishPage(page)
        }
        doc.writeTo(out)
        doc.close()
    }
}
