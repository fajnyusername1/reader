package com.example.bookreader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * position / total by type:
 *   pdf  -> zero-based page index / page count
 *   epub -> chapter * 10000 + progress inside chapter (0..9999) / chapterCount * 10000
 *   txt  -> scroll progress 0..10000 / 10000
 * path: file for pdf and txt, the unzipped folder for epub.
 * lastOpened == 0 means the book was never opened. shelf == "" means no shelf.
 */
data class Book(
    val id: String,
    val title: String,
    val path: String,
    val type: String,
    var position: Int,
    var total: Int,
    var lastOpened: Long,
    var shelf: String = "",
    val added: Long = 0L
) {
    fun progressText(): String = when {
        lastOpened == 0L -> "Not started"
        isFinished() -> "Finished"
        type == "pdf" -> "Page ${position + 1} of $total"
        type == "epub" -> "Chapter ${position / 10000 + 1} of ${total / 10000}"
        else -> "${position / 100}%"
    }

    fun progressPercent(): Int = when {
        lastOpened == 0L -> 0
        type == "pdf" -> if (total > 0) ((position + 1) * 100L / total).toInt() else 0
        type == "epub" -> if (total > 0) (position * 100L / total).toInt() else 0
        else -> position / 100
    }

    fun isFinished(): Boolean = lastOpened > 0 && when (type) {
        "pdf" -> total > 0 && position + 1 >= total - 1
        "epub" -> total > 0 && position >= total - 1000
        else -> position >= 9800
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
                    o.getLong("lastOpened"), o.optString("shelf", ""), o.optLong("added", 0L)
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
                    .put("shelf", it.shelf).put("added", it.added)
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

    fun setShelf(ctx: Context, id: String, shelf: String) {
        val books = load(ctx)
        books.firstOrNull { it.id == id }?.shelf = shelf.trim()
        save(ctx, books)
    }

    fun delete(ctx: Context, book: Book) {
        val p = File(book.path)
        if (p.isDirectory) p.deleteRecursively() else p.delete()
        Covers.file(ctx, book).delete()
        save(ctx, load(ctx).filter { it.id != book.id })
    }

    // ---------------- Backup support ----------------

    /** Library as JSON with file paths relative to the app's files folder. */
    fun exportJson(ctx: Context): String {
        val arr = JSONArray()
        load(ctx).forEach { b ->
            val rel = try {
                File(b.path).relativeTo(ctx.filesDir).path
            } catch (e: IllegalArgumentException) {
                b.path
            }
            arr.put(
                JSONObject()
                    .put("id", b.id).put("title", b.title)
                    .put("path", rel.replace(File.separatorChar, '/'))
                    .put("type", b.type).put("position", b.position)
                    .put("total", b.total).put("lastOpened", b.lastOpened)
                    .put("shelf", b.shelf).put("added", b.added)
            )
        }
        return JSONObject().put("version", 1).put("books", arr).toString()
    }

    /** Adds books from a backup; for books already present, keeps whichever reading position is newer. */
    fun mergeFromJson(ctx: Context, raw: String): Int {
        val arr = JSONObject(raw).getJSONArray("books")
        val current = load(ctx)
        val root = ctx.filesDir.canonicalPath + File.separator
        var changed = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            val file = File(ctx.filesDir, o.getString("path"))
            if (!file.exists() || !file.canonicalPath.startsWith(root)) continue
            val lastOpened = o.getLong("lastOpened")
            val existing = current.firstOrNull { it.id == id }
            if (existing == null) {
                current.add(
                    Book(
                        id, o.getString("title"), file.path, o.getString("type"),
                        o.getInt("position"), o.getInt("total"), lastOpened,
                        o.optString("shelf", ""), o.optLong("added", 0L)
                    )
                )
                changed++
            } else {
                if (lastOpened > existing.lastOpened) {
                    existing.position = o.getInt("position")
                    existing.total = o.getInt("total")
                    existing.lastOpened = lastOpened
                    changed++
                }
                if (existing.shelf.isEmpty()) existing.shelf = o.optString("shelf", "")
            }
        }
        save(ctx, current)
        return changed
    }

    // ---------------- Import ----------------

    /** Copies/unpacks the picked file into app storage. Returns null if the file type is unsupported. */
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
            lower.endsWith(".epub") || mime == "application/epub+zip" -> "epub"
            lower.endsWith(".txt") || mime == "text/plain" -> "txt"
            else -> return null
        }
        val id = UUID.randomUUID().toString()
        val booksDir = File(ctx.filesDir, "books").apply { mkdirs() }
        val fallbackTitle = name.substringBeforeLast('.')

        val book: Book = (if (type == "epub") {
            importEpub(ctx, uri, id, File(booksDir, id), fallbackTitle)
        } else {
            importFile(ctx, uri, id, File(booksDir, "$id.$type"), type, fallbackTitle)
        }) ?: return null

        try { Covers.ensure(ctx, book) } catch (e: Exception) { /* placeholder will be used */ }

        val books = load(ctx)
        books.add(book)
        save(ctx, books)
        return book
    }

    private fun importFile(ctx: Context, uri: Uri, id: String, file: File, type: String, title: String): Book? {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { input.copyTo(it) }
        } ?: return null
        return Book(id, title, file.path, type, 0, 0, 0L, "", System.currentTimeMillis())
    }

    private fun importEpub(ctx: Context, uri: Uri, id: String, dir: File, fallbackTitle: String): Book? {
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: return null
            input.use { Epub.unzip(it, dir) }
            val info = Epub.parse(dir)
            if (info.spine.isEmpty()) {
                dir.deleteRecursively()
                return null
            }
            val title = info.title?.takeIf { it.isNotBlank() } ?: fallbackTitle
            return Book(id, title, dir.path, "epub", 0, 0, 0L, "", System.currentTimeMillis())
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }
}
