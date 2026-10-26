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
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.Executors

/** Home screen: folders and notes (with thumbnails) of the current folder. */
class NotesActivity : ComponentActivity() {
    private lateinit var ui: Ui
    private lateinit var store: NoteStore
    private lateinit var grid: GridView
    private lateinit var title: TextView
    private lateinit var path: TextView
    private lateinit var upButton: View
    private lateinit var empty: TextView
    private val stack = ArrayList<DocumentFile>()
    private val current get() = stack.last()
    private var items: List<Any> = emptyList() // NoteStore.Folder | NoteStore.Note

    private val io = Executors.newSingleThreadExecutor()
    private val thumbIo = Executors.newSingleThreadExecutor()
    private val thumbs = LruCache<String, Bitmap>(64)
    private var pdfs: PdfPages? = null

    private val pickRoot = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching { store.setTree(uri); resetToRoot() }.onFailure { toast("Couldn't use that folder: ${it.message}") }
    }
    private val importDocs = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> importAll(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui.of(this)
        store = NoteStore(this)
        stack += store.root()
        savedInstanceState?.getStringArrayList("stack")?.drop(1)?.forEach { stack += store.folder(Uri.parse(it)) }

        upButton = ImageView(this).apply {
            setImageDrawable(IconDrawable(Icon.BACK, ui.text)); contentDescription = "Up"
            setPadding(ui.dpi(8), ui.dpi(8), ui.dpi(8), ui.dpi(8)); setOnClickListener { goUp() }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dpi(12), ui.dpi(12), ui.dpi(12), ui.dpi(12))
            addView(upButton, LinearLayout.LayoutParams(ui.dpi(44), ui.dpi(44)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(ui.dpi(8), 0, 0, 0)
                title = TextView(context).apply { textSize = 26f; setTextColor(ui.text); maxLines = 1 }
                path = TextView(context).apply { textSize = 13f; setTextColor(ui.subtext); maxLines = 1 }
                addView(title); addView(path)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val buttons = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(pill(Icon.MOON, "Theme") { chooseTheme() })
                addView(pill(Icon.FOLDER, "Storage") { chooseRoot() })
                addView(pill(Icon.FOLDER_PLUS, "Folder") { newFolder() })
                addView(pill(Icon.IMPORT, "Import") { importDocs.launch(arrayOf("application/pdf", "application/json", "application/octet-stream", "*/*")) })
                addView(pill(Icon.PLUS, "New", primary = true) { newNoteMenu(it) })
            }
            addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(buttons) })
        }
        empty = TextView(this).apply {
            text = "Nothing here yet.\nTap “New” for a whiteboard or A4 pages, or import a PDF to annotate."
            gravity = Gravity.CENTER; textSize = 16f; setTextColor(ui.subtext); visibility = View.GONE
        }
        grid = GridView(this).apply {
            columnWidth = ui.dpi(220); numColumns = GridView.AUTO_FIT
            horizontalSpacing = ui.dpi(16); verticalSpacing = ui.dpi(16)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(ui.dpi(16), ui.dpi(4), ui.dpi(16), ui.dpi(16)); clipToPadding = false
            adapter = Adapter()
            setOnItemClickListener { _, _, pos, _ ->
                when (val it = items[pos]) {
                    is NoteStore.Folder -> { stack += store.folder(it.uri); reload() }
                    is NoteStore.Note -> openNote(it)
                }
            }
            setOnItemLongClickListener { _, v, pos, _ ->
                when (val it = items[pos]) { is NoteStore.Folder -> folderMenu(v, it); is NoteStore.Note -> noteMenu(v, it) }
                true
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.bg)
            addView(header)
            addView(FrameLayout(context).apply { addView(grid); addView(empty, FrameLayout.LayoutParams(-1, -1)) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()); v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        setContentView(root)
        window.statusBarColor = ui.bg
        window.navigationBarColor = ui.bg
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (stack.size > 1) goUp() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() } }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("stack", ArrayList(stack.map { it.uri.toString() }))
    }

    override fun onResume() { super.onResume(); reload() }

    override fun onDestroy() { pdfs?.close(); super.onDestroy() }

    private fun goUp() { if (stack.size > 1) { stack.removeAt(stack.size - 1); reload() } }

    private fun resetToRoot() { stack.clear(); stack += store.root(); reload() }

    private fun reload() {
        title.text = if (stack.size == 1) "Notes" else (current.name ?: "Folder")
        path.text = (listOf(store.rootLabel()) + stack.drop(1).map { it.name ?: "?" }).joinToString("  ›  ")
        upButton.visibility = if (stack.size > 1) View.VISIBLE else View.GONE
        val folder = current
        pdfs?.close()
        pdfs = PdfPages(this) { name -> store.findFile(folder, name) }
        io.execute {
            val l = runCatching { store.list(folder) }.getOrElse { NoteStore.Listing(emptyList(), emptyList()) }
            runOnUiThread {
                if (folder != current) return@runOnUiThread
                items = l.folders + l.notes
                empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                (grid.adapter as BaseAdapter).notifyDataSetChanged()
            }
        }
    }

    private fun openNote(n: NoteStore.Note) {
        startActivity(Intent(this, EditorActivity::class.java).setData(n.uri)
            .putExtra(EditorActivity.EXTRA_NAME, n.name).putExtra(EditorActivity.EXTRA_FOLDER, current.uri.toString()))
    }

    private fun newNoteMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Whiteboard (infinite)")
            menu.add("A4 pages · portrait")
            menu.add("A4 pages · landscape")
            menu.add("Annotate a PDF…")
            setOnMenuItemClickListener {
                val f = current
                when (it.title.toString()) {
                    "Whiteboard (infinite)" -> openNote(store.create(f))
                    "A4 pages · portrait" -> openNote(store.create(f, null, Pages.newPagedScene(PageOrientation.PORTRAIT, PageDirection.VERTICAL).toJson()))
                    "A4 pages · landscape" -> openNote(store.create(f, null, Pages.newPagedScene(PageOrientation.LANDSCAPE, PageDirection.VERTICAL).toJson()))
                    "Annotate a PDF…" -> importDocs.launch(arrayOf("application/pdf"))
                }
                true
            }
        }.show()
    }

    private fun newFolder() {
        val input = EditText(this).apply { hint = "Folder name" }
        AlertDialog.Builder(this).setTitle("New folder").setView(input)
            .setPositiveButton("Create") { _, _ ->
                val f = current
                io.execute { val r = runCatching { store.createFolder(f, input.text.toString()) }; runOnUiThread { r.onFailure { toast("Couldn't create folder: ${it.message}") }; reload() } }
            }.setNegativeButton("Cancel", null).show()
    }

    /** .excalidraw files are copied in; PDFs become new paged notes (the PDF is stored next to the note). */
    private fun importAll(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val folder = current
        val progress = Toast.makeText(this, "Importing…", Toast.LENGTH_LONG).apply { show() }
        io.execute {
            var ok = 0
            var lastPdf: NoteStore.Note? = null
            for (u in uris) runCatching {
                val file = queryName(u) ?: "Imported"
                val isPdf = contentResolver.getType(u) == "application/pdf" || file.endsWith(".pdf", true)
                val name = NoteStore.displayName(file).removeSuffix(".pdf").removeSuffix(".PDF")
                if (isPdf) {
                    val scene = ExcalidrawScene.empty()
                    PdfSupport.import(this, store, folder, u, file, scene, PageDirection.VERTICAL)
                    NoteMeta(paged = true).writeTo(scene)
                    lastPdf = store.create(folder, name, scene.toJson())
                } else {
                    val text = contentResolver.openInputStream(u)!!.bufferedReader().use { it.readText() }
                    ExcalidrawScene.parse(text) // validate
                    store.create(folder, name, text)
                }
                ok++
            }.onFailure { e -> runOnUiThread { toast("Import failed: ${e.message}") } }
            runOnUiThread {
                progress.cancel()
                if (ok > 0) toast("Imported $ok of ${uris.size}")
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

    private fun chooseRoot() {
        val items = arrayOf("Choose a folder… (e.g. your Obsidian vault)", "Use storage on this tablet")
        AlertDialog.Builder(this).setTitle("Where should notes live?\nNow: ${store.rootLabel()}")
            .setItems(items) { _, which -> if (which == 0) pickRoot.launch(store.treeUri) else { store.setTree(null); resetToRoot() } }
            .show()
    }

    private fun folderMenu(anchor: View, f: NoteStore.Folder) {
        PopupMenu(this, anchor).apply {
            menu.add("Rename"); menu.add("Delete")
            setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Rename" -> {
                        val input = EditText(this@NotesActivity).apply { setText(f.name); selectAll() }
                        AlertDialog.Builder(this@NotesActivity).setTitle("Rename folder").setView(input)
                            .setPositiveButton("Rename") { _, _ -> runCatching { store.folder(f.uri).renameTo(input.text.toString().trim()) }; reload() }
                            .setNegativeButton("Cancel", null).show()
                    }
                    "Delete" -> AlertDialog.Builder(this@NotesActivity).setMessage("Delete folder “${f.name}” and everything in it?")
                        .setPositiveButton("Delete") { _, _ -> store.delete(f.uri).also { if (!it) runCatching { store.folder(f.uri).delete() } }; reload() }
                        .setNegativeButton("Cancel", null).show()
                }
                true
            }
        }.show()
    }

    private fun noteMenu(anchor: View, n: NoteStore.Note) {
        PopupMenu(this, anchor).apply {
            menu.add("Rename"); menu.add("Duplicate"); menu.add("Move to…"); menu.add("Share"); menu.add("Delete")
            setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Rename" -> rename(n)
                    "Duplicate" -> { val f = current; io.execute { runCatching { store.duplicate(f, n) }; runOnUiThread { reload() } } }
                    "Move to…" -> moveNote(n)
                    "Share" -> share(n)
                    "Delete" -> AlertDialog.Builder(this@NotesActivity).setMessage("Delete “${n.name}”?")
                        .setPositiveButton("Delete") { _, _ -> store.delete(n.uri); reload() }
                        .setNegativeButton("Cancel", null).show()
                }
                true
            }
        }.show()
    }

    private fun moveNote(n: NoteStore.Note) {
        val from = current
        io.execute {
            val folders = runCatching { store.allFolders() }.getOrDefault(emptyList()).filter { it.second.uri != from.uri }
            runOnUiThread {
                if (folders.isEmpty()) { toast("Create a folder first"); return@runOnUiThread }
                AlertDialog.Builder(this).setTitle("Move “${n.name}” to")
                    .setItems(folders.map { if (it.first == "/") store.rootLabel() else it.first.removePrefix("/") }.toTypedArray()) { _, which ->
                        io.execute {
                            val r = runCatching { store.move(n, from, folders[which].second) }
                            runOnUiThread { r.onFailure { toast("Move failed: ${it.message}") }; reload() }
                        }
                    }.show()
            }
        }
    }

    private fun rename(n: NoteStore.Note) {
        val input = EditText(this).apply { setText(n.name); selectAll() }
        AlertDialog.Builder(this).setTitle("Rename note").setView(input)
            .setPositiveButton("Rename") { _, _ -> if (store.rename(n.uri, current, input.text.toString()) == null) toast("Rename failed"); reload() }
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
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (items[position] is NoteStore.Folder) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = items[position]
            if (item is NoteStore.Folder) {
                val card = (convertView as? LinearLayout) ?: LinearLayout(this@NotesActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = ui.rounded(ui.surface, 12, ui.divider); elevation = ui.dp(2)
                    setPadding(ui.dpi(14), ui.dpi(14), ui.dpi(14), ui.dpi(14))
                    addView(ImageView(context).apply { setImageDrawable(IconDrawable(Icon.FOLDER, ui.accent)) }, LinearLayout.LayoutParams(ui.dpi(28), ui.dpi(28)))
                    addView(TextView(context).apply { textSize = 15f; setTextColor(ui.text); maxLines = 1; tag = "name"; setPadding(ui.dpi(10), 0, 0, 0) })
                }
                card.findViewWithTag<TextView>("name").text = item.name
                return card
            }
            val n = item as NoteStore.Note
            val card = (convertView as? LinearLayout) ?: LinearLayout(this@NotesActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = ui.rounded(ui.surface, 12, ui.divider); elevation = ui.dp(2)
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
            val p = pdfs
            if (cached == null) thumbIo.execute {
                val bmp = runCatching { thumbnail(store.read(n.uri), p) }.getOrNull() ?: return@execute
                thumbs.put(key, bmp)
                runOnUiThread { if (img.getTag(R.id.thumb_key) == key) img.setImageBitmap(bmp) }
            }
            return card
        }
    }

    private fun thumbnail(json: String, pdfs: PdfPages?): Bitmap {
        val scene = ExcalidrawScene.parse(json)
        val meta = NoteMeta.readFrom(scene)
        val w = 440; val h = 300
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val first = if (meta.paged) Pages.pages(scene).firstOrNull() else null
        val b = first?.box ?: Geometry.contentBounds(scene.elements)?.inflate(20.0)
        val r = SceneRenderer(dark = ui.dark, syncImages = true)
        c.drawColor(if (meta.paged) ui.desk else r.color(SceneRenderer.parseColor(scene.backgroundColor, android.graphics.Color.WHITE)))
        if (b == null) return bmp
        val s = minOf(w / b.width, h / b.height, 1.5).toFloat()
        c.translate((w - b.width.toFloat() * s) / 2, (h - b.height.toFloat() * s) / 2)
        c.scale(s, s); c.translate(-b.minX.toFloat(), -b.minY.toFloat())
        val files = scene.files
        r.draw(c, SceneRenderer.Input(
            scene.elements, { id -> (files[id]?.get("dataURL") as? JsonPrimitive)?.content }, scene.backgroundColor,
            visible = b, paged = meta.paged, deskColor = ui.desk, scale = s,
            pageImage = { f, _ -> if (pdfs == null) null else runCatching { pdfs.renderNow(f.pdfFile!!, f.pdfPage ?: 0, (f.width * s).toInt().coerceAtLeast(1), (f.height * s).toInt().coerceAtLeast(1)) }.getOrNull() },
        ))
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
