package com.studytimelapse.app.service

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.studytimelapse.app.data.db.AppDatabase
import com.studytimelapse.app.data.db.SegmentEntity
import com.studytimelapse.app.data.db.SessionEntity
import com.studytimelapse.app.data.db.SessionStatus
import com.studytimelapse.app.data.db.SpanEntity
import com.studytimelapse.app.data.db.TimelapseStatus
import com.studytimelapse.app.data.prefs.CaptureResolution
import com.studytimelapse.app.data.prefs.SettingsRepository
import com.studytimelapse.app.data.prefs.StudyScreenMode
import com.studytimelapse.app.data.repository.AchievementRepository
import com.studytimelapse.app.data.repository.SessionRepository
import com.studytimelapse.app.data.repository.SubjectRepository
import com.studytimelapse.app.domain.Format
import com.studytimelapse.app.domain.GoalPeriod
import com.studytimelapse.app.domain.SessionClock
import com.studytimelapse.app.domain.StatsCalculator
import com.studytimelapse.app.domain.StudySpan
import com.studytimelapse.app.notifications.Notifier
import com.studytimelapse.app.timelapse.TimelapseEncoder
import com.studytimelapse.app.util.Storage
import com.studytimelapse.app.worker.SyncWorker
import com.studytimelapse.app.worker.TimelapseProcessingWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class StartRequest(
    val subject: String,
    val title: String?,
    val targetMinutes: Int?,
    val timelapse: Boolean,
    val intervalSec: Int,
    val resolution: CaptureResolution,
    val bitrate: Int,
    val backCamera: Boolean,
    val targetRotation: Int,
    val screenMode: StudyScreenMode,
)

/** Things worth telling the user after a session (shown on the summary screen too). */
data class SessionOutcome(
    val sessionId: String,
    val dailyGoalReached: Boolean,
    val streak: Int,
    val streakIncreased: Boolean,
    val newAchievements: List<String>,
)

/**
 * Owns the active study session.
 *
 * The **timer** lives here, in-process, and is persisted to Room every 30 seconds and on every
 * state change, so it survives the app being killed. The **camera** lives in
 * [StudySessionService] and is optional: if it fails, the study time is unaffected and the gap is
 * reported honestly. This separation is what guarantees "a failed timelapse never loses the study
 * session".
 */
