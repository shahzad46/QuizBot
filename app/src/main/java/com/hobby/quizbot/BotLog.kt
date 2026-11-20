package com.hobby.quizbot

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tiny file-backed log shown in the app's main screen. */
object BotLog {
    private const val MAX_LINES = 300
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private fun file(ctx: Context) = File(ctx.filesDir, "log.txt")

    @Synchronized
    fun add(ctx: Context, msg: String) {
        val f = file(ctx)
        val lines = if (f.exists()) f.readLines().toMutableList() else mutableListOf()
        lines.add("${fmt.format(Date())}  $msg")
        f.writeText(lines.takeLast(MAX_LINES).joinToString("\n"))
    }

    fun read(ctx: Context): String {
        val f = file(ctx)
        return if (f.exists()) f.readLines().reversed().joinToString("\n") else "(log is empty)"
    }

    fun clear(ctx: Context) { file(ctx).delete() }
}
