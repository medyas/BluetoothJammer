package util

import java.util.Locale

/**
 * Small pure formatting helpers, kept free of Android types so they can be unit tested
 * on the host JVM.
 */
object Format {

    /** Human-readable byte count, e.g. 1536 -> "1.5 KB", 0 -> "0 B". */
    fun humanBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }
}
