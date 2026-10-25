package com.example.bookreader

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One zip with everything: library.json (titles, shelves, reading positions), the book files and covers.
 * Restoring merges into the current library, so it is safe to run on a phone that already has books.
 */
object Backup {

    fun export(ctx: Context, uri: Uri) {
        val out = ctx.contentResolver.openOutputStream(uri) ?: throw IOException("Cannot write backup")
        ZipOutputStream(BufferedOutputStream(out)).use { zos ->
            zos.setLevel(1)
            zos.putNextEntry(ZipEntry("library.json"))
            zos.write(Library.exportJson(ctx).toByteArray())
            zos.closeEntry()
            for (sub in listOf("books", "covers")) {
                val base = File(ctx.filesDir, sub)
                if (!base.exists()) continue
                base.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { f ->
                    val name = f.relativeTo(ctx.filesDir).path.replace(File.separatorChar, '/')
                    zos.putNextEntry(ZipEntry(name))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }

    /** Returns how many books were added or had their reading position updated. */
    fun restore(ctx: Context, uri: Uri): Int {
        val root = ctx.filesDir.canonicalPath + File.separator
        var hasLibrary = false
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read backup")
        ZipInputStream(BufferedInputStream(input)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                // Only these locations are ever written, so a random zip cannot touch other app files.
                val allowed = name == "library.json" || name.startsWith("books/") || name.startsWith("covers/")
                if (allowed && !entry.isDirectory) {
                    val out = File(ctx.filesDir, name)
                    if (!out.canonicalPath.startsWith(root)) throw SecurityException("Bad entry")
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                    if (name == "library.json") hasLibrary = true
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        val json = File(ctx.filesDir, "library.json")
        if (!hasLibrary || !json.exists()) throw IOException("Not a Book Reader backup")
        val raw = json.readText()
        json.delete()
        return Library.mergeFromJson(ctx, raw)
    }
}
