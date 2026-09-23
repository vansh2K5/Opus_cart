package com.studytimelapse.app.util

import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * App-specific storage layout. Timelapses live in internal app storage: private to the app,
 * excluded from cloud backup (see data_extraction_rules.xml) and removed on uninstall.
 *
 * files/timelapses/<sessionId>/seg_0000.mp4 ...   rolling segments while recording
 * files/timelapses/<sessionId>/timelapse.mp4      final joined video
 * files/timelapses/<sessionId>/thumb.jpg          poster frame
 */
object Storage {
    const val LOW_SPACE_WARN_BYTES = 500L * 1024 * 1024
    const val LOW_SPACE_STOP_BYTES = 150L * 1024 * 1024

    fun timelapsesRoot(context: Context): File = File(context.filesDir, "timelapses").apply { mkdirs() }

    fun sessionDir(context: Context, sessionId: String): File = File(timelapsesRoot(context), sessionId).apply { mkdirs() }

    fun finalVideo(context: Context, sessionId: String): File = File(sessionDir(context, sessionId), "timelapse.mp4")

    fun thumbnail(context: Context, sessionId: String): File = File(sessionDir(context, sessionId), "thumb.jpg")

    fun testDir(context: Context): File = File(context.cacheDir, "screen_off_test").apply { mkdirs() }

    fun sharedDir(context: Context): File = File(context.cacheDir, "shared").apply { mkdirs() }

    fun freeBytes(context: Context): Long = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrDefault(0L)

    fun totalBytes(context: Context): Long = runCatching { StatFs(context.filesDir.path).totalBytes }.getOrDefault(0L)

    fun dirSize(dir: File): Long = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun formatBytes(bytes: Long): String {
        val kb = 1024.0
        return when {
            bytes >= kb * kb * kb -> "%.1f GB".format(bytes / (kb * kb * kb))
            bytes >= kb * kb -> "%.0f MB".format(bytes / (kb * kb))
            bytes >= kb -> "%.0f KB".format(bytes / kb)
            else -> "$bytes B"
        }
    }
}
