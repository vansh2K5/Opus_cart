package com.studytimelapse.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studytimelapse.app.data.db.AppDatabase
import com.studytimelapse.app.data.db.SegmentEntity
import com.studytimelapse.app.data.db.SessionEntity
import com.studytimelapse.app.data.db.SessionStatus
import com.studytimelapse.app.data.db.SpanEntity
import com.studytimelapse.app.data.db.SyncState
import com.studytimelapse.app.data.db.TimelapseStatus
import com.studytimelapse.app.data.repository.SessionRepository
import com.studytimelapse.app.data.repository.toRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: SessionRepository
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = SessionRepository(context, db)
    }

    @After
    fun tearDown() = db.close()

    private fun session(id: String, studyMs: Long = 7_200_000, status: String = SessionStatus.FINISHED) = SessionEntity(
        id = id,
        subject = "Cybersecurity",
        startUtc = 1_000_000,
        endUtc = 1_000_000 + studyMs,
        studyMs = studyMs,
        status = status,
        captureIntervalSec = 2,
        timelapseStatus = TimelapseStatus.READY,
        timelapsePath = "/tmp/x.mp4",
        timelapseBytes = 50_000_000,
        zoneId = "UTC",
    )

    @Test
    fun sessionWithSpansRoundTrip() = runTest {
        db.sessions().upsert(session("a"))
        db.spans().replace("a", listOf(SpanEntity(sessionId = "a", startUtc = 1_000_000, endUtc = 4_600_000)))
        val rec = db.sessions().finishedWithSpans().single().toRecord()
        assertEquals(7_200_000, rec.studyMs)
        assertEquals(1, rec.spans.size)
    }

    @Test
    fun deletingTimelapseKeepsTheStudySession() = runTest {
        db.sessions().upsert(session("a"))
        repo.deleteTimelapse("a")
        val s = db.sessions().get("a")
        assertNotNull(s)
        assertEquals(TimelapseStatus.DELETED, s!!.timelapseStatus)
        assertEquals(7_200_000, s.studyMs)
        assertEquals(1, repo.records.first().size) // still counts in statistics
        assertEquals(0L, db.sessions().observeTimelapseBytes().first())
    }

    @Test
    fun deletedSessionDisappearsFromStatsAndWaitsForSync() = runTest {
        db.sessions().upsert(session("a"))
        repo.deleteSession("a")
        assertEquals(0, repo.records.first().size)
        val pending = db.sessions().pendingSync().single()
        assertEquals(SessionStatus.DELETED, pending.status)
        assertEquals(SyncState.DELETE_PENDING, pending.syncState)
    }

    @Test
    fun spansAndSegmentsCascadeWithTheSession() = runTest {
        db.sessions().upsert(session("a"))
        db.spans().insertAll(listOf(SpanEntity(sessionId = "a", startUtc = 0, endUtc = 10)))
        db.segments().insert(SegmentEntity(sessionId = "a", segmentIndex = 0, path = "/tmp/s", frames = 10, durationUs = 1))
        db.sessions().delete("a")
        assertEquals(0, db.spans().forSession("a").size)
        assertEquals(0, db.segments().forSession("a").size)
    }

    @Test
    fun unfinishedSessionIsFoundForRecovery() = runTest {
        assertNull(db.sessions().unfinished())
        db.sessions().upsert(session("live", status = SessionStatus.ACTIVE).copy(endUtc = null, runningSinceUtc = 5, runningSinceElapsed = 5))
        val u = db.sessions().unfinished()
        assertEquals("live", u?.id)
        assertEquals(5L, u?.runningSinceElapsed)
    }

    @Test
    fun syncMarkerIgnoresStaleWrites() = runTest {
        val s = session("a").copy(updatedAt = 10)
        db.sessions().upsert(s)
        db.sessions().markSynced("a", updatedAt = 9, state = SyncState.SYNCED) // edited since: stays dirty
        assertEquals(SyncState.DIRTY, db.sessions().get("a")!!.syncState)
        db.sessions().markSynced("a", updatedAt = 10, state = SyncState.SYNCED)
        assertEquals(SyncState.SYNCED, db.sessions().get("a")!!.syncState)
    }
}
