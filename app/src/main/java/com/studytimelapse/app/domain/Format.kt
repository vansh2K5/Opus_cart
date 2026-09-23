package com.studytimelapse.app.domain

/** Human-friendly duration formatting shared by UI, notifications and exports. */
object Format {
    /** "2h 14m", "45m", "0m". Seconds are dropped (floored). */
    fun duration(ms: Long): String {
        val totalMinutes = (ms.coerceAtLeast(0) / 60_000)
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return when {
            h == 0L -> "${m}m"
            m == 0L -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }

    /** "01:23:41" — the big study-mode timer. */
    fun clock(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0) / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    /** "2m 14s" — for short video lengths. */
    fun shortDuration(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0) / 1000
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return if (m == 0L) "${s}s" else "${m}m ${s}s"
    }

    /** Spoken form for screen readers: "2 hours 14 minutes". */
    fun spokenDuration(ms: Long): String {
        val totalMinutes = ms.coerceAtLeast(0) / 60_000
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        val parts = buildList {
            if (h > 0) add("$h hour${if (h == 1L) "" else "s"}")
            if (m > 0 || h == 0L) add("$m minute${if (m == 1L) "" else "s"}")
        }
        return parts.joinToString(" ")
    }

    fun thousands(n: Int): String = "%,d".format(n)
}
