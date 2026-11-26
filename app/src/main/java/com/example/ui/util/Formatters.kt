package com.example.ui.util

import java.util.Locale

object Formatters {

    fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "Unknown"
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.2f MB", mb)
        val gb = mb / 1024.0
        if (gb < 1024.0) return String.format(Locale.US, "%.2f GB", gb)
        val tb = gb / 1024.0
        return String.format(Locale.US, "%.2f TB", tb)
    }

    fun formatSpeed(bytesPerSec: Long, useMbps: Boolean): String {
        val safe = bytesPerSec.coerceAtLeast(0L)
        return if (useMbps) {
            val bitsPerSec = safe * 8.0
            when {
                bitsPerSec < 1_000.0 -> String.format(Locale.US, "%.0f bps", bitsPerSec)
                bitsPerSec < 1_000_000.0 -> String.format(Locale.US, "%.1f Kbps", bitsPerSec / 1_000.0)
                bitsPerSec < 1_000_000_000.0 -> String.format(Locale.US, "%.2f Mbps", bitsPerSec / 1_000_000.0)
                else -> String.format(Locale.US, "%.2f Gbps", bitsPerSec / 1_000_000_000.0)
            }
        } else {
            when {
                safe < 1024L -> "$safe B/s"
                safe < 1024L * 1024L -> String.format(Locale.US, "%.1f KB/s", safe / 1024.0)
                safe < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f MB/s", safe / (1024.0 * 1024.0))
                else -> String.format(Locale.US, "%.2f GB/s", safe / (1024.0 * 1024.0 * 1024.0))
            }
        }
    }

    fun formatEta(seconds: Long): String {
        if (seconds < 0L) return "—"
        if (seconds < 60L) return "${seconds}s"
        val mins = seconds / 60L
        val remSec = seconds % 60L
        if (mins < 60L) return "${mins}m ${remSec}s"
        val hours = mins / 60L
        val remMin = mins % 60L
        return "${hours}h ${remMin}m"
    }

    fun formatTime(hour: Int, minute: Int): String =
        String.format(Locale.US, "%02d:%02d", hour.coerceIn(0, 23), minute.coerceIn(0, 59))

    fun extractFirstHttpUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val regex = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
        return regex.find(text.trim())?.value?.trimEnd('.', ',', ')', ']')
    }
}
