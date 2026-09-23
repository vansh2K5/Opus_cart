package com.studytimelapse.app.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.studytimelapse.app.StudyApp
import com.studytimelapse.app.data.db.TimelapseStatus
import com.studytimelapse.app.timelapse.SegmentConcatenator
import com.studytimelapse.app.util.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Joins the recorded segments into the final timelapse and makes a thumbnail.
 *
 * Runs in WorkManager so it survives the app being closed right after "Finish", never blocks
 * the UI, and is retried if the process dies mid-way. Failure only affects the video: the study
 * session was already saved before this worker was enqueued.
 */
class TimelapseProcessingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(KEY_SESSION) ?: return@withContext Result.failure()
        val container = (applicationContext as StudyApp).container
        val db = container.db
        val session = db.sessions().get(id) ?: return@withContext Result.success()
        if (session.timelapseStatus != TimelapseStatus.PROCESSING) return@withContext Result.success()

        val segments = db.segments().forSession(id).map { File(it.path) }.filter { it.exists() }
        val output = Storage.finalVideo(applicationContext, id)
        val result = try {
            SegmentConcatenator.concatenate(segments, output, session.rotationDegrees)
        } catch (e: Exception) {
            Log.e(TAG, "Processing failed for $id", e)
            null
        }
        if (result == null) {
            if (runAttemptCount < 2 && segments.isNotEmpty()) return@withContext Result.retry()
            db.sessions().upsert(
                session.copy(timelapseStatus = TimelapseStatus.FAILED, updatedAt = System.currentTimeMillis()),
            )
            return@withContext Result.success()
        }
        val thumb = Storage.thumbnail(applicationContext, id)
        val hasThumb = SegmentConcatenator.writeThumbnail(result.file, thumb)
        // The joined file is complete; segments are no longer needed.
        segments.forEach { it.delete() }
        db.segments().deleteFor(id)
        db.sessions().upsert(
            session.copy(
                timelapseStatus = TimelapseStatus.READY,
                timelapsePath = result.file.absolutePath,
                thumbnailPath = if (hasThumb) thumb.absolutePath else null,
                timelapseDurationMs = result.durationUs / 1000,
                timelapseBytes = result.file.length(),
                frameCount = result.frames,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        Result.success()
    }

    companion object {
        private const val TAG = "TimelapseWorker"
        private const val KEY_SESSION = "session"

        fun enqueue(context: Context, sessionId: String) {
            val request = OneTimeWorkRequestBuilder<TimelapseProcessingWorker>()
                .setInputData(workDataOf(KEY_SESSION to sessionId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("timelapse-$sessionId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
