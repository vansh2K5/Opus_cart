package com.studytimelapse.app.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

object SessionStatus {
    /** Timer is live (running or paused) inside the foreground service. */
    const val ACTIVE = "ACTIVE"
    /** The process died mid-session; waiting for the user to resume or finish it. */
    const val INTERRUPTED = "INTERRUPTED"
    const val FINISHED = "FINISHED"
    /** Deleted by the user; kept only until the deletion has been synced to the server. */
    const val DELETED = "DELETED"
}

object TimelapseStatus {
    const val NONE = "NONE"
    const val RECORDING = "RECORDING"
    const val PROCESSING = "PROCESSING"
    const val READY = "READY"
    const val FAILED = "FAILED"
    /** Video deleted by the user; the study session itself stays in statistics. */
    const val DELETED = "DELETED"
}

object SyncState {
    const val SYNCED = 0
    const val DIRTY = 1
    const val DELETE_PENDING = 2
}

/** One study session. Timestamps are UTC epoch millis. The video file lives outside the DB. */
@Entity(tableName = "sessions", indices = [Index("startUtc"), Index("status")])
data class SessionEntity(
    @PrimaryKey val id: String,
    val subject: String,
    val title: String? = null,
    val startUtc: Long,
    val endUtc: Long? = null,
    val studyMs: Long = 0,
    val pausedMs: Long = 0,
    val pauseCount: Int = 0,
    val targetMs: Long? = null,
    val status: String = SessionStatus.ACTIVE,
    /** Seconds between captured frames, or null when the session had no timelapse. */
    val captureIntervalSec: Int? = null,
    val timelapseStatus: String = TimelapseStatus.NONE,
    val timelapsePath: String? = null,
    val thumbnailPath: String? = null,
    val timelapseDurationMs: Long = 0,
    val timelapseBytes: Long = 0,
    val frameCount: Int = 0,
    val framesScreenOff: Int = 0,
    /** Times the camera stopped delivering frames during the session (reported honestly). */
    val captureGaps: Int = 0,
    /** Display rotation (degrees) the capture was set up for; used to restart the camera. */
    val rotationDegrees: Int = 0,
    val notes: String = "",
    /** Zone at session start (informational; stats use the user's configured zone). */
    val zoneId: String,
    /** Persisted stopwatch state so a running timer survives process death (null = not running). */
    val runningSinceUtc: Long? = null,
    val runningSinceElapsed: Long? = null,
    /** Settings.Global.BOOT_COUNT when the span started; a reboot invalidates runningSinceElapsed. */
    val bootCount: Int = -1,
    val lastCheckpointUtc: Long = startUtc,
    val createdAt: Long = startUtc,
    val updatedAt: Long = startUtc,
    val syncState: Int = SyncState.DIRTY,
)

@Entity(
    tableName = "study_spans",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class SpanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val startUtc: Long,
    val endUtc: Long,
)

@Entity(
    tableName = "timelapse_segments",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val segmentIndex: Int,
    val path: String,
    val frames: Int,
    val durationUs: Long,
)

@Entity(tableName = "subjects", indices = [Index(value = ["name"], unique = true)])
data class SubjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lastUsedUtc: Long = 0,
    val archived: Boolean = false,
)

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** DAY, WEEK or MONTH ([com.studytimelapse.app.domain.GoalPeriod]). */
    val period: String,
    val targetMinutes: Int,
    val createdAt: Long,
    val active: Boolean = true,
)

@Entity(tableName = "achievements")
data class AchievementEntity(
    @PrimaryKey val type: String,
    val unlockedAt: Long,
    val synced: Boolean = false,
)

data class SessionWithSpans(
    @Embedded val session: SessionEntity,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val spans: List<SpanEntity>,
)
