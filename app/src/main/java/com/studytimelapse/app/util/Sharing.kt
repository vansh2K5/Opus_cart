package com.studytimelapse.app.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.studytimelapse.app.R
import com.studytimelapse.app.data.db.SessionEntity
import com.studytimelapse.app.domain.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Share cards, video sharing, gallery export and study-history export. Nothing is automatic. */
object Sharing {

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Opens the Android share sheet for a timelapse (explicit user action only). */
    fun shareVideo(context: Context, file: File) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Share timelapse").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Copies a timelapse into the public Movies/StudyTimelapse folder (no permission on Android 10+). */
    suspend fun saveVideoToGallery(context: Context, file: File, displayName: String): Boolean = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$displayName.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/StudyTimelapse")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("no stream")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }

    /** Renders the "STUDY SESSION" card as a PNG and opens the share sheet. */
    suspend fun shareSessionCard(context: Context, session: SessionEntity, streak: Int, zone: ZoneId) {
        val file = withContext(Dispatchers.Default) {
            val bmp = renderCard(context, session, streak, zone)
            val f = File(Storage.sharedDir(context), "study-session.png")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            f
        }
        val intent = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Share session").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun renderCard(context: Context, s: SessionEntity, streak: Int, zone: ZoneId): Bitmap {
        val w = 1080
        val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val typeface = runCatching { ResourcesCompat.getFont(context, R.font.inter_variable) }.getOrNull() ?: Typeface.DEFAULT
        val bold = Typeface.create(typeface, 600, false)
        c.drawColor(0xFF1C1916.toInt())
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFAF8F4.toInt() }
        c.drawRoundRect(RectF(80f, 80f, w - 80f, h - 80f), 72f, 72f, card)
        fun text(str: String, x: Float, y: Float, size: Float, color: Int, tf: Typeface = typeface, spacing: Float = 0f) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = size
                this.typeface = tf
                letterSpacing = spacing
            }
            c.drawText(str, x, y, p)
        }
        val ink = 0xFF1D1B19.toInt()
        val slate = 0xFF6F6A63.toInt()
        val accent = 0xFFB64400.toInt()
        text("STUDY SESSION", 160f, 260f, 40f, slate, bold, 0.12f)
        text(s.subject.take(26), 160f, 420f, 72f, ink, bold, -0.01f)
        s.title?.let { text(it.take(34), 160f, 490f, 40f, slate) }
        text(Format.duration(s.studyMs), 160f, 760f, 200f, ink, bold, -0.03f)
        if (streak > 0) text("🔥 $streak-day streak", 160f, 900f, 52f, accent, bold)
        val date = Instant.ofEpochMilli(s.startUtc).atZone(zone).format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
        text(date, 160f, 1130f, 44f, slate)
        text("StudyTimelapse", 160f, 1200f, 32f, slate, bold)
        return bmp
    }

    // ------------------------------------------------------------------ export

    private val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun writeCsv(sessions: List<SessionEntity>, zone: ZoneId, out: OutputStream) {
        out.bufferedWriter().use { w ->
            w.appendLine("date,start,end,subject,title,study_minutes,paused_minutes,pauses,target_minutes,frames,notes")
            for (s in sessions.sortedBy { it.startUtc }) {
                val start = Instant.ofEpochMilli(s.startUtc).atZone(zone)
                val end = s.endUtc?.let { Instant.ofEpochMilli(it).atZone(zone) }
                w.appendLine(
                    listOf(
                        start.toLocalDate().toString(),
                        start.format(iso),
                        end?.format(iso) ?: "",
                        s.subject,
                        s.title ?: "",
                        "%.1f".format(s.studyMs / 60_000.0),
                        "%.1f".format(s.pausedMs / 60_000.0),
                        s.pauseCount.toString(),
                        s.targetMs?.let { (it / 60_000).toString() } ?: "",
                        s.frameCount.toString(),
                        s.notes,
                    ).joinToString(",") { csv(it) },
                )
            }
        }
    }

    fun writeJson(sessions: List<SessionEntity>, zone: ZoneId, out: OutputStream) {
        val arr = JSONArray()
        for (s in sessions.sortedBy { it.startUtc }) {
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("date", Instant.ofEpochMilli(s.startUtc).atZone(zone).toLocalDate().toString())
                    .put("start", Instant.ofEpochMilli(s.startUtc).atZone(zone).format(iso))
                    .put("end", s.endUtc?.let { Instant.ofEpochMilli(it).atZone(zone).format(iso) } ?: JSONObject.NULL)
                    .put("subject", s.subject)
                    .put("title", s.title ?: JSONObject.NULL)
                    .put("studyMinutes", s.studyMs / 60_000.0)
                    .put("pausedMinutes", s.pausedMs / 60_000.0)
                    .put("pauses", s.pauseCount)
                    .put("targetMinutes", s.targetMs?.let { it / 60_000 } ?: JSONObject.NULL)
                    .put("frames", s.frameCount)
                    .put("notes", s.notes),
            )
        }
        out.bufferedWriter().use { it.write(JSONObject().put("exportedAt", Instant.now().toString()).put("sessions", arr).toString(2)) }
    }

    private fun csv(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}
