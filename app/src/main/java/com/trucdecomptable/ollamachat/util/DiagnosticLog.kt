package com.trucdecomptable.ollamachat.util

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * A small in-memory ring buffer of things that went wrong.
 *
 * The app has no crash reporting and never phones home, so when something
 * misbehaves there is otherwise nothing to look at. This keeps the last
 * failures around so the user can share them on request.
 *
 * Nothing here leaves the device on its own, and message bodies are never
 * recorded — only what failed and why.
 */
object DiagnosticLog {

    private const val CAPACITY = 200

    data class Entry(val at: Long, val tag: String, val message: String)

    private val entries = ArrayDeque<Entry>(CAPACITY)

    @Synchronized
    fun record(tag: String, message: String) {
        if (message.isBlank()) return
        if (entries.size >= CAPACITY) entries.removeFirst()
        entries.addLast(Entry(System.currentTimeMillis(), tag, message.take(400)))
    }

    @Synchronized
    fun record(tag: String, throwable: Throwable) {
        record(tag, "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}")
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

    fun formatted(): String {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val lines = snapshot()
        if (lines.isEmpty()) return "Journal vide."
        return lines.joinToString("\n") { "${stamp.format(Date(it.at))}  [${it.tag}]  ${it.message}" }
    }
}
