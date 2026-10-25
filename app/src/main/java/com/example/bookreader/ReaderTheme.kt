package com.example.bookreader

import android.content.Context
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

/** Reading theme shared by all readers: 0 = Light, 1 = Sepia, 2 = Dark. */
object ReaderTheme {
    val names = arrayOf("Light", "Sepia", "Dark")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)

    fun get(ctx: Context): Int = prefs(ctx).getInt("readerTheme", 0)

    fun set(ctx: Context, theme: Int) {
        prefs(ctx).edit().putInt("readerTheme", theme).apply()
    }

    fun next(ctx: Context): Int {
        val n = (get(ctx) + 1) % names.size
        set(ctx, n)
        return n
    }

    fun bg(t: Int): Int = when (t) {
        1 -> 0xFFF4ECD8.toInt()
        2 -> 0xFF121212.toInt()
        else -> 0xFFFFFFFF.toInt()
    }

    fun fg(t: Int): Int = when (t) {
        1 -> 0xFF5B4636.toInt()
        2 -> 0xFFC9C9C9.toInt()
        else -> 0xFF111111.toInt()
    }

    private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    /** Color filter that turns the white PDF page bitmaps into the chosen theme. */
    fun pageFilter(t: Int): ColorFilter? = when (t) {
        1 -> ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    0.957f, 0f, 0f, 0f, 0f,
                    0f, 0.925f, 0f, 0f, 0f,
                    0f, 0f, 0.847f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        2 -> ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    -0.8f, 0f, 0f, 0f, 222f,
                    0f, -0.8f, 0f, 0f, 222f,
                    0f, 0f, -0.8f, 0f, 222f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        else -> null
    }

    /** CSS appended to EPUB chapters. Empty for the light theme so the book keeps its own look. */
    fun css(t: Int): String {
        if (t == 0) return ""
        val link = if (t == 2) "#8AB4F8" else "#8B5A2B"
        return "html,body{background:${hex(bg(t))} !important;color:${hex(fg(t))} !important}" +
            "body *{color:inherit !important;background-color:transparent !important}" +
            "a,a *{color:$link !important}"
    }
}
