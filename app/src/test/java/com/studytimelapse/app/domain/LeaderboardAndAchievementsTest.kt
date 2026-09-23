package com.studytimelapse.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaderboardAndAchievementsTest {
    private val threshold = 30 * MIN
    private val today = d("2026-08-27")

    @Test
    fun `tiny sessions do not count as sessions but still count as time`() {
        val records = (0 until 10).map { session("2026-08-27", "10:%02d".format(it * 5), 3, id = "t$it") } +
            session("2026-08-26", "10:00", 60)
        val stats = LeaderboardCalculator.weekly(records, ZONE, today, threshold, weeklyGoalMinutes = 600)
        assertEquals(1, stats.sessions)
        assertEquals(90 * MIN, stats.studyMs)
        assertEquals(15, stats.weeklyGoalPercent)
    }

    @Test
    fun `overlapping sessions are never double counted`() {
        val a = session("2026-08-27", "10:00", 60, id = "a")
        val duplicate = a.copy(id = "b")
        val partial = session("2026-08-27", "10:30", 60, id = "c") // 30 min overlaps
        val stats = LeaderboardCalculator.weekly(listOf(a, duplicate, partial), ZONE, today, threshold, null)
        assertEquals(90 * MIN, stats.studyMs)
    }

    @Test
    fun `impossible study time is clamped to wall clock`() {
        val start = at("2026-08-27", "10:00")
        val cheat = StudyRecord("x", "CS", start, start + HOUR, 10 * HOUR)
        val stats = LeaderboardCalculator.weekly(listOf(cheat), ZONE, today, threshold, null)
        assertEquals(HOUR, stats.studyMs)
    }

    @Test
    fun `lead by metric`() {
        val me = CompetitorStats(14 * HOUR + 20 * MIN, 9, 8, 72)
        val friend = CompetitorStats(12 * HOUR + 45 * MIN, 11, 6, 64)
        assertEquals(95 * MIN, LeaderboardCalculator.lead(me, friend, LeaderboardMetric.STUDY_TIME))
        assertEquals(-2, LeaderboardCalculator.lead(me, friend, LeaderboardMetric.SESSIONS))
        assertEquals(2, LeaderboardCalculator.lead(me, friend, LeaderboardMetric.STREAK))
    }

    @Test
    fun `achievements`() {
        val records = (21..27).map { session("2026-08-$it", "21:30", 70) } + session("2026-08-20", "05:00", 250)
        val unlocked = AchievementEvaluator.evaluate(records, ZONE, today, threshold)
        assertTrue(AchievementType.FIRST_SESSION in unlocked)
        assertTrue(AchievementType.HOURS_10 in unlocked) // 8h10m + 4h10m
        assertFalse(AchievementType.HOURS_50 in unlocked)
        assertTrue(AchievementType.STREAK_7 in unlocked)
        assertTrue(AchievementType.CONSISTENCY in unlocked)
        assertTrue(AchievementType.MARATHON in unlocked)
        assertTrue(AchievementType.NIGHT_OWL in unlocked) // 21:30 + 70 min ends 22:40
        assertFalse(AchievementType.EARLY_BIRD in unlocked) // the early session ended at 09:10
    }

    @Test
    fun `early bird`() {
        val unlocked = AchievementEvaluator.evaluate(listOf(session("2026-08-27", "06:00", 90)), ZONE, today, threshold)
        assertTrue(AchievementType.EARLY_BIRD in unlocked)
        assertFalse(AchievementType.NIGHT_OWL in unlocked)
    }

    @Test
    fun `no achievements without sessions`() {
        assertTrue(AchievementEvaluator.evaluate(emptyList(), ZONE, today, threshold).isEmpty())
    }

    @Test
    fun `challenge progress`() {
        val me = listOf(session("2026-08-24", "10:00", 120), session("2026-08-25", "10:00", 45))
        val friend = listOf(session("2026-08-24", "10:00", 200))
        val p = ChallengeCalculator.progress(
            ChallengeType.WEEKLY_HOURS, 15 * 60, me, friend, ZONE, ZONE, d("2026-08-24"), d("2026-08-30"), today, threshold,
        )
        assertEquals(165 * MIN, p.meValue)
        assertEquals(200 * MIN, p.friendValue)
        assertFalse(p.meDone)
        val c = ChallengeCalculator.progress(
            ChallengeType.CONSISTENCY_7, 0, me, friend, ZONE, ZONE, d("2026-08-24"), d("2026-08-30"), today, threshold,
        )
        assertEquals(2, c.meValue)
        assertEquals(7, c.target)
    }

    @Test
    fun `format`() {
        assertEquals("2h 14m", Format.duration(2 * HOUR + 14 * MIN + 59_000))
        assertEquals("45m", Format.duration(45 * MIN))
        assertEquals("3h", Format.duration(3 * HOUR))
        assertEquals("01:23:41", Format.clock(HOUR + 23 * MIN + 41_000))
        assertEquals("2m 14s", Format.shortDuration(134_000))
        assertEquals(3600, TimelapseMath.expectedFrames(2 * HOUR, 2))
        assertEquals(120_000, TimelapseMath.videoLengthMs(3600))
    }
}
