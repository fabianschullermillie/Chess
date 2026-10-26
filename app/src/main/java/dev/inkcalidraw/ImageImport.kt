package dev.inkcalidraw

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Turns an image Uri into what an Excalidraw file stores: a data URL keyed by a content hash. */
object ImageImport {
    class Imported(val fileId: String, val mimeType: String, val dataUrl: String, val width: Int, val height: Int)

    private const val MAX_SIDE = 2400
    private const val MAX_BYTES = 2_000_000
    private val KEEP_AS_IS = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

    fun load(cr: ContentResolver, uri: Uri): Imported {
        val original = cr.openInputStream(uri)!!.use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        require(bounds.outWidth > 0) { "not an image" }
        val mime = bounds.outMimeType ?: cr.getType(uri) ?: "image/png"

        val (bytes, outMime, w, h) = if (mime in KEEP_AS_IS && original.size <= MAX_BYTES && maxOf(bounds.outWidth, bounds.outHeight) <= MAX_SIDE) {
            Quad(original, mime, bounds.outWidth, bounds.outHeight)
        } else {
            // Too big for a note file: downscale, keep PNG for screenshots/diagrams (crisp text), JPEG for photos.
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(original, 0, original.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: error("couldn't decode image")
            val k = minOf(1f, MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
            val bmp = if (k < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * k).toInt(), (decoded.height * k).toInt(), true) else decoded
            val png = mime == "image/png" || bmp.hasAlpha()
            val out = ByteArrayOutputStream()
            bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, out)
            Quad(out.toByteArray(), if (png) "image/png" else "image/jpeg", bmp.width, bmp.height)
        }
        // Excalidraw uses the SHA-1 of the file contents as its fileId; identical pastes share one file.
        val id = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        return Imported(id, outMime, "data:$outMime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP), w, h)
    }

    private data class Quad(val bytes: ByteArray, val mime: String, val w: Int, val h: Int)
}
