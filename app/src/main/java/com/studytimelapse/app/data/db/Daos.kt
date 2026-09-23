package com.studytimelapse.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observe(id: String): Flow<SessionEntity?>

    @Transaction
    @Query("SELECT * FROM sessions WHERE status = 'FINISHED' ORDER BY startUtc DESC")
    fun observeFinishedWithSpans(): Flow<List<SessionWithSpans>>

    @Transaction
    @Query("SELECT * FROM sessions WHERE status = 'FINISHED' ORDER BY startUtc DESC")
    suspend fun finishedWithSpans(): List<SessionWithSpans>

    @Query("SELECT * FROM sessions WHERE status = 'FINISHED' ORDER BY startUtc DESC")
    suspend fun finished(): List<SessionEntity>

    @Query(
        "SELECT * FROM sessions WHERE status = 'FINISHED' AND timelapseStatus IN ('READY','PROCESSING','FAILED') " +
            "ORDER BY startUtc DESC",
    )
    fun observeTimelapses(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE status IN ('ACTIVE','INTERRUPTED') ORDER BY startUtc DESC LIMIT 1")
    suspend fun unfinished(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE status IN ('ACTIVE','INTERRUPTED') ORDER BY startUtc DESC LIMIT 1")
    fun observeUnfinished(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE timelapseStatus = 'PROCESSING' AND status = 'FINISHED'")
    suspend fun processing(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE syncState != 0 AND status IN ('FINISHED','DELETED')")
    suspend fun pendingSync(): List<SessionEntity>

    @Query("UPDATE sessions SET syncState = :state WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long, state: Int)

    @Query("UPDATE sessions SET notes = :notes, title = :title, updatedAt = :now, syncState = 1 WHERE id = :id")
    suspend fun updateNotes(id: String, notes: String, title: String?, now: Long)

    @Query("SELECT COALESCE(SUM(timelapseBytes), 0) FROM sessions WHERE timelapseStatus = 'READY'")
    fun observeTimelapseBytes(): Flow<Long>

    @Query("SELECT * FROM sessions WHERE timelapseStatus = 'READY'")
    suspend fun withVideo(): List<SessionEntity>

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()

    /** Local-only mode: soft-deleted sessions have nothing to sync, so remove them for real. */
    @Query("DELETE FROM sessions WHERE status = 'DELETED'")
    suspend fun purgeDeleted()
}

@Dao
abstract class SpanDao {
    @Insert
    abstract suspend fun insertAll(spans: List<SpanEntity>)

    @Query("DELETE FROM study_spans WHERE sessionId = :sessionId")
    abstract suspend fun deleteFor(sessionId: String)

    @Query("SELECT * FROM study_spans WHERE sessionId = :sessionId ORDER BY startUtc")
    abstract suspend fun forSession(sessionId: String): List<SpanEntity>

    @Transaction
    open suspend fun replace(sessionId: String, spans: List<SpanEntity>) {
        deleteFor(sessionId)
        insertAll(spans)
    }
}

@Dao
interface SegmentDao {
    @Insert
    suspend fun insert(segment: SegmentEntity)

    @Query("SELECT * FROM timelapse_segments WHERE sessionId = :sessionId ORDER BY segmentIndex")
    suspend fun forSession(sessionId: String): List<SegmentEntity>

    @Query("DELETE FROM timelapse_segments WHERE sessionId = :sessionId")
    suspend fun deleteFor(sessionId: String)
}

@Dao
interface SubjectDao {
    @Query("SELECT * FROM subjects WHERE archived = 0 ORDER BY lastUsedUtc DESC, name")
    fun observeAll(): Flow<List<SubjectEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(subject: SubjectEntity): Long

    @Query("UPDATE subjects SET lastUsedUtc = :now, archived = 0 WHERE name = :name")
    suspend fun touch(name: String, now: Long)

    @Query("UPDATE subjects SET archived = 1 WHERE id = :id")
    suspend fun archive(id: Long)

    @Query("SELECT COUNT(*) FROM subjects")
    suspend fun count(): Int
}

@Dao
interface GoalDao {
    @Query("SELECT * FROM goals WHERE active = 1 ORDER BY CASE period WHEN 'DAY' THEN 0 WHEN 'WEEK' THEN 1 ELSE 2 END, id")
    fun observeActive(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals WHERE active = 1")
    suspend fun active(): List<GoalEntity>

    @Upsert
    suspend fun upsert(goal: GoalEntity): Long

    @Query("UPDATE goals SET active = 0 WHERE id = :id")
    suspend fun deactivate(id: Long)
}

@Dao
interface AchievementDao {
    @Query("SELECT * FROM achievements ORDER BY unlockedAt DESC")
    fun observeAll(): Flow<List<AchievementEntity>>

    @Query("SELECT type FROM achievements")
    suspend fun unlockedTypes(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<AchievementEntity>)

    @Query("DELETE FROM achievements")
    suspend fun deleteAll()
}
