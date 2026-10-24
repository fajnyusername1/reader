package com.example.bookreader

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** Plain-text reader. Position is stored as scroll progress 0..10000 so it survives font/size changes. */
class TextReaderActivity : AppCompatActivity() {

    private lateinit var book: Book
    private lateinit var scroll: ScrollView
    private lateinit var tv: TextView
    private var loaded = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val found = Library.find(this, intent.getStringExtra("id"))
        if (found == null) { finish(); return }
        book = found

        tv = TextView(this).apply {
            textSize = 18f
            setLineSpacing(0f, 1.25f)
            setPadding(dp(20), dp(48), dp(20), dp(48))
        }
        scroll = ScrollView(this).apply { addView(tv) }
        setContentView(scroll)

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

    override fun onPause() {
        super.onPause()
        if (!loaded) return
        val max = (tv.height - scroll.height).coerceAtLeast(1)
        val pos = (scroll.scrollY.toLong() * 10000 / max).toInt().coerceIn(0, 10000)
        Library.update(this, book.id, pos, 10000)
    }
}
