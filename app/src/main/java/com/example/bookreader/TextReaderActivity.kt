package com.example.bookreader

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** Plain-text reader. Position is stored as scroll progress 0..10000 so it survives font/size changes. */
class TextReaderActivity : AppCompatActivity() {

    private lateinit var book: Book
    private lateinit var frame: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var tv: TextView
    private var loaded = false
    private var themeId = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val found = Library.find(this, intent.getStringExtra("id"))
        if (found == null) { finish(); return }
        book = found
        themeId = ReaderTheme.get(this)

        // Keep lines a comfortable length on wide screens.
        val sidePad = maxOf(dp(20), (resources.displayMetrics.widthPixels - dp(720)) / 2)
        tv = TextView(this).apply {
            textSize = 18f
            setLineSpacing(0f, 1.25f)
            setPadding(sidePad, dp(48), sidePad, dp(48))
        }
        scroll = ScrollView(this).apply { addView(tv) }

        val themeBtn = TextView(this).apply {
            text = "◐"
            textSize = 20f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener {
                themeId = ReaderTheme.next(this@TextReaderActivity)
                applyTheme()
            }
        }
        frame = FrameLayout(this)
        frame.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        frame.addView(themeBtn, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        ).apply { setMargins(0, 0, dp(16), dp(24)) })
        setContentView(frame)
        applyTheme()

        Thread {
            try {
                val text = File(book.path).readText()
                runOnUiThread {
                    tv.text = text
                    loaded = true
                    scroll.post {
                        val max = (tv.height - scroll.height).coerceAtLeast(0)
                        scroll.scrollTo(0, (max.toLong() * book.position / 10000).toInt())
                    }
                    Library.update(this, book.id, book.position, 10000)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Could not open this file", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    private fun applyTheme() {
        val bg = ReaderTheme.bg(themeId)
        frame.setBackgroundColor(bg)
        scroll.setBackgroundColor(bg)
        tv.setTextColor(ReaderTheme.fg(themeId))
    }

    override fun onPause() {
        super.onPause()
        if (!loaded) return
        val max = (tv.height - scroll.height).coerceAtLeast(1)
        val pos = (scroll.scrollY.toLong() * 10000 / max).toInt().coerceIn(0, 10000)
        Library.update(this, book.id, pos, 10000)
    }
}
