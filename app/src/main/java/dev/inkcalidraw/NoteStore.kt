package dev.inkcalidraw

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Notes are plain .excalidraw files in one folder: either the app's private storage (default) or a
 * folder the user picked (e.g. inside an Obsidian vault or a synced drive), accessed via the
 * Storage Access Framework. DocumentFile gives both the same API.
 */
class NoteStore(private val context: Context) {
    data class Note(val uri: Uri, val name: String, val lastModified: Long)

    private val prefs = context.getSharedPreferences("notes", Context.MODE_PRIVATE)

    val folderUri: Uri? get() = prefs.getString("tree", null)?.let(Uri::parse)

    val isExternal: Boolean get() = folderUri != null

    fun root(): DocumentFile {
        folderUri?.let { uri -> DocumentFile.fromTreeUri(context, uri)?.takeIf { it.canWrite() }?.let { return it } }
        return DocumentFile.fromFile(File(context.filesDir, "notes").apply { mkdirs() })
    }

    fun folderLabel(): String = if (isExternal) (root().name ?: "Chosen folder") else "On this tablet"

    fun setFolder(uri: Uri?) {
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        prefs.edit().apply { if (uri == null) remove("tree") else putString("tree", uri.toString()) }.apply()
    }

    fun list(): List<Note> = root().listFiles()
        .filter { it.isFile && (it.name ?: "").endsWith(EXT, ignoreCase = true) }
        .map { Note(it.uri, displayName(it.name ?: ""), it.lastModified()) }
        .sortedByDescending { it.lastModified }

    fun create(baseName: String? = null, content: String = ExcalidrawScene.empty().toJson()): Note {
        val name = uniqueName(baseName ?: ("Note " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date())))
        val file = root().createFile(MIME, name + EXT)
            ?: error("Couldn't create a file in ${folderLabel()}")
        // Some providers append their own extension for unknown mime types; fix the name if so.
        if (file.name != name + EXT) file.renameTo(name + EXT)
        write(file.uri, content)
        return Note(file.uri, name, System.currentTimeMillis())
    }

    fun read(uri: Uri): String = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }

    fun write(uri: Uri, text: String) {
        context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(text) }
    }

    /** Returns the (possibly changed) uri after renaming. */
    fun rename(note: Note, newName: String): Uri? {
        val f = doc(note.uri) ?: return null
        val name = uniqueName(newName.trim().ifEmpty { note.name }, except = note.name)
        return if (f.renameTo(name + EXT)) f.uri else null
    }

    fun delete(note: Note): Boolean = doc(note.uri)?.delete() ?: false

    fun duplicate(note: Note): Note = create("${note.name} copy", read(note.uri))

    private fun doc(uri: Uri): DocumentFile? =
        if (uri.scheme == "file") DocumentFile.fromFile(File(uri.path!!)) else DocumentFile.fromSingleUri(context, uri)

    private fun uniqueName(base: String, except: String? = null): String {
        val existing = list().map { it.name.lowercase() }.toSet() - (except?.lowercase() ?: "")
        var candidate = base.replace(Regex("[\\\\/:*?\"<>|]"), "-")
        var i = 2
        val stem = candidate
        while (candidate.lowercase() in existing) candidate = "$stem ($i)".also { i++ }
        return candidate
    }

    companion object {
        const val EXT = ".excalidraw"
        const val MIME = "application/octet-stream"
        fun displayName(fileName: String) = fileName.removeSuffix(EXT).removeSuffix(".json")
    }
}
