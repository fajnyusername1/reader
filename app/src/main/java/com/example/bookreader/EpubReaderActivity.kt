package com.example.bookreader

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

/** EPUB reader: one chapter at a time in a WebView, with scroll position saved inside the chapter. */
@SuppressLint("SetJavaScriptEnabled")
class EpubReaderActivity : AppCompatActivity() {

    private lateinit var book: Book
    private lateinit var web: WebView
    private lateinit var root: LinearLayout
    private lateinit var bar: LinearLayout
    private lateinit var label: TextView
    private var barTexts: List<TextView> = emptyList()

    private var spine: List<File> = emptyList()
    private var toc: List<TocEntry> = emptyList()
    private var chapter = 0
    private var targetFrac = 0f
    private var restored = true
    private var ready = false
    private var zoom = 100
    private var themeId = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val found = Library.find(this, intent.getStringExtra("id"))
        if (found == null) { finish(); return }
        book = found
        zoom = getSharedPreferences("epub", MODE_PRIVATE).getInt("zoom", 100)
        themeId = ReaderTheme.get(this)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.textZoom = zoom
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url
                    if (u.scheme == "file") {
                        val path = u.path ?: return true
                        val target = File(path).canonicalPath
                        val idx = spine.indexOfFirst { it.canonicalPath == target }
                        if (idx >= 0) {
                            load(idx, 0f)
                            return true
                        }
                        return false
                    }
                    return true // never open web links
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    injectStyle()
                    val ch = chapter
                    view.postDelayed({
                        if (ch == chapter) {
                            val f = targetFrac
                            if (f > 0f) {
                                view.evaluateJavascript(
                                    "(function(){var r=document.documentElement.scrollHeight-window.innerHeight;" +
                                        "window.scrollTo(0,r>0?r*$f:0);})();", null
                                )
                            }
                            restored = true
                        }
                    }, 300)
                }
            }
        }

        fun barButton(text: String) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            this.text = text
            textSize = 18f
            minWidth = dp(44)
            minimumWidth = dp(44)
        }

        val prev = barButton("‹").apply { setOnClickListener { goTo(chapter - 1) } }
        val next = barButton("›").apply { setOnClickListener { goTo(chapter + 1) } }
        val smaller = barButton("A−").apply { setOnClickListener { changeZoom(-10) } }
        val bigger = barButton("A+").apply { setOnClickListener { changeZoom(10) } }
        val themeBtn = barButton("◐").apply {
            setOnClickListener {
                themeId = ReaderTheme.next(this@EpubReaderActivity)
                applyTheme()
            }
        }
        label = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            text = "☰  contents"
            maxLines = 1
            setOnClickListener { showToc() }
        }
        barTexts = listOf(prev, smaller, label, bigger, themeBtn, next)

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(prev)
            addView(smaller)
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(bigger)
            addView(themeBtn)
            addView(next)
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(root)
        applyTheme()

        Thread {
            try {
                val info = Epub.parse(File(book.path))
                runOnUiThread {
                    if (info.spine.isEmpty()) {
                        Toast.makeText(this, "This EPUB has no readable chapters", Toast.LENGTH_LONG).show()
                        finish()
                        return@runOnUiThread
                    }
                    spine = info.spine
                    toc = info.toc
                    ready = true
                    val start = (book.position / 10000).coerceIn(0, spine.size - 1)
                    val frac = (book.position % 10000) / 10000f
                    Library.update(this, book.id, book.position, spine.size * 10000)
                    load(start, frac)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Could not open this EPUB", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    private fun applyTheme() {
        val bg = ReaderTheme.bg(themeId)
        val fg = ReaderTheme.fg(themeId)
        root.setBackgroundColor(bg)
        bar.setBackgroundColor(bg)
        web.setBackgroundColor(bg)
        barTexts.forEach { it.setTextColor(fg) }
        injectStyle()
    }

    /** Readable layout (centered column on wide screens) plus the chosen reading theme. */
    private fun injectStyle() {
        val css = "body{margin:0 auto !important;padding:0 16px !important;max-width:46em;line-height:1.5;" +
            "word-wrap:break-word}img,svg{max-width:100% !important;height:auto !important}" +
            ReaderTheme.css(themeId)
        web.evaluateJavascript(
            "(function(){var s=document.getElementById('bkstyle');if(!s){s=document.createElement('style');" +
                "s.id='bkstyle';(document.head||document.documentElement).appendChild(s);}" +
                "s.innerHTML='$css';})();", null
        )
    }

    private fun load(index: Int, frac: Float) {
        chapter = index
        targetFrac = frac
        restored = false
        label.text = "☰  ${index + 1} / ${spine.size}"
        web.loadUrl(Uri.fromFile(spine[index]).toString())
    }

    private fun goTo(index: Int) {
        if (!ready || index < 0 || index >= spine.size) return
        load(index, 0f)
    }

    private fun changeZoom(delta: Int) {
        zoom = (zoom + delta).coerceIn(70, 220)
        web.settings.textZoom = zoom
        getSharedPreferences("epub", MODE_PRIVATE).edit().putInt("zoom", zoom).apply()
    }

    private fun showToc() {
        if (!ready) return
        val entries = if (toc.isNotEmpty()) toc else spine.indices.map { TocEntry("Chapter ${it + 1}", 0, it) }
        val labels = entries.map { "    ".repeat(it.level) + it.title }.toTypedArray()
        val checked = entries.indexOfLast { it.spineIndex <= chapter }.coerceAtLeast(0)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Contents")
            .setSingleChoiceItems(labels, checked) { d, which ->
                d.dismiss()
                goTo(entries[which].spineIndex)
            }
            .setNegativeButton("Close", null)
            .show()
        dialog.listView.setSelection(checked)
    }

    private fun liveFraction(): Float {
        val range = web.contentHeight * resources.displayMetrics.density - web.height
        return if (range <= 1f) 0f else (web.scrollY / range).coerceIn(0f, 1f)
    }

    override fun onPause() {
        super.onPause()
        if (!ready) return
        val frac = if (restored) liveFraction() else targetFrac
        Library.update(this, book.id, chapter * 10000 + (frac * 9999).toInt(), spine.size * 10000)
    }

    override fun onDestroy() {
        super.onDestroy()
        web.destroy()
    }
}
