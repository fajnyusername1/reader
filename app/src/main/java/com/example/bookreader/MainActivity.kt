package com.example.bookreader

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {

    private lateinit var continueCard: MaterialCardView
    private lateinit var continueTitle: TextView
    private lateinit var continueSub: TextView
    private lateinit var continueProgress: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var list: RecyclerView
    private var books: List<Book> = emptyList()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importBook(uri)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(20))
        }

        root.addView(TextView(this).apply {
            text = "Book Reader"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
        })

        // Continue reading card
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

        // Add book button
        root.addView(MaterialButton(this).apply {
            text = "Add new book"
            setOnClickListener { picker.launch(arrayOf("application/pdf", "text/plain")) }
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
            text = "No books yet. Tap \"Add new book\" to import a PDF or TXT file."
            visibility = View.GONE
        }
        root.addView(emptyText)

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
        }
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
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
        list.adapter = BookAdapter()
    }

    private fun open(book: Book) {
        val cls = if (book.type == "pdf") ReaderActivity::class.java else TextReaderActivity::class.java
        startActivity(Intent(this, cls).putExtra("id", book.id))
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
                    Toast.makeText(this, "Unsupported or unreadable file (PDF and TXT only)", Toast.LENGTH_LONG).show()
                } else {
                    refresh()
                    open(book)
                }
            }
        }.start()
    }

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
            h.row.setOnLongClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Remove \"${b.title}\"?")
                    .setMessage("This removes the book and your saved place from the app.")
                    .setPositiveButton("Remove") { _, _ -> Library.delete(this@MainActivity, b); refresh() }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
        }
    }
}
