package com.example.bookreader

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
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
    private lateinit var label: TextView

    private var spine: List<File> = emptyList()
    private var chapter = 0
    private var targetFrac = 0f
    private var restored = true
    private var ready = false
    private var zoom = 100

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val found = Library.find(this, intent.getStringExtra("id"))
        if (found == null) { finish(); return }
        book = found
        zoom = getSharedPreferences("epub", MODE_PRIVATE).getInt("zoom", 100)

        web = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
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
                    val css = "body{margin:0 !important;padding:0 16px !important;line-height:1.5;" +
                        "word-wrap:break-word} img,svg{max-width:100% !important;height:auto !important}"
                    view.evaluateJavascript(
                        "(function(){var s=document.createElement('style');s.innerHTML='$css';" +
                            "(document.head||document.documentElement).appendChild(s);})();", null
                    )
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
            minWidth = dp(48)
            minimumWidth = dp(48)
        }

        val prev = barButton("‹").apply { setOnClickListener { goTo(chapter - 1) } }
        val next = barButton("›").apply { setOnClickListener { goTo(chapter + 1) } }
        val smaller = barButton("A−").apply { setOnClickListener { changeZoom(-10) } }
        val bigger = barButton("A+").apply { setOnClickListener { changeZoom(10) } }
        label = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            text = book.title
            maxLines = 1
            setOnClickListener { askForChapter() }
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(prev)
            addView(smaller)
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(bigger)
            addView(next)
        }

        val root = LinearLayout(this).apply {
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

    private fun load(index: Int, frac: Float) {
        chapter = index
        targetFrac = frac
        restored = false
        label.text = "Chapter ${index + 1} / ${spine.size}  ·  tap to jump"
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

    private fun askForChapter() {
        if (!ready) return
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        AlertDialog.Builder(this)
            .setTitle("Go to chapter (1–${spine.size})")
            .setView(input)
            .setPositiveButton("Go") { _, _ ->
                input.text.toString().toIntOrNull()?.let { goTo((it - 1).coerceIn(0, spine.size - 1)) }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
