package dev.inkcalidraw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.util.concurrent.Executors

/**
 * Renders pages of the PDFs a note annotates, on demand and at the resolution the current zoom needs.
 * The PDF itself is never copied into the .excalidraw file, so even very large PDFs open instantly.
 *
 * Thread-safe: [get] may be called from the render thread; rendering happens on one worker thread
 * (PdfRenderer allows only one open page at a time).
 */
class PdfPages(private val context: Context, private val resolve: (String) -> Uri?) {
    /** Called on the worker thread when a requested page finished rendering. */
    var onReady: (() -> Unit)? = null

    private val worker = Executors.newSingleThreadExecutor()
    private val renderers = HashMap<String, Pair<ParcelFileDescriptor, PdfRenderer>>() // worker thread only
    private val cache = object : LruCache<String, Bitmap>(220 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val queue = ArrayDeque<Request>()
    private val queued = HashSet<String>()
    @Volatile private var running = false

    private class Request(val key: String, val file: String, val page: Int, val w: Int, val h: Int)

    /**
     * Best available bitmap for [page] drawn at [pxPerUnit] screen pixels per scene unit. If the ideal
     * resolution isn't cached yet it is rendered in the background and a lower one returned meanwhile.
     */
    fun get(file: String, page: Int, pageW: Double, pageH: Double, pxPerUnit: Float): Bitmap? {
        val level = LEVELS.firstOrNull { it >= pxPerUnit } ?: LEVELS.last()
        val key = key(file, page, level)
        cache.get(key)?.let { return it }
        val cap = minOf(1.0, 4096.0 / (maxOf(pageW, pageH) * level)) // stay under GPU texture limits
        request(Request(key, file, page, (pageW * level * cap).toInt().coerceAtLeast(1), (pageH * level * cap).toInt().coerceAtLeast(1)))
        // fall back to any other cached resolution of the same page (prefer higher)
        for (l in LEVELS.reversed()) if (l != level) cache.get(key(file, page, l))?.let { return it }
        return null
    }

    /** Synchronous render for export/thumbnails. */
    fun renderNow(file: String, page: Int, w: Int, h: Int): Bitmap? =
        worker.submit<Bitmap?> { render(file, page, w, h) }.get()

    fun pageSizes(uri: Uri): List<Pair<Double, Double>> =
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { fd ->
            PdfRenderer(fd).use { pdf -> (0 until pdf.pageCount).map { i -> pdf.openPage(i).use { it.width * PX_PER_PT to it.height * PX_PER_PT } } }
        }

    fun close() {
        worker.execute { renderers.values.forEach { (fd, r) -> runCatching { r.close(); fd.close() } }; renderers.clear() }
        worker.shutdown()
    }

    private fun request(r: Request) {
        synchronized(queue) {
            if (!queued.add(r.key)) return
            queue.addLast(r)
            // Fast scrolling: only the most recent requests matter.
            while (queue.size > 6) queued.remove(queue.removeFirst().key)
            if (running) return
            running = true
        }
        worker.execute { drain() }
    }

    private fun drain() {
        while (true) {
            val r = synchronized(queue) {
                val next = queue.removeLastOrNull() // newest first: what's on screen now
                if (next == null) { running = false; return }
                next
            }
            try { render(r.file, r.page, r.w, r.h)?.let { cache.put(r.key, it) } } catch (_: Throwable) {}
            synchronized(queue) { queued.remove(r.key) }
            onReady?.invoke()
        }
    }

    private fun render(file: String, page: Int, w: Int, h: Int): Bitmap? {
        val renderer = renderers[file]?.second ?: run {
            val uri = resolve(file) ?: return null
            val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val r = PdfRenderer(fd)
            renderers[file] = fd to r
            r
        }
        if (page !in 0 until renderer.pageCount) return null
        renderer.openPage(page).use { p ->
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, Matrix().apply { setScale(w.toFloat() / p.width, h.toFloat() / p.height) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        }
    }

    companion object {
        const val PX_PER_PT = 96.0 / 72.0
        /** Pixels per scene unit we render at; 3 = sharp up to 300% zoom. */
        private val LEVELS = floatArrayOf(0.25f, 0.5f, 1f, 1.5f, 2f, 3f)
        private fun key(file: String, page: Int, level: Float) = "$file#$page@$level"
    }
}
