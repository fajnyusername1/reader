package com.example.bookreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.io.File

/** Cover thumbnails: first page for PDFs, the embedded cover image for EPUBs, a colored title card otherwise. */
object Covers {

    private val colors = intArrayOf(
        0xFF37474F.toInt(), 0xFF5D4037.toInt(), 0xFF283593.toInt(), 0xFF00695C.toInt(),
        0xFF6A1B9A.toInt(), 0xFFAD1457.toInt(), 0xFF2E7D32.toInt()
    )

    fun file(ctx: Context, book: Book) = File(File(ctx.filesDir, "covers"), "${book.id}.jpg")

    /** Returns the cover file, generating it if needed. Call from a background thread. Null = no real cover. */
    fun ensure(ctx: Context, book: Book): File? {
        val f = file(ctx, book)
        if (f.exists()) return f
        f.parentFile?.mkdirs()
        val bmp: Bitmap = (try {
            when (book.type) {
                "pdf" -> pdfFirstPage(book.path)
                "epub" -> epubCover(book.path)
                else -> null
            }
        } catch (e: Exception) {
            null
        }) ?: return null
        val tmp = File(f.path + ".tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        return if (tmp.renameTo(f)) f else null
    }

    private fun pdfFirstPage(path: String): Bitmap? =
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                if (r.pageCount == 0) {
                    null
                } else {
                    r.openPage(0).use { p ->
                        val w = 400
                        val h = (w.toLong() * p.height / p.width).toInt().coerceAtLeast(1)
                        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        b.eraseColor(Color.WHITE)
                        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        b
                    }
                }
            }
        }

    private fun epubCover(dir: String): Bitmap? {
        val cover = Epub.parse(File(dir)).cover ?: return null
        val b = decodeScaled(cover.path, 400) ?: return null
        return flatten(b)
    }

    private fun flatten(b: Bitmap): Bitmap {
        if (!b.hasAlpha()) return b
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(b, 0f, 0f, null)
        b.recycle()
        return out
    }

    fun decodeScaled(path: String, reqWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqWidth) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun placeholder(title: String, w: Int, h: Int): Bitmap {
        val color = colors[(title.hashCode() and Int.MAX_VALUE) % colors.size]
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(color)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = w / 9f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        val pad = (w * 0.1f).toInt()
        val layout = StaticLayout.Builder.obtain(title, 0, title.length, paint, w - 2 * pad)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(6)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        c.save()
        c.translate(pad.toFloat(), (h - layout.height) / 2f)
        layout.draw(c)
        c.restore()
        return bmp
    }
}
