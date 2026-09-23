package com.studytimelapse.app.data.repository

import android.content.Context
import com.studytimelapse.app.data.db.AppDatabase
import com.studytimelapse.app.data.db.SessionEntity
import com.studytimelapse.app.data.db.SessionStatus
import com.studytimelapse.app.data.db.SessionWithSpans
import com.studytimelapse.app.data.db.SyncState
import com.studytimelapse.app.data.db.TimelapseStatus
import com.studytimelapse.app.domain.StudyRecord
import com.studytimelapse.app.domain.StudySpan
import com.studytimelapse.app.util.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/** Finished-session history and timelapse file management. */
class SessionRepository(private val context: Context, private val db: AppDatabase) {

    val finishedWithSpans: Flow<List<SessionWithSpans>> = db.sessions().observeFinishedWithSpans()

    val records: Flow<List<StudyRecord>> = finishedWithSpans.map { list -> list.map { it.toRecord() } }

    val timelapses: Flow<List<SessionEntity>> = db.sessions().observeTimelapses()

    val timelapseBytes: Flow<Long> = db.sessions().observeTimelapseBytes()

    fun observe(id: String): Flow<SessionEntity?> = db.sessions().observe(id)

    suspend fun get(id: String): SessionEntity? = db.sessions().get(id)

    suspend fun allRecords(): List<StudyRecord> = db.sessions().finishedWithSpans().map { it.toRecord() }

    suspend fun saveNotes(id: String, notes: String, title: String?) =
        db.sessions().updateNotes(id, notes.take(4000), title?.take(80)?.ifBlank { null }, System.currentTimeMillis())

    /**
     * Deletes only the video. The study session stays in statistics, streaks and the leaderboard.
     */
    suspend fun deleteTimelapse(id: String) = withContext(Dispatchers.IO) {
        val s = db.sessions().get(id) ?: return@withContext
        File(Storage.timelapsesRoot(context), id).deleteRecursively()
        db.segments().deleteFor(id)
        db.sessions().upsert(
            s.copy(
                timelapseStatus = TimelapseStatus.DELETED,
                timelapsePath = null,
                thumbnailPath = null,
                timelapseBytes = 0,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteAllTimelapses() = withContext(Dispatchers.IO) {
        for (s in db.sessions().withVideo()) deleteTimelapse(s.id)
    }

    /** Removes a whole session (e.g. started by accident). Synced to the server as a deletion. */
    suspend fun deleteSession(id: String) = withContext(Dispatchers.IO) {
        val s = db.sessions().get(id) ?: return@withContext
        File(Storage.timelapsesRoot(context), id).deleteRecursively()
        db.segments().deleteFor(id)
        db.spans().deleteFor(id)
        db.sessions().upsert(
            s.copy(
                status = SessionStatus.DELETED,
                timelapseStatus = TimelapseStatus.DELETED,
                timelapsePath = null,
                thumbnailPath = null,
                timelapseBytes = 0,
                notes = "",
                syncState = SyncState.DELETE_PENDING,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** "Delete all local data": every session, video, goal and achievement on this device. */
    suspend fun deleteAllLocal() = withContext(Dispatchers.IO) {
        Storage.timelapsesRoot(context).deleteRecursively()
        db.clearAllTables()
    }
}

fun SessionWithSpans.toRecord(): StudyRecord = StudyRecord(
    id = session.id,
    subject = session.subject,
    startUtc = session.startUtc,
    endUtc = session.endUtc ?: session.lastCheckpointUtc,
    studyMs = session.studyMs,
    spans = spans.map { StudySpan(it.startUtc, it.endUtc) },
    hasTimelapse = session.timelapseStatus == TimelapseStatus.READY,
)
