package dev.inkcalidraw

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.Executors

/** Home screen: a grid of notes with thumbnails. */
class NotesActivity : ComponentActivity() {
    private lateinit var store: NoteStore
    private lateinit var grid: GridView
    private lateinit var folderLabel: TextView
    private lateinit var empty: TextView
    private var notes: List<NoteStore.Note> = emptyList()

    private val io = Executors.newSingleThreadExecutor()
    private val thumbs = LruCache<String, Bitmap>(64)

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching { store.setFolder(uri); reload() }
            .onFailure { toast("Couldn't use that folder: ${it.message}") }
    }
    private val importDoc = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        var ok = 0
        for (u in uris) runCatching {
            val text = contentResolver.openInputStream(u)!!.bufferedReader().use { it.readText() }
            ExcalidrawScene.parse(text) // validate
            val name = u.lastPathSegment?.substringAfterLast('/')?.let { NoteStore.displayName(it) }
            store.create(name, text); ok++
        }
        if (uris.isNotEmpty()) toast("Imported $ok of ${uris.size}")
        reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NoteStore(this)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(12), dp(12))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = "Notes"; textSize = 26f; setTextColor(Color.BLACK) })
                folderLabel = TextView(context).apply { textSize = 13f; setTextColor(Color.GRAY) }
                addView(folderLabel)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(pill("Folder") { chooseFolder() })
            addView(pill("Import") { importDoc.launch(arrayOf("*/*")) })
            addView(pill("+ New note", primary = true) { openNote(store.create()) })
        }
        empty = TextView(this).apply {
            text = "No notes yet.\nTap “+ New note” to start drawing."
            gravity = Gravity.CENTER; textSize = 16f; setTextColor(Color.GRAY); visibility = View.GONE
        }
        grid = GridView(this).apply {
            columnWidth = dp(220); numColumns = GridView.AUTO_FIT
            horizontalSpacing = dp(16); verticalSpacing = dp(16)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(dp(16), dp(4), dp(16), dp(16)); clipToPadding = false
            adapter = Adapter()
            setOnItemClickListener { _, _, pos, _ -> openNote(notes[pos]) }
            setOnItemLongClickListener { _, v, pos, _ -> noteMenu(v, notes[pos]); true }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#f4f4f6"))
            addView(header)
            addView(android.widget.FrameLayout(context).apply {
                addView(grid); addView(empty, android.widget.FrameLayout.LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars()); v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        setContentView(root)
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

    private fun chooseFolder() {
        val items = arrayOf("Choose a folder… (e.g. your Obsidian vault)", "Use storage on this tablet")
        AlertDialog.Builder(this).setTitle("Where should notes live?\nNow: ${store.folderLabel()}")
            .setItems(items) { _, which ->
                if (which == 0) pickFolder.launch(store.folderUri) else { store.setFolder(null); reload() }
            }.show()
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

    // ------------------------------------------------------------------ grid

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = notes.size
        override fun getItem(position: Int) = notes[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val n = notes[position]
            val card = (convertView as? LinearLayout) ?: LinearLayout(this@NotesActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12).toFloat() }
                elevation = dp(2).toFloat()
                setPadding(dp(8), dp(8), dp(8), dp(10))
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; tag = "img" }, LinearLayout.LayoutParams(-1, dp(150)))
                addView(TextView(context).apply { textSize = 15f; setTextColor(Color.BLACK); maxLines = 1; tag = "name"; setPadding(dp(4), dp(8), dp(4), 0) })
                addView(TextView(context).apply { textSize = 12f; setTextColor(Color.GRAY); tag = "date"; setPadding(dp(4), dp(2), dp(4), 0) })
            }
            card.findViewWithTag<TextView>("name").text = n.name
            card.findViewWithTag<TextView>("date").text = DateUtils.getRelativeTimeSpanString(n.lastModified)
            val img = card.findViewWithTag<ImageView>("img")
            val key = "${n.uri}@${n.lastModified}"
            img.tag = "img"; img.setTag(R.id.thumb_key, key)
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
        val w = 440; val h = 300
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(SceneRenderer.parseColor(scene.backgroundColor, Color.WHITE))
        val b = SceneRenderer.contentBounds(scene.elements) ?: return bmp
        b.inset(-20f, -20f)
        val s = minOf(w / b.width(), h / b.height(), 1.5f)
        c.translate((w - b.width() * s) / 2, (h - b.height() * s) / 2)
        c.scale(s, s); c.translate(-b.left, -b.top)
        SceneRenderer().draw(c, scene)
        return bmp
    }

    // ------------------------------------------------------------------ ui helpers

    private fun pill(label: String, primary: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f
        setTextColor(if (primary) Color.WHITE else Color.parseColor("#1e1e1e"))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(if (primary) Color.parseColor("#6965db") else Color.parseColor("#e9e9f0"))
        }
        setPadding(dp(16), dp(9), dp(16), dp(9))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

}
