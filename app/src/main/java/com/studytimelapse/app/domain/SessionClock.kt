package com.studytimelapse.app.domain

/**
 * Tracks actual study time for one session, with pause/resume.
 *
 * Durations are measured with a monotonic clock ([elapsed], `SystemClock.elapsedRealtime()` on
 * Android) so that the user changing the wall clock or time zone mid-session cannot distort study
 * time. Each running span is anchored to the wall clock ([wall]) at the moment it starts; its end
 * is `start + monotonic duration`.
 *
 * Not thread-safe: the owning service calls it from a single thread.
 */
class SessionClock(
    private val elapsed: () -> Long,
    private val wall: () -> Long,
) {
    enum class State { IDLE, RUNNING, PAUSED, FINISHED }

    var state: State = State.IDLE
        private set

    var sessionStartUtc: Long = 0
        private set

    var pauseCount: Int = 0
        private set

    private val closed = mutableListOf<StudySpan>()
    private var runStartElapsed = 0L
    private var runStartUtc = 0L

    fun start() {
        check(state == State.IDLE) { "already started" }
        sessionStartUtc = wall()
        beginRun()
    }

    /**
     * Rebuilds a session from the database after the process died.
     *
     * If [runningSinceUtc]/[runningSinceElapsed] are given (the timer was running and the device
     * has not rebooted since, so the monotonic clock is still comparable) the clock continues
     * exactly like a stopwatch. Otherwise it resumes paused with only the persisted spans, so time
     * the app could not observe is never counted silently.
     */
    fun restore(
        startUtc: Long,
        spans: List<StudySpan>,
        pauses: Int,
        runningSinceUtc: Long? = null,
        runningSinceElapsed: Long? = null,
    ) {
        check(state == State.IDLE)
        sessionStartUtc = startUtc
        closed += spans
        pauseCount = pauses
        if (runningSinceUtc != null && runningSinceElapsed != null && runningSinceElapsed <= elapsed()) {
            runStartUtc = runningSinceUtc
            runStartElapsed = runningSinceElapsed
            state = State.RUNNING
        } else {
            state = State.PAUSED
        }
    }

    /** Closed spans only (the running one excluded). Persisted alongside [runningSince]. */
    fun closedSpans(): List<StudySpan> = closed.toList()

    /** (wall start, monotonic start) of the running span, or null when not running. */
    fun runningSince(): Pair<Long, Long>? = if (state == State.RUNNING) runStartUtc to runStartElapsed else null

    fun pause() {
        if (state != State.RUNNING) return
        closeRun()
        pauseCount++
        state = State.PAUSED
    }

    fun resume() {
        if (state != State.PAUSED) return
        beginRun()
    }

    /** Stops the clock. Returns every study span (the open one is closed first). */
    fun finish(): List<StudySpan> {
        if (state == State.RUNNING) closeRun()
        state = State.FINISHED
        return closed.toList()
    }

    /** Actual study time so far, including the currently running span. */
    fun studyMs(): Long = closed.sumOf { it.durationMs } + openRunMs()

    /** All spans, with the running one (if any) closed at "now". Used for checkpoints. */
    fun snapshot(): List<StudySpan> {
        val open = if (state == State.RUNNING) listOf(StudySpan(runStartUtc, runStartUtc + openRunMs())) else emptyList()
        return closed + open
    }

    /** Wall-clock end of the latest study activity (used as the session end time). */
    fun lastActivityUtc(): Long = snapshot().maxOfOrNull { it.endUtc } ?: sessionStartUtc

    private fun openRunMs(): Long = if (state == State.RUNNING) (elapsed() - runStartElapsed).coerceAtLeast(0) else 0

    private fun beginRun() {
        runStartElapsed = elapsed()
        runStartUtc = wall()
        state = State.RUNNING
    }

    private fun closeRun() {
        closed += StudySpan(runStartUtc, runStartUtc + openRunMs())
    }
}
