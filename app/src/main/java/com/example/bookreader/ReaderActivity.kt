package com.example.bookreader

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors

/**
 * Continuous-scroll PDF viewer. On landscape tablets pages are shown two per row (like an open book).
 * The first visible page is saved whenever the app is paused.
 */
class ReaderActivity : AppCompatActivity() {

    private lateinit var book: Book
    private lateinit var frame: FrameLayout
    private lateinit var rv: RecyclerView
    private lateinit var lm: GridLayoutManager
    private lateinit var indicator: TextView

    private var span = 1
    private var themeId = 0
    private var renderer: PdfRenderer? = null
    private var pageSizes = IntArray(0) // width,height pairs
    private var pageCount = 0
    private val lock = Any()
    private var closed = false
    private val exec = Executors.newSingleThreadExecutor()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val found = Library.find(this, intent.getStringExtra("id"))
        if (found == null) { finish(); return }
        book = found

        themeId = ReaderTheme.get(this)
        val cfg = resources.configuration
        val twoPage = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("twoPage", true)
        span = if (twoPage && cfg.orientation == Configuration.ORIENTATION_LANDSCAPE && cfg.screenWidthDp >= 600) 2 else 1

        frame = FrameLayout(this)
        rv = RecyclerView(this)
        lm = GridLayoutManager(this, span)
        rv.layoutManager = lm
        frame.addView(rv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        indicator = TextView(this).apply {
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(8), dp(8), dp(8))
            textSize = 14f
            text = book.title
            setOnClickListener { askForPage() }
        }
        val themeBtn = TextView(this).apply {
            text = "◐"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener {
                themeId = ReaderTheme.next(this@ReaderActivity)
                applyTheme()
            }
        }
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            addView(indicator)
            addView(themeBtn)
        }
        frame.addView(pill, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(24) })

        setContentView(frame)
        applyTheme()

        // Open the PDF and measure every page off the main thread.
        exec.execute {
            try {
                val pfd = ParcelFileDescriptor.open(File(book.path), ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(pfd)
                val sizes = IntArray(r.pageCount * 2)
                for (i in 0 until r.pageCount) {
                    r.openPage(i).use { sizes[2 * i] = it.width; sizes[2 * i + 1] = it.height }
                }
                runOnUiThread {
                    if (closed) { r.close(); return@runOnUiThread }
                    renderer = r
                    pageSizes = sizes
                    pageCount = r.pageCount
                    rv.adapter = PageAdapter()
                    val start = book.position.coerceIn(0, pageCount - 1)
                    lm.scrollToPositionWithOffset(start, 0)
                    updateIndicator(start)
                    Library.update(this, book.id, start, pageCount)
                    rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                            updateIndicator(lm.findFirstVisibleItemPosition())
                        }
                    })
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Could not open this PDF", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun applyTheme() {
        val bg = ReaderTheme.bg(themeId)
        val filter = ReaderTheme.pageFilter(themeId)
        frame.setBackgroundColor(bg)
        rv.setBackgroundColor(bg)
        for (i in 0 until rv.childCount) {
            val iv = rv.getChildAt(i) as? ImageView ?: continue
            iv.colorFilter = filter
            iv.setBackgroundColor(bg)
        }
    }

    private fun updateIndicator(pos: Int) {
        if (pos < 0) return
        val a = pos + 1
        indicator.text = if (span == 2) {
            "Pages $a–${minOf(a + 1, pageCount)} / $pageCount  ·  tap to jump"
        } else {
            "Page $a / $pageCount  ·  tap to jump"
        }
    }

    private fun askForPage() {
        if (pageCount == 0) return
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        AlertDialog.Builder(this)
            .setTitle("Go to page (1–$pageCount)")
            .setView(input)
            .setPositiveButton("Go") { _, _ ->
                input.text.toString().toIntOrNull()?.let {
                    lm.scrollToPositionWithOffset((it - 1).coerceIn(0, pageCount - 1), 0)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        if (pageCount > 0) {
            val p = lm.findFirstVisibleItemPosition()
            if (p >= 0) Library.update(this, book.id, p, pageCount)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exec.shutdownNow()
        synchronized(lock) {
            closed = true
            renderer?.close()
            renderer = null
        }
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.VH>() {
        inner class VH(val img: ImageView) : RecyclerView.ViewHolder(img) {
            @Volatile var page = -1
        }

        private val pageW = resources.displayMetrics.widthPixels / span

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val iv = ImageView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                scaleType = ImageView.ScaleType.FIT_XY
            }
            return VH(iv)
        }

        override fun getItemCount() = pageCount

        override fun onBindViewHolder(h: VH, position: Int) {
            val w = pageSizes[2 * position]
            val ph = pageSizes[2 * position + 1]
            val heightPx = (pageW.toLong() * ph / w).toInt().coerceAtLeast(1)
            h.page = position
            h.img.setImageBitmap(null)
            h.img.colorFilter = ReaderTheme.pageFilter(themeId)
            h.img.setBackgroundColor(ReaderTheme.bg(themeId))
            val lp = h.img.layoutParams
            lp.height = heightPx
            h.img.layoutParams = lp

            exec.execute {
                if (h.page != position) return@execute
                val bmp = synchronized(lock) {
                    val r = renderer
                    if (closed || r == null) return@execute
                    val b = Bitmap.createBitmap(pageW, heightPx, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    r.openPage(position).use {
                        it.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                    b
                }
                runOnUiThread { if (h.page == position && !closed) h.img.setImageBitmap(bmp) }
            }
        }

        override fun onViewRecycled(h: VH) {
            h.page = -1
            h.img.setImageBitmap(null)
        }
    }
}
