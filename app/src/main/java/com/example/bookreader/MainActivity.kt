package com.example.bookreader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // Home tab
    private lateinit var homeView: LinearLayout
    private lateinit var continueCard: MaterialCardView
    private lateinit var continueTitle: TextView
    private lateinit var continueSub: TextView
    private lateinit var continueProgress: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var list: RecyclerView

    // Library tab
    private lateinit var libraryView: LinearLayout
    private lateinit var grid: RecyclerView
    private lateinit var emptyLibrary: TextView
    private lateinit var chipScroll: HorizontalScrollView
    private lateinit var chipGroup: ChipGroup
    private lateinit var filterBtn: Button
    private var cols = 3
    private var itemW = 300

    private val sortNames = arrayOf("Recently read", "Title (A–Z)", "Date added", "Progress")
    private val statusNames = arrayOf("All books", "Reading", "Not started", "Finished")
    private var sortMode = 0
    private var statusFilter = 0
    private var shelfFilter: String? = null

    private var books: List<Book> = emptyList()   // everything, most recently read first (Home)
    private var shown: List<Book> = emptyList()   // what the Library grid shows
    private var coverTargetId: String? = null

    private val ui by lazy { getSharedPreferences("ui", MODE_PRIVATE) }
    private val coverExec = Executors.newFixedThreadPool(2)
    private val coverCache = LruCache<String, Bitmap>(24)

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importBooks(uris)
    }
    private val coverPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) setCover(uri)
    }
    private val backupPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) doBackup(uri)
    }
    private val restorePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) doRestore(uri)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sortMode = ui.getInt("sort", 0)
        statusFilter = ui.getInt("status", 0)

        // ---------------- Home tab (unchanged layout) ----------------
        homeView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(20))
        }
        val root = homeView

        root.addView(TextView(this).apply {
            text = "Book Reader"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
        })

        continueCard = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = dp(2).toFloat()
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(24) }
        }
        val cardInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        cardInner.addView(TextView(this).apply {
            text = "CONTINUE READING"
            textSize = 12f
            letterSpacing = 0.1f
        })
        continueTitle = TextView(this).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, 0)
            maxLines = 2
        }
        continueSub = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(2), 0, dp(10))
        }
        continueProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        cardInner.addView(continueTitle)
        cardInner.addView(continueSub)
        cardInner.addView(continueProgress)
        continueCard.addView(cardInner)
        root.addView(continueCard)

        root.addView(MaterialButton(this).apply {
            text = "Add new book"
            setOnClickListener { picker.launch(arrayOf("*/*")) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        })

        root.addView(TextView(this).apply {
            text = "Library"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(24), 0, dp(8))
        })

        emptyText = TextView(this).apply {
            text = "No books yet. Tap \"Add new book\" to import PDF, EPUB or TXT files."
            visibility = View.GONE
        }
        root.addView(emptyText)

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
        }
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        list.adapter = BookAdapter()

        // ---------------- Library tab (cover grid) ----------------
        cols = (resources.configuration.screenWidthDp / 120).coerceIn(2, 8)
        itemW = (resources.displayMetrics.widthPixels - dp(28)) / cols - dp(12)

        libraryView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(48), dp(14), 0)
            visibility = View.GONE
        }

        fun headerButton(label: String) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            minWidth = 0
            minimumWidth = 0
        }
        val sortBtn = headerButton("Sort").apply { setOnClickListener { chooseSort() } }
        filterBtn = headerButton("Filter").apply { setOnClickListener { chooseStatus() } }
        val menuBtn = headerButton("⋮").apply {
            textSize = 20f
            setOnClickListener { showMenu(this) }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, 0, dp(4))
        }
        header.addView(TextView(this).apply {
            text = "Library"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(sortBtn)
        header.addView(filterBtn)
        header.addView(menuBtn)
        libraryView.addView(header)

        chipGroup = ChipGroup(this).apply {
            isSingleLine = true
            isSingleSelection = true
            isSelectionRequired = true
            setPadding(dp(6), 0, dp(6), 0)
        }
        chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(chipGroup)
        }
        libraryView.addView(chipScroll)

        emptyLibrary = TextView(this).apply {
            setPadding(dp(6), dp(12), 0, 0)
            visibility = View.GONE
        }
        libraryView.addView(emptyLibrary)

        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, cols)
        }
        libraryView.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        grid.adapter = GridAdapter()

        // ---------------- Bottom switcher ----------------
        val content = FrameLayout(this)
        content.addView(homeView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        content.addView(libraryView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val nav = BottomNavigationView(this)
        nav.inflateMenu(R.menu.bottom_nav)
        nav.setOnItemSelectedListener { item ->
            val lib = item.itemId == R.id.nav_library
            homeView.visibility = if (lib) View.GONE else View.VISIBLE
            libraryView.visibility = if (lib) View.VISIBLE else View.GONE
            true
        }

        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        outer.addView(nav, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        setContentView(outer)

        if (savedInstanceState?.getBoolean("library") == true) nav.selectedItemId = R.id.nav_library
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("library", libraryView.visibility == View.VISIBLE)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        coverExec.shutdownNow()
    }

    // ---------------- Data -> screens ----------------

    private fun refresh() {
        books = Library.load(this).sortedByDescending { it.lastOpened }
        val last = Library.lastRead(this)
        if (last != null) {
            continueCard.visibility = View.VISIBLE
            continueTitle.text = last.title
            continueSub.text = last.progressText()
            continueProgress.progress = last.progressPercent()
            continueCard.setOnClickListener { open(last) }
        } else {
            continueCard.visibility = View.GONE
        }
        emptyText.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
        list.adapter?.notifyDataSetChanged()
        rebuildChips()
        applyLibrary()
    }

    private fun rebuildChips() {
        val shelves = books.map { it.shelf }.filter { it.isNotBlank() }
            .distinct().sortedBy { it.lowercase() }
        val current = shelfFilter
        if (current != null && current !in shelves) shelfFilter = null
        chipGroup.removeAllViews()
        if (shelves.isEmpty()) {
            chipScroll.visibility = View.GONE
            return
        }
        chipScroll.visibility = View.VISIBLE
        fun addChip(label: String, value: String?) {
            val chip = Chip(this)
            chip.text = label
            chip.isCheckable = true
            chip.isChecked = (shelfFilter == value)
            chip.setOnClickListener {
                shelfFilter = value
                applyLibrary()
            }
            chipGroup.addView(chip)
        }
        addChip("All books", null)
        shelves.forEach { addChip(it, it) }
    }

    private fun applyLibrary() {
        val filtered = books
            .filter { shelfFilter == null || it.shelf == shelfFilter }
            .filter {
                when (statusFilter) {
                    1 -> it.lastOpened > 0 && !it.isFinished()
                    2 -> it.lastOpened == 0L
                    3 -> it.isFinished()
                    else -> true
                }
            }
        shown = when (sortMode) {
            1 -> filtered.sortedBy { it.title.lowercase() }
            2 -> filtered.sortedByDescending { it.added }
            3 -> filtered.sortedByDescending { it.progressPercent() }
            else -> filtered.sortedWith(
                compareByDescending<Book> { it.lastOpened }.thenByDescending { it.added }
            )
        }
        filterBtn.text = if (statusFilter == 0) "Filter" else statusNames[statusFilter]
        if (shown.isEmpty()) {
            emptyLibrary.visibility = View.VISIBLE
            emptyLibrary.text = if (books.isEmpty()) {
                "Your library is empty. Add a book from the Home tab."
            } else {
                "No books match this filter."
            }
        } else {
            emptyLibrary.visibility = View.GONE
        }
        grid.adapter?.notifyDataSetChanged()
    }

    private fun open(book: Book) {
        val cls = when (book.type) {
            "pdf" -> ReaderActivity::class.java
            "epub" -> EpubReaderActivity::class.java
            else -> TextReaderActivity::class.java
        }
        startActivity(Intent(this, cls).putExtra("id", book.id))
    }

    // ---------------- Library controls ----------------

    private fun chooseSort() {
        AlertDialog.Builder(this)
            .setTitle("Sort by")
            .setSingleChoiceItems(sortNames, sortMode) { d, which ->
                sortMode = which
                ui.edit().putInt("sort", which).apply()
                d.dismiss()
                applyLibrary()
            }
            .show()
    }

    private fun chooseStatus() {
        AlertDialog.Builder(this)
            .setTitle("Show")
            .setSingleChoiceItems(statusNames, statusFilter) { d, which ->
                statusFilter = which
                ui.edit().putInt("status", which).apply()
                d.dismiss()
                applyLibrary()
            }
            .show()
    }

    private fun showMenu(anchor: View) {
        val themeName = ReaderTheme.names[ReaderTheme.get(this)]
        val twoPage = ui.getBoolean("twoPage", true)
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Back up library")
        popup.menu.add(0, 2, 1, "Restore from backup")
        popup.menu.add(0, 3, 2, "Reading theme: $themeName")
        popup.menu.add(0, 4, 3, "Two-page PDFs in landscape: " + if (twoPage) "On" else "Off")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> backupPicker.launch("BookReader-backup.zip")
                2 -> restorePicker.launch(arrayOf("*/*"))
                3 -> chooseTheme()
                4 -> ui.edit().putBoolean("twoPage", !twoPage).apply()
            }
            true
        }
        popup.show()
    }

    private fun chooseTheme() {
        AlertDialog.Builder(this)
            .setTitle("Reading theme")
            .setSingleChoiceItems(ReaderTheme.names, ReaderTheme.get(this)) { d, which ->
                ReaderTheme.set(this, which)
                d.dismiss()
            }
            .show()
    }

    // ---------------- Per-book actions ----------------

    private fun showBookMenu(b: Book) {
        val items = arrayOf("Move to shelf…", "Change cover…", "Reset cover", "Remove")
        AlertDialog.Builder(this)
            .setTitle(b.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showShelfDialog(b)
                    1 -> {
                        coverTargetId = b.id
                        coverPicker.launch(arrayOf("image/*"))
                    }
                    2 -> {
                        Covers.file(this, b).delete()
                        coverCache.remove(b.id)
                        grid.adapter?.notifyDataSetChanged()
                    }
                    3 -> confirmRemove(b)
                }
            }
            .show()
    }

    private fun showShelfDialog(b: Book) {
        val shelves = books.map { it.shelf }.filter { it.isNotBlank() }
            .distinct().sortedBy { it.lowercase() }
        val options = arrayOf("No shelf") + shelves.toTypedArray() + arrayOf("New shelf…")
        AlertDialog.Builder(this)
            .setTitle("Move to shelf")
            .setItems(options) { _, which ->
                when {
                    which == 0 -> {
                        Library.setShelf(this, b.id, "")
                        refresh()
                    }
                    which == options.size - 1 -> askNewShelf(b)
                    else -> {
                        Library.setShelf(this, b.id, shelves[which - 1])
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun askNewShelf(b: Book) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            hint = "e.g. Fantasy"
        }
        AlertDialog.Builder(this)
            .setTitle("New shelf")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    Library.setShelf(this, b.id, name)
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setCover(uri: Uri) {
        val id = coverTargetId ?: return
        val book = books.firstOrNull { it.id == id } ?: return
        Thread {
            val ok = Covers.setCustom(this, book, uri)
            runOnUiThread {
                coverCache.remove(id)
                if (ok) {
                    grid.adapter?.notifyDataSetChanged()
                } else {
                    Toast.makeText(this, "Could not use that image", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun confirmRemove(b: Book) {
        AlertDialog.Builder(this)
            .setTitle("Remove \"${b.title}\"?")
            .setMessage("This removes the book and your saved place from the app.")
            .setPositiveButton("Remove") { _, _ ->
                coverCache.remove(b.id)
                Library.delete(this, b)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- Import / backup ----------------

    private fun importBooks(uris: List<Uri>) {
        val n = uris.size
        Toast.makeText(
            this, if (n == 1) "Importing..." else "Importing $n files...", Toast.LENGTH_SHORT
        ).show()
        Thread {
            var added = 0
            var skipped = 0
            var last: Book? = null
            for (uri in uris) {
                val book = try {
                    Library.import(this, uri)
                } catch (e: Exception) {
                    null
                }
                if (book != null) {
                    added++
                    last = book
                } else {
                    skipped++
                }
            }
            runOnUiThread {
                refresh()
                val only = last
                when {
                    added == 0 -> Toast.makeText(
                        this, "Unsupported or unreadable file (PDF, EPUB and TXT only)", Toast.LENGTH_LONG
                    ).show()
                    n == 1 && only != null -> open(only)
                    else -> {
                        val msg = "Added $added book" + (if (added == 1) "" else "s") +
                            (if (skipped > 0) " ($skipped skipped: unsupported or unreadable)" else "")
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun doBackup(uri: Uri) {
        Toast.makeText(this, "Backing up...", Toast.LENGTH_SHORT).show()
        Thread {
            val ok = try {
                Backup.export(this, uri)
                true
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                Toast.makeText(this, if (ok) "Backup saved" else "Backup failed", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun doRestore(uri: Uri) {
        Toast.makeText(this, "Restoring...", Toast.LENGTH_SHORT).show()
        Thread {
            val n = try {
                Backup.restore(this, uri)
            } catch (e: Exception) {
                -1
            }
            runOnUiThread {
                if (n < 0) {
                    Toast.makeText(this, "That doesn't look like a Book Reader backup", Toast.LENGTH_LONG).show()
                } else {
                    coverCache.evictAll()
                    refresh()
                    Toast.makeText(this, "Restored: $n book(s) added or updated", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ---------------- Home list ----------------
    private inner class BookAdapter : RecyclerView.Adapter<BookAdapter.VH>() {
        inner class VH(val row: LinearLayout, val title: TextView, val sub: TextView) :
            RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(12), dp(4), dp(12))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                isClickable = true
                isFocusable = true
                val attrs = intArrayOf(android.R.attr.selectableItemBackground)
                val ta = context.obtainStyledAttributes(attrs)
                background = ta.getDrawable(0)
                ta.recycle()
            }
            val title = TextView(parent.context).apply { textSize = 16f; maxLines = 1 }
            val sub = TextView(parent.context).apply { textSize = 13f; alpha = 0.7f }
            row.addView(title)
            row.addView(sub)
            return VH(row, title, sub)
        }

        override fun getItemCount() = books.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val b = books[position]
            h.title.text = b.title
            h.sub.text = "${b.type.uppercase()} · ${b.progressText()}"
            h.row.setOnClickListener { open(b) }
            h.row.setOnLongClickListener { showBookMenu(b); true }
        }
    }

    // ---------------- Library grid ----------------
    private inner class GridAdapter : RecyclerView.Adapter<GridAdapter.VH>() {
        inner class VH(
            val root: LinearLayout,
            val cover: ImageView,
            val bar: ProgressBar,
            val title: TextView,
            val sub: TextView
        ) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val rootV = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(10))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val frame = FrameLayout(ctx).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(0xFFCCCCCC.toInt())
                }
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = true
            }
            val cover = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            frame.addView(cover, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, itemW * 3 / 2
            ))
            val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
            frame.addView(bar, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4), Gravity.BOTTOM
            ))
            rootV.addView(frame)
            val title = TextView(ctx).apply {
                textSize = 13f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(6), 0, 0)
            }
            val sub = TextView(ctx).apply { textSize = 11f; alpha = 0.7f; maxLines = 1 }
            rootV.addView(title)
            rootV.addView(sub)
            return VH(rootV, cover, bar, title, sub)
        }

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val b = shown[position]
            h.title.text = b.title
            h.sub.text = b.progressText()
            h.bar.progress = b.progressPercent()
            h.bar.visibility = if (b.lastOpened > 0) View.VISIBLE else View.GONE
            h.cover.tag = b.id

            val cached = coverCache.get(b.id)
            if (cached != null) {
                h.cover.setImageBitmap(cached)
            } else {
                h.cover.setImageBitmap(Covers.placeholder(b.title, itemW, itemW * 3 / 2))
                coverExec.execute {
                    val file = Covers.ensure(this@MainActivity, b)
                    val bmp = file?.let { Covers.decodeScaled(it.path, itemW * 2) }
                    if (bmp != null) {
                        coverCache.put(b.id, bmp)
                        runOnUiThread { if (h.cover.tag == b.id) h.cover.setImageBitmap(bmp) }
                    }
                }
            }
            h.root.setOnClickListener { open(b) }
            h.root.setOnLongClickListener { showBookMenu(b); true }
        }
    }
}
