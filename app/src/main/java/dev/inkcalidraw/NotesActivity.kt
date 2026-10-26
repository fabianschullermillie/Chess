package dev.inkcalidraw

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.Executors

/** Home screen: a grid of notes with thumbnails. */
class NotesActivity : ComponentActivity() {
    private lateinit var ui: Ui
    private lateinit var store: NoteStore
    private lateinit var grid: GridView
    private lateinit var folderLabel: TextView
    private lateinit var empty: TextView
    private var notes: List<NoteStore.Note> = emptyList()

    private val io = Executors.newSingleThreadExecutor()
    private val thumbs = LruCache<String, Bitmap>(64)

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching { store.setFolder(uri); reload() }.onFailure { toast("Couldn't use that folder: ${it.message}") }
    }
    private val importDocs = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> importAll(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui.of(this)
        store = NoteStore(this)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dpi(20), ui.dpi(12), ui.dpi(12), ui.dpi(12))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = "Notes"; textSize = 26f; setTextColor(ui.text) })
                folderLabel = TextView(context).apply { textSize = 13f; setTextColor(ui.subtext) }
                addView(folderLabel)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(pill(Icon.MOON, "Theme") { chooseTheme() })
            addView(pill(Icon.FOLDER, "Folder") { chooseFolder() })
            addView(pill(Icon.IMPORT, "Import") { importDocs.launch(arrayOf("application/pdf", "application/json", "application/octet-stream", "*/*")) })
            addView(pill(Icon.PLUS, "New", primary = true) { newNoteMenu(it) })
        }
        empty = TextView(this).apply {
            text = "No notes yet.\nTap “New” for a whiteboard or A4 pages, or import a PDF to annotate."
            gravity = Gravity.CENTER; textSize = 16f; setTextColor(ui.subtext); visibility = View.GONE
        }
        grid = GridView(this).apply {
            columnWidth = ui.dpi(220); numColumns = GridView.AUTO_FIT
            horizontalSpacing = ui.dpi(16); verticalSpacing = ui.dpi(16)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(ui.dpi(16), ui.dpi(4), ui.dpi(16), ui.dpi(16)); clipToPadding = false
            adapter = Adapter()
            setOnItemClickListener { _, _, pos, _ -> openNote(notes[pos]) }
            setOnItemLongClickListener { _, v, pos, _ -> noteMenu(v, notes[pos]); true }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.bg)
            addView(header)
            addView(FrameLayout(context).apply { addView(grid); addView(empty, FrameLayout.LayoutParams(-1, -1)) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars()); v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        setContentView(root)
        window.statusBarColor = ui.bg
    }

    override fun onResume() { super.onResume(); reload() }

    private fun reload() {
        folderLabel.text = store.folderLabel()
        io.execute {
            val list = runCatching { store.list() }.getOrElse { emptyList() }
            runOnUiThread {
                notes = list
                empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                (grid.adapter as BaseAdapter).notifyDataSetChanged()
            }
        }
    }

    private fun openNote(n: NoteStore.Note) {
        startActivity(Intent(this, EditorActivity::class.java).setData(n.uri).putExtra(EditorActivity.EXTRA_NAME, n.name))
    }

    private fun newNoteMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Whiteboard (infinite)")
            menu.add("A4 pages · portrait")
            menu.add("A4 pages · landscape")
            menu.add("Annotate a PDF…")
            setOnMenuItemClickListener {
                when (it.title.toString()) {
                    "Whiteboard (infinite)" -> openNote(store.create())
                    "A4 pages · portrait" -> openNote(store.create(null, Pages.newPagedScene(PageOrientation.PORTRAIT, PageDirection.VERTICAL).toJson()))
                    "A4 pages · landscape" -> openNote(store.create(null, Pages.newPagedScene(PageOrientation.LANDSCAPE, PageDirection.VERTICAL).toJson()))
                    "Annotate a PDF…" -> importDocs.launch(arrayOf("application/pdf"))
                }
                true
            }
        }.show()
    }

    /** .excalidraw files are copied in; PDFs become new paged notes ready for annotation. */
    private fun importAll(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val progress = Toast.makeText(this, "Importing…", Toast.LENGTH_LONG).apply { show() }
        io.execute {
            var ok = 0
            var lastPdf: NoteStore.Note? = null
            for (u in uris) runCatching {
                val name = queryName(u)?.let { NoteStore.displayName(it).removeSuffix(".pdf").removeSuffix(".PDF") }
                if (contentResolver.getType(u) == "application/pdf" || name?.endsWith(".pdf", true) == true || queryName(u)?.endsWith(".pdf", true) == true) {
                    val scene = ExcalidrawScene.empty()
                    PdfSupport.import(this, u, scene, PageDirection.VERTICAL)
                    NoteMeta(paged = true).writeTo(scene)
                    lastPdf = store.create(name, scene.toJson())
                } else {
                    val text = contentResolver.openInputStream(u)!!.bufferedReader().use { it.readText() }
                    ExcalidrawScene.parse(text) // validate
                    store.create(name, text)
                }
                ok++
            }
            runOnUiThread {
                progress.cancel()
                toast("Imported $ok of ${uris.size}")
                reload()
                if (uris.size == 1) lastPdf?.let { openNote(it) }
            }
        }
    }

    private fun queryName(u: Uri): String? = runCatching {
        contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: u.lastPathSegment?.substringAfterLast('/')

    private fun chooseTheme() {
        val keys = listOf("system", "light", "dark")
        AlertDialog.Builder(this).setTitle("Appearance")
            .setSingleChoiceItems(arrayOf("Follow system", "Light", "Dark"), keys.indexOf(Ui.themeSetting(this))) { d, which ->
                Ui.setThemeSetting(this, keys[which]); d.dismiss(); recreate()
            }.show()
    }

    private fun chooseFolder() {
        val items = arrayOf("Choose a folder… (e.g. your Obsidian vault)", "Use storage on this tablet")
        AlertDialog.Builder(this).setTitle("Where should notes live?\nNow: ${store.folderLabel()}")
            .setItems(items) { _, which -> if (which == 0) pickFolder.launch(store.folderUri) else { store.setFolder(null); reload() } }
            .show()
    }

    private fun noteMenu(anchor: View, n: NoteStore.Note) {
        PopupMenu(this, anchor).apply {
            menu.add("Rename"); menu.add("Duplicate"); menu.add("Share"); menu.add("Delete")
            setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Rename" -> rename(n)
                    "Duplicate" -> { runCatching { store.duplicate(n) }; reload() }
                    "Share" -> share(n)
                    "Delete" -> AlertDialog.Builder(this@NotesActivity).setMessage("Delete “${n.name}”?")
                        .setPositiveButton("Delete") { _, _ -> store.delete(n); reload() }
                        .setNegativeButton("Cancel", null).show()
                }
                true
            }
        }.show()
    }

    private fun rename(n: NoteStore.Note) {
        val input = EditText(this).apply { setText(n.name); selectAll() }
        AlertDialog.Builder(this).setTitle("Rename note").setView(input)
            .setPositiveButton("Rename") { _, _ -> if (store.rename(n, input.text.toString()) == null) toast("Rename failed"); reload() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun share(n: NoteStore.Note) {
        val uri: Uri = if (n.uri.scheme == "file") {
            androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", java.io.File(n.uri.path!!))
        } else n.uri
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/json"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share ${n.name}"))
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = notes.size
        override fun getItem(position: Int) = notes[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val n = notes[position]
            val card = (convertView as? LinearLayout) ?: LinearLayout(this@NotesActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = ui.rounded(ui.surface, 12, ui.divider)
                elevation = ui.dp(2)
                setPadding(ui.dpi(8), ui.dpi(8), ui.dpi(8), ui.dpi(10))
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; tag = "img" }, LinearLayout.LayoutParams(-1, ui.dpi(150)))
                addView(TextView(context).apply { textSize = 15f; setTextColor(ui.text); maxLines = 1; tag = "name"; setPadding(ui.dpi(4), ui.dpi(8), ui.dpi(4), 0) })
                addView(TextView(context).apply { textSize = 12f; setTextColor(ui.subtext); tag = "date"; setPadding(ui.dpi(4), ui.dpi(2), ui.dpi(4), 0) })
            }
            card.findViewWithTag<TextView>("name").text = n.name
            card.findViewWithTag<TextView>("date").text = DateUtils.getRelativeTimeSpanString(n.lastModified)
            val img = card.findViewWithTag<ImageView>("img")
            val key = "${n.uri}@${n.lastModified}@${ui.dark}"
            img.setTag(R.id.thumb_key, key)
            val cached = thumbs.get(key)
            img.setImageBitmap(cached)
            if (cached == null) io.execute {
                val bmp = runCatching { thumbnail(store.read(n.uri)) }.getOrNull() ?: return@execute
                thumbs.put(key, bmp)
                runOnUiThread { if (img.getTag(R.id.thumb_key) == key) img.setImageBitmap(bmp) }
            }
            return card
        }
    }

    private fun thumbnail(json: String): Bitmap {
        val scene = ExcalidrawScene.parse(json)
        val meta = NoteMeta.readFrom(scene)
        val w = 440; val h = 300
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Paged notes: show the first page; whiteboards: everything.
        val first = if (meta.paged) Pages.pages(scene).firstOrNull() else null
        val b = first?.box ?: Geometry.contentBounds(scene.elements)?.inflate(20.0)
        val r = SceneRenderer(dark = ui.dark, syncImages = true)
        c.drawColor(if (meta.paged) ui.desk else r.color(SceneRenderer.parseColor(scene.backgroundColor, android.graphics.Color.WHITE)))
        if (b == null) return bmp
        val s = minOf(w / b.width, h / b.height, 1.5).toFloat()
        c.translate((w - b.width.toFloat() * s) / 2, (h - b.height.toFloat() * s) / 2)
        c.scale(s, s); c.translate(-b.minX.toFloat(), -b.minY.toFloat())
        val files = scene.files
        r.draw(c, SceneRenderer.Input(scene.elements, { id -> (files[id]?.get("dataURL") as? JsonPrimitive)?.content }, scene.backgroundColor, paged = meta.paged, deskColor = ui.desk))
        return bmp
    }

    private fun pill(icon: Icon, label: String, primary: Boolean = false, onClick: (View) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        val fg = if (primary) android.graphics.Color.WHITE else ui.text
        addView(ImageView(context).apply { setImageDrawable(IconDrawable(icon, fg)) }, LinearLayout.LayoutParams(ui.dpi(20), ui.dpi(20)))
        addView(TextView(context).apply { text = label; textSize = 15f; setTextColor(fg); setPadding(ui.dpi(6), 0, 0, 0) })
        background = ui.rounded(if (primary) ui.accent else ui.accentSoft, 20)
        setPadding(ui.dpi(14), ui.dpi(8), ui.dpi(16), ui.dpi(8))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dpi(8) }
        setOnClickListener { onClick(it) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