class SessionManager(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val sessions: SessionRepository,
    private val subjects: SubjectRepository,
    private val achievements: AchievementRepository,
    private val notifier: Notifier,
    private val scope: CoroutineScope,
    /** Publishes "studying now" to the friend (no-op when offline / signed out). */
    private val publishLive: suspend (subject: String?, startedAtUtc: Long?, targetMinutes: Int?) -> Unit,
) {
    private val mutex = Mutex()
    private var clock: SessionClock? = null
    private var entity: SessionEntity? = null
    private var host: CameraHost? = null
    private var checkpointJob: Job? = null
    private var captureConfig: CaptureConfig? = null

    private val frames = AtomicInteger(0)
    private val framesScreenOff = AtomicInteger(0)
    private val gaps = AtomicInteger(0)

    private val _live = MutableStateFlow<LiveSession?>(null)
    val live: StateFlow<LiveSession?> = _live.asStateFlow()

    private val _test = MutableStateFlow<ScreenOffTest?>(null)
    val screenOffTest: StateFlow<ScreenOffTest?> = _test.asStateFlow()

    private val _lastOutcome = MutableStateFlow<SessionOutcome?>(null)
    val lastOutcome: StateFlow<SessionOutcome?> = _lastOutcome.asStateFlow()

    private val elapsed = { SystemClock.elapsedRealtime() }
    private val wall = { System.currentTimeMillis() }

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    /** Called once at app start: rebuilds a session that was active when the process died. */
    fun restore() {
        scope.launch {
            mutex.withLock {
                if (clock != null) return@withLock
                val e = db.sessions().unfinished() ?: return@withLock
                val spans = db.spans().forSession(e.id).map { StudySpan(it.startUtc, it.endUtc) }
                val sinceUtc = e.runningSinceUtc
                val sinceElapsed = e.runningSinceElapsed
                val rebooted = e.bootCount != bootCount() || (sinceElapsed != null && sinceElapsed > elapsed())
                val c = SessionClock(elapsed, wall)
                var interrupted = e.status == SessionStatus.INTERRUPTED
                if (sinceUtc != null && sinceElapsed != null && !rebooted) {
                    // Same boot: the monotonic clock is still valid, continue like a stopwatch.
                    c.restore(e.startUtc, spans, e.pauseCount, sinceUtc, sinceElapsed)
                } else if (sinceUtc != null) {
                    // After a reboot we only know the user studied until the last checkpoint.
                    val upTo = maxOf(sinceUtc, e.lastCheckpointUtc)
                    c.restore(e.startUtc, spans + StudySpan(sinceUtc, upTo), e.pauseCount + 1)
                    interrupted = true
                } else {
                    c.restore(e.startUtc, spans, e.pauseCount)
                }
                clock = c
                frames.set(e.frameCount)
                framesScreenOff.set(e.framesScreenOff)
                val hasTimelapse = e.captureIntervalSec != null
                gaps.set(e.captureGaps)
                // The camera died with the process while the timer kept going: that is a gap.
                if (hasTimelapse && c.state == SessionClock.State.RUNNING) gaps.incrementAndGet()
                entity = e.copy(status = if (interrupted) SessionStatus.INTERRUPTED else SessionStatus.ACTIVE)
                val s = settings.current()
                captureConfig = if (hasTimelapse) configFor(e.id, e.captureIntervalSec!!, s.effectiveResolution, s.bitrate(), s.cameraFacing.name == "BACK", e.rotationDegrees.toSurfaceRotation(), isTest = false) else null
                publish(
                    camera = if (hasTimelapse) CameraStatus.NOT_RUNNING else CameraStatus.OFF,
                    cameraMessage = if (hasTimelapse) "The app was closed by Android, so the camera stopped. Tap to restart recording." else null,
                    interrupted = interrupted,
                    screenMode = s.screenMode,
                )
                persistLocked()
                startCheckpoints()
                refreshNotification()
            }
        }
    }

    suspend fun start(req: StartRequest): String = mutex.withLock {
        check(clock == null) { "A session is already active" }
        val id = UUID.randomUUID().toString()
        val c = SessionClock(elapsed, wall).also { it.start() }
        clock = c
        frames.set(0)
        framesScreenOff.set(0)
        gaps.set(0)
        val rotation = req.targetRotation.toDegrees()
        entity = SessionEntity(
            id = id,
            subject = req.subject.ifBlank { "Study" },
            title = req.title?.ifBlank { null },
            startUtc = c.sessionStartUtc,
            targetMs = req.targetMinutes?.let { it * 60_000L },
            status = SessionStatus.ACTIVE,
            captureIntervalSec = if (req.timelapse) req.intervalSec else null,
            timelapseStatus = if (req.timelapse) TimelapseStatus.RECORDING else TimelapseStatus.NONE,
            rotationDegrees = rotation,
            zoneId = ZoneId.systemDefault().id,
            bootCount = bootCount(),
        )
        captureConfig = if (req.timelapse) {
            configFor(id, req.intervalSec, req.resolution, req.bitrate, req.backCamera, req.targetRotation, isTest = false)
        } else null
        publish(
            camera = if (req.timelapse) CameraStatus.STARTING else CameraStatus.OFF,
            screenMode = req.screenMode,
        )
        persistLocked()
        subjects.touch(req.subject)
        captureConfig?.let { startCaptureService(it) }
        startCheckpoints()
        refreshNotification()
        scope.launch { runCatching { publishLive(req.subject, c.sessionStartUtc, req.targetMinutes) } }
        id
    }

    suspend fun pause() = mutex.withLock {
        val c = clock ?: return@withLock
        c.pause()
        host?.pauseCapture()
        publish(camera = if (captureConfig != null) CameraStatus.PAUSED else CameraStatus.OFF)
        persistLocked()
        refreshNotification()
    }

    suspend fun resume() = mutex.withLock {
        val c = clock ?: return@withLock
        c.resume()
        entity = entity?.copy(status = SessionStatus.ACTIVE, bootCount = bootCount())
        val cfg = captureConfig
        val h = host
        val camera = when {
            cfg == null -> CameraStatus.OFF
            h != null -> {
                h.resumeCapture()
                CameraStatus.STARTING
            }
            else -> {
                startCaptureService(cfg.copy(firstSegmentIndex = nextSegmentIndex(cfg.sessionId)))
                CameraStatus.STARTING
            }
        }
        publish(camera = camera, cameraMessage = null, interrupted = false)
        persistLocked()
        refreshNotification()
    }

    /** Restarts the camera for a running session whose camera died (e.g. after process death). */
    suspend fun restartCamera() = mutex.withLock {
        val cfg = captureConfig ?: return@withLock
        if (clock?.state != SessionClock.State.RUNNING) return@withLock
        val h = host
        if (h != null) {
            // Service alive but the camera errored or stalled: close and reopen it.
            h.pauseCapture()
            h.resumeCapture()
        } else {
            startCaptureService(cfg.copy(firstSegmentIndex = nextSegmentIndex(cfg.sessionId)))
        }
        publish(camera = CameraStatus.STARTING, cameraMessage = null)
    }

    /**
     * Finishes the session. Study time is saved immediately; the video is finalised in the
     * background and the UI observes [SessionEntity.timelapseStatus].
     */
    suspend fun finish(): String? = mutex.withLock {
        val c = clock ?: return@withLock null
        val e = entity ?: return@withLock null
        val spans = c.finish()
        val end = wall()
        val study = c.studyMs()
        val hasTimelapse = e.captureIntervalSec != null
        val finished = e.copy(
            endUtc = end,
            studyMs = study,
            pausedMs = (end - e.startUtc - study).coerceAtLeast(0),
            pauseCount = c.pauseCount,
            status = SessionStatus.FINISHED,
            timelapseStatus = if (hasTimelapse) TimelapseStatus.PROCESSING else TimelapseStatus.NONE,
            frameCount = frames.get(),
            framesScreenOff = framesScreenOff.get(),
            captureGaps = gaps.get(),
            runningSinceUtc = null,
            runningSinceElapsed = null,
            lastCheckpointUtc = end,
            updatedAt = end,
        )
        db.sessions().upsert(finished)
        db.spans().replace(e.id, spans.map { SpanEntity(sessionId = e.id, startUtc = it.startUtc, endUtc = it.endUtc) })

        val h = host
        if (h != null) {
            h.finishCapture() // service enqueues processing once segments are closed
        } else if (hasTimelapse) {
            TimelapseProcessingWorker.enqueue(context, e.id)
        }
        clearLocked()
        if (h == null) notifier.cancelSession()
        scope.launch { runCatching { publishLive(null, null, null) } }
        scope.launch { afterFinish(e.id) }
        SyncWorker.enqueue(context)
        e.id
    }

    /** Throws away the active session entirely (e.g. started by accident). */
    suspend fun discard() = mutex.withLock {
        val e = entity ?: return@withLock
        host?.abortCapture()
        clearLocked()
        notifier.cancelSession()
        db.sessions().delete(e.id)
        Storage.sessionDir(context, e.id).deleteRecursively()
        scope.launch { runCatching { publishLive(null, null, null) } }
    }

    private fun clearLocked() {
        checkpointJob?.cancel()
        checkpointJob = null
        clock = null
        entity = null
        captureConfig = null
        _live.value = null
    }

    // ---------------------------------------------------------------------------------------
    // Screen-off capability test
    // ---------------------------------------------------------------------------------------

    fun startScreenOffTest() {
        if (clock != null || _test.value?.running == true) return
        Storage.testDir(context).deleteRecursively()
        _test.value = ScreenOffTest(running = true)
        startCaptureService(
            CaptureConfig(
                sessionId = TEST_SESSION_ID, intervalSec = 1, longEdge = 640, shortEdge = 480,
                bitrate = 1_000_000, backCamera = true, targetRotation = 0, firstSegmentIndex = 0, isTest = true,
            ),
        )
    }

    fun stopScreenOffTest() {
        val t = _test.value ?: return
        if (host?.sessionId == TEST_SESSION_ID) host?.abortCapture()
        // Expect roughly one frame per second the screen was off; accept 60% for timing jitter.
        val expected = t.screenOffMs / 1000
        val works = when {
            t.screenOffMs < 8_000 -> null
            else -> t.framesScreenOff >= expected * 0.6
        }
        _test.value = t.copy(running = false, works = works)
        if (works != null) {
            scope.launch { settings.update { it.copy(screenOffCapability = if (works) 1 else 0) } }
        }
        Storage.testDir(context).deleteRecursively()
    }

    fun clearScreenOffTest() {
        _test.value = null
    }

    // ---------------------------------------------------------------------------------------
    // Callbacks from the capture service (any thread)
    // ---------------------------------------------------------------------------------------

    fun attachHost(h: CameraHost) {
        host = h
    }

    fun detachHost(h: CameraHost) {
        if (host === h) host = null
    }

    fun onFrameCaptured(sessionId: String, screenOff: Boolean) {
        if (sessionId == TEST_SESSION_ID) {
            _test.update { it?.copy(frames = it.frames + 1, framesScreenOff = it.framesScreenOff + if (screenOff) 1 else 0, cameraStatus = CameraStatus.RECORDING) }
            return
        }
        val total = frames.incrementAndGet()
        val off = if (screenOff) framesScreenOff.incrementAndGet() else framesScreenOff.get()
        _live.update {
            it?.copy(
                frames = total,
                framesScreenOff = off,
                lastFrameElapsed = elapsed(),
                camera = if (it.running) CameraStatus.RECORDING else it.camera,
                cameraMessage = null,
            )
        }
        if (screenOff && total % 30 == 0) {
            scope.launch { if (settings.current().screenOffCapability != 1) settings.update { it.copy(screenOffCapability = 1) } }
        }
    }

    fun onScreenOffTime(sessionId: String, addMs: Long) {
        if (sessionId == TEST_SESSION_ID) _test.update { it?.copy(screenOffMs = it.screenOffMs + addMs) }
    }

    /** Camera problem or recovery. [gap] counts a new interruption of the timelapse. */
    fun onCameraStatus(sessionId: String, status: CameraStatus, message: String?, gap: Boolean = false) {
        if (sessionId == TEST_SESSION_ID) {
            _test.update { it?.copy(cameraStatus = status) }
            return
        }
        if (gap) gaps.incrementAndGet()
        _live.update { it?.copy(camera = status, cameraMessage = message, gaps = gaps.get()) }
        refreshNotification()
    }

    fun onWarning(sessionId: String, warning: String?) {
        if (sessionId != TEST_SESSION_ID) _live.update { it?.copy(warning = warning) }
    }

    fun onIntervalChanged(sessionId: String, intervalSec: Int) {
        if (sessionId == TEST_SESSION_ID) return
        _live.update { it?.copy(intervalSec = intervalSec) }
        captureConfig = captureConfig?.copy(intervalSec = intervalSec)
    }

    /** Called on the capture thread each time a segment file is safely closed. */
    fun onSegmentFinished(sessionId: String, segment: TimelapseEncoder.Segment) {
        if (sessionId == TEST_SESSION_ID) return
        runBlocking {
            db.segments().insert(
                SegmentEntity(
                    sessionId = sessionId,
                    segmentIndex = segment.index,
                    path = segment.file.absolutePath,
                    frames = segment.frames,
                    durationUs = segment.durationUs,
                ),
            )
        }
    }

    /** All segments are closed; the service is about to stop. */
    fun onCaptureFinished(sessionId: String) {
        if (sessionId == TEST_SESSION_ID) return
        TimelapseProcessingWorker.enqueue(context, sessionId)
    }

    /** The service died or stopped without being asked (camera fatal error, system kill). */
    fun onCaptureStopped(sessionId: String, reason: String) {
        if (sessionId == TEST_SESSION_ID || _live.value?.sessionId != sessionId) return
        gaps.incrementAndGet()
        _live.update { it?.copy(camera = CameraStatus.ERROR, cameraMessage = reason, gaps = gaps.get()) }
        notifier.alert("Timelapse stopped", "$reason Your study timer is still running.")
        refreshNotification()
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    private fun startCaptureService(cfg: CaptureConfig) {
        val granted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            if (cfg.isTest) {
                _test.update { it?.copy(running = false, cameraStatus = CameraStatus.ERROR) }
            } else {
                _live.update { it?.copy(camera = CameraStatus.ERROR, cameraMessage = "Camera permission is off. Your timer still runs.") }
            }
            return
        }
        val intent = cfg.writeTo(Intent(context, StudySessionService::class.java).setAction(StudySessionService.ACTION_START_CAPTURE))
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            // Android refuses to start a camera service from the background (e.g. while the app
            // is not visible). Report it; the user can restart the camera from the app.
            Log.w(TAG, "Could not start capture service", e)
            if (cfg.isTest) {
                _test.update { it?.copy(running = false, cameraStatus = CameraStatus.ERROR) }
            } else {
                _live.update { it?.copy(camera = CameraStatus.NOT_RUNNING, cameraMessage = "Open the app to start the camera.") }
            }
        }
    }

    private suspend fun nextSegmentIndex(sessionId: String): Int =
        (db.segments().forSession(sessionId).maxOfOrNull { it.segmentIndex } ?: -1) + 1

    private fun configFor(
        id: String, interval: Int, res: CaptureResolution, bitrate: Int, back: Boolean, rotation: Int, isTest: Boolean,
    ) = CaptureConfig(id, interval, res.longEdge, res.shortEdge, bitrate, back, rotation, 0, isTest)

    private fun publish(
        camera: CameraStatus? = null,
        cameraMessage: String? = _live.value?.cameraMessage,
        interrupted: Boolean? = null,
        screenMode: StudyScreenMode? = null,
    ) {
        val c = clock ?: return
        val e = entity ?: return
        val prev = _live.value
        _live.value = LiveSession(
            sessionId = e.id,
            subject = e.subject,
            title = e.title,
            targetMs = e.targetMs,
            startUtc = e.startUtc,
            running = c.state == SessionClock.State.RUNNING,
            studyMsAtSnapshot = c.studyMs(),
            snapshotElapsed = elapsed(),
            pauseCount = c.pauseCount,
            timelapse = e.captureIntervalSec != null,
            intervalSec = captureConfig?.intervalSec ?: e.captureIntervalSec ?: 0,
            camera = camera ?: prev?.camera ?: CameraStatus.OFF,
            cameraMessage = cameraMessage,
            frames = frames.get(),
            framesScreenOff = framesScreenOff.get(),
            lastFrameElapsed = prev?.lastFrameElapsed ?: 0,
            gaps = gaps.get(),
            warning = prev?.warning,
            screenMode = screenMode ?: prev?.screenMode ?: StudyScreenMode.DIM,
            interrupted = interrupted ?: prev?.interrupted ?: false,
        )
    }

    private suspend fun persistLocked() {
        val c = clock ?: return
        val e = entity ?: return
        val since = c.runningSince()
        val now = wall()
        val updated = e.copy(
            studyMs = c.studyMs(),
            pauseCount = c.pauseCount,
            runningSinceUtc = since?.first,
            runningSinceElapsed = since?.second,
            frameCount = frames.get(),
            framesScreenOff = framesScreenOff.get(),
            captureGaps = gaps.get(),
            lastCheckpointUtc = now,
            updatedAt = now,
        )
        entity = updated
        db.sessions().upsert(updated)
        db.spans().replace(e.id, c.closedSpans().map { SpanEntity(sessionId = e.id, startUtc = it.startUtc, endUtc = it.endUtc) })
    }

    private fun startCheckpoints() {
        checkpointJob?.cancel()
        checkpointJob = scope.launch {
            while (isActive) {
                delay(CHECKPOINT_MS)
                mutex.withLock { persistLocked() }
            }
        }
    }

    private fun refreshNotification() {
        val l = _live.value ?: return
        val status = when {
            !l.timelapse -> "Studying"
            l.camera == CameraStatus.RECORDING -> "● Timelapse recording · ${Format.thousands(l.frames)} frames"
            else -> l.camera.label
        }
        notifier.postSession(
            notifier.sessionNotification(l.subject, l.running, l.studyMsAt(elapsed()), status),
        )
    }

    /** Goal / streak / achievement feedback after a session. Quiet unless something happened. */
    private suspend fun afterFinish(sessionId: String) {
        try {
            val s = settings.current()
            val zone = s.zone
            val today = LocalDate.now(zone)
            val all = sessions.allRecords()
            val before = all.filter { it.id != sessionId }
            val dailyBefore = StatsCalculator.dailyTotals(before, zone)
            val dailyAfter = StatsCalculator.dailyTotals(all, zone)
            val streakBefore = StatsCalculator.streak(dailyBefore, today, s.streakThresholdMs)
            val streakAfter = StatsCalculator.streak(dailyAfter, today, s.streakThresholdMs)
            if (streakAfter.longest > s.longestStreakEver) settings.update { it.copy(longestStreakEver = streakAfter.longest) }

            val dayGoal = db.goals().active().firstOrNull { it.period == GoalPeriod.DAY.name }
            val goalReached = dayGoal != null &&
                (dailyBefore[today] ?: 0) < dayGoal.targetMinutes * 60_000L &&
                (dailyAfter[today] ?: 0) >= dayGoal.targetMinutes * 60_000L
            val fresh = achievements.evaluate(all, zone, today, s.streakThresholdMs)
            val streakUp = streakAfter.current > streakBefore.current

            _lastOutcome.value = SessionOutcome(sessionId, goalReached, streakAfter.current, streakUp, fresh.map { it.title })

            when {
                goalReached && s.notifyGoals -> notifier.progress("Daily goal complete 🎉", "You studied ${Format.duration(dailyAfter[today] ?: 0)} today.")
                streakUp && s.notifyStreaks && streakAfter.current in STREAK_MILESTONES ->
                    notifier.progress("${streakAfter.current}-day streak 🔥", "Consistency beats intensity. See you tomorrow.")
                fresh.isNotEmpty() && s.notifyGoals -> notifier.progress("Achievement unlocked", fresh.first().title)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Post-session processing failed", e)
        }
    }

    private fun bootCount(): Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    private fun Int.toDegrees(): Int = when (this) {
        android.view.Surface.ROTATION_90 -> 90
        android.view.Surface.ROTATION_180 -> 180
        android.view.Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun Int.toSurfaceRotation(): Int = when (this) {
        90 -> android.view.Surface.ROTATION_90
        180 -> android.view.Surface.ROTATION_180
        270 -> android.view.Surface.ROTATION_270
        else -> android.view.Surface.ROTATION_0
    }

    companion object {
        private const val TAG = "SessionManager"
        const val TEST_SESSION_ID = "screen-off-test"
        private const val CHECKPOINT_MS = 30_000L
        private val STREAK_MILESTONES = setOf(3, 7, 14, 21, 30, 50, 75, 100, 150, 200, 365)
    }
}
