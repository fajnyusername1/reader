package com.example.bookreader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * position: PDF -> zero-based page index; TXT -> scroll progress 0..10000
 * total:    PDF -> page count;            TXT -> 10000
 * lastOpened == 0 means the book was never opened.
 */
data class Book(
    val id: String,
    val title: String,
    val path: String,
    val type: String,
    var position: Int,
    var total: Int,
    var lastOpened: Long
) {
    fun progressText(): String = when {
        lastOpened == 0L -> "Not started"
        type == "pdf" -> "Page ${position + 1} of $total"
        else -> "${position / 100}%"
    }

    fun progressPercent(): Int = when {
        lastOpened == 0L -> 0
        type == "pdf" -> if (total > 0) (position + 1) * 100 / total else 0
        else -> position / 100
    }
}

object Library {
    private const val PREFS = "library"
    private const val KEY = "books"

    fun load(ctx: Context): MutableList<Book> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return mutableListOf()
        val arr = JSONArray(raw)
        val out = mutableListOf<Book>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                Book(
                    o.getString("id"), o.getString("title"), o.getString("path"),
                    o.getString("type"), o.getInt("position"), o.getInt("total"),
                    o.getLong("lastOpened")
                )
            )
        }
        return out
    }

    private fun save(ctx: Context, books: List<Book>) {
        val arr = JSONArray()
        books.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("title", it.title).put("path", it.path)
                    .put("type", it.type).put("position", it.position)
                    .put("total", it.total).put("lastOpened", it.lastOpened)
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun find(ctx: Context, id: String?): Book? = load(ctx).firstOrNull { it.id == id }

    fun lastRead(ctx: Context): Book? =
        load(ctx).filter { it.lastOpened > 0 }.maxByOrNull { it.lastOpened }

    fun update(ctx: Context, id: String, position: Int, total: Int) {
        val books = load(ctx)
        books.firstOrNull { it.id == id }?.let {
            it.position = position
            it.total = total
            it.lastOpened = System.currentTimeMillis()
        }
        save(ctx, books)
    }

    fun delete(ctx: Context, book: Book) {
        File(book.path).delete()
        save(ctx, load(ctx).filter { it.id != book.id })
    }

    /** Copies the picked file into app storage. Returns null if the file type is unsupported. */
    fun import(ctx: Context, uri: Uri): Book? {
        var name = "Book"
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
        }
        val lower = name.lowercase()
        val mime = ctx.contentResolver.getType(uri)
        val type = when {
            lower.endsWith(".pdf") || mime == "application/pdf" -> "pdf"
            lower.endsWith(".txt") || mime == "text/plain" -> "txt"
            else -> return null
        }
        val id = UUID.randomUUID().toString()
        val dir = File(ctx.filesDir, "books").apply { mkdirs() }
        val file = File(dir, "$id.$type")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { input.copyTo(it) }
        } ?: return null
        val book = Book(id, name.substringBeforeLast('.'), file.path, type, 0, 0, 0L)
        val books = load(ctx)
        books.add(book)
        save(ctx, books)
        return book
    }
}
