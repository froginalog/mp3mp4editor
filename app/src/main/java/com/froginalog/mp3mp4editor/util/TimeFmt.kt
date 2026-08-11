package com.froginalog.mp3mp4editor.util

import java.util.Locale

/** Formatting/parsing helpers for the millisecond timestamps used all over the trimmer. */
object TimeFmt {

    /** `1:02.480` or `1:04:02.480` when the value runs past an hour. */
    fun clock(ms: Long, withMillis: Boolean = true): String {
        val safe = ms.coerceAtLeast(0L)
        val totalSec = safe / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val millis = safe % 1000
        val head = if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
        return if (withMillis) String.format(Locale.US, "%s.%03d", head, millis) else head
    }

    /**
     * Parses the strings [clock] produces, plus looser input a person would actually type:
     * `12`, `1:30`, `1:30.5`, `01:02:03.250`. Returns null when it cannot be read.
     */
    fun parse(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(':')
        if (parts.size > 3) return null

        var seconds = 0.0
        for ((index, raw) in parts.withIndex()) {
            val part = raw.trim()
            // Only the final component may carry a fraction.
            val value = part.toDoubleOrNull() ?: return null
            if (value < 0) return null
            if (index < parts.lastIndex && (part.contains('.') || value >= 60)) return null
            seconds = seconds * 60 + value
        }
        return (seconds * 1000).toLong()
    }

    fun bytes(count: Long): String {
        if (count <= 0) return "—"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = count.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (unit == 0) "$count B" else String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}
