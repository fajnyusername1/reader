package com.example.bookreader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
    private var cols = 3
    private var itemW = 300

    private var books: List<Book> = emptyList()
    private val coverExec = Executors.newFixedThreadPool(2)
    private val coverCache = LruCache<String, Bitmap>(24)

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importBook(uri)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            text = "No books yet. Tap \"Add new book\" to import a PDF, EPUB or TXT file."
            visibility = View.GONE
        }
        root.addView(emptyText)

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
        }
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // ---------------- Library tab (cover grid) ----------------
        cols = (resources.configuration.screenWidthDp / 120).coerceIn(2, 6)
        itemW = (resources.displayMetrics.widthPixels - dp(28)) / cols - dp(12)

        libraryView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(48), dp(14), 0)
            visibility = View.GONE
        }
        libraryView.addView(TextView(this).apply {
            text = "Library"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(6), 0, 0, dp(12))
        })
        emptyLibrary = TextView(this).apply {
            text = "Your library is empty. Add a book from the Home tab."
            setPadding(dp(6), 0, 0, 0)
            visibility = View.GONE
        }
        libraryView.addView(emptyLibrary)
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, cols)
        }
        libraryView.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

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
        emptyLibrary.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
        list.adapter = BookAdapter()
        grid.adapter = GridAdapter()
    }

    private fun open(book: Book) {
        val cls = when (book.type) {
            "pdf" -> ReaderActivity::class.java
            "epub" -> EpubReaderActivity::class.java
            else -> TextReaderActivity::class.java
        }
        startActivity(Intent(this, cls).putExtra("id", book.id))
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

    private fun importBook(uri: Uri) {
        Toast.makeText(this, "Importing...", Toast.LENGTH_SHORT).show()
        Thread {
            val book = try {
                Library.import(this, uri)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (book == null) {
                    Toast.makeText(
                        this, "Unsupported or unreadable file (PDF, EPUB and TXT only)", Toast.LENGTH_LONG
                    ).show()
                } else {
                    refresh()
                    open(book)
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
            h.row.setOnLongClickListener { confirmRemove(b); true }
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
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(6), 0, 0)
            }
            val sub = TextView(ctx).apply { textSize = 11f; alpha = 0.7f; maxLines = 1 }
            rootV.addView(title)
            rootV.addView(sub)
            return VH(rootV, cover, bar, title, sub)
        }

        override fun getItemCount() = books.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val b = books[position]
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
            h.root.setOnLongClickListener { confirmRemove(b); true }
        }
    }
}
