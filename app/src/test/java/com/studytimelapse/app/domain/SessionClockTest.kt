package com.studytimelapse.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionClockTest {
    private val fake = FakeClock()
    private val clock = SessionClock({ fake.elapsed }, { fake.wall })

    @Test
    fun `study time excludes paused time`() {
        clock.start()
        fake.advance(60 * MIN)
        clock.pause()
        fake.advance(25 * MIN)
        clock.resume()
        fake.advance(65 * MIN)
        val spans = clock.finish()

        assertEquals(125 * MIN, clock.studyMs())
        assertEquals(2, spans.size)
        assertEquals(1, clock.pauseCount)
        val wallLength = fake.wall - clock.sessionStartUtc
        assertEquals(150 * MIN, wallLength)
        assertEquals(25 * MIN, wallLength - clock.studyMs())
    }

    @Test
    fun `wall clock jumps do not change study time`() {
        clock.start()
        fake.advance(30 * MIN)
        fake.wall -= 3 * HOUR // user changes the system clock
        fake.advance(30 * MIN)
        assertEquals(60 * MIN, clock.studyMs())
    }

    @Test
    fun `double pause and double resume are ignored`() {
        clock.start()
        fake.advance(10 * MIN)
        clock.pause()
        clock.pause()
        fake.advance(10 * MIN)
        clock.resume()
        clock.resume()
        fake.advance(10 * MIN)
        assertEquals(20 * MIN, clock.studyMs())
        assertEquals(1, clock.pauseCount)
    }

    @Test
    fun `restored session resumes paused and keeps previous spans`() {
        val spans = listOf(StudySpan(0, 40 * MIN))
        clock.restore(0, spans, pauses = 2)
        assertEquals(SessionClock.State.PAUSED, clock.state)
        assertEquals(40 * MIN, clock.studyMs())
        clock.resume()
        fake.advance(20 * MIN)
        assertEquals(60 * MIN, clock.studyMs())
    }

    @Test
    fun `snapshot includes the running span`() {
        clock.start()
        fake.advance(5 * MIN)
        assertEquals(5 * MIN, clock.snapshot().sumOf { it.durationMs })
    }

    @Test
    fun `restored running session continues like a stopwatch`() {
        clock.start()
        fake.advance(30 * MIN)
        val closed = clock.closedSpans()
        val (sinceUtc, sinceElapsed) = clock.runningSince()!!
        fake.advance(10 * MIN) // process was dead for these 10 minutes

        val restored = SessionClock({ fake.elapsed }, { fake.wall })
        restored.restore(clock.sessionStartUtc, closed, 0, sinceUtc, sinceElapsed)
        assertEquals(SessionClock.State.RUNNING, restored.state)
        assertEquals(40 * MIN, restored.studyMs())
    }

    @Test
    fun `after a reboot the running span cannot be trusted and restore is paused`() {
        val restored = SessionClock({ 5 * MIN }, { fake.wall }) // monotonic clock reset by reboot
        restored.restore(0, listOf(StudySpan(0, 20 * MIN)), 0, runningSinceUtc = 20 * MIN, runningSinceElapsed = 50 * MIN)
        assertEquals(SessionClock.State.PAUSED, restored.state)
        assertEquals(20 * MIN, restored.studyMs())
    }
}
