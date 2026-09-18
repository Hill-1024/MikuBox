package top.uwu.mikubox.ui

import java.util.Locale

/** Byte formatting shared by the screens that report traffic. */
object TrafficFormat {

    /** "12.3 MiB" — readable sizes, for places with room for them. */
    fun readable(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB")
        var value = bytes.toDouble() / 1024
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    /** "45.3M" — short enough for a list row's traffic column. */
    fun compact(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> String.format(Locale.US, "%.0fK", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1fM", bytes / 1024.0 / 1024)
        else -> String.format(Locale.US, "%.2fG", bytes / 1024.0 / 1024 / 1024)
    }
}
