package com.studytimelapse.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class StatsCalculatorTest {
    private val threshold = 30 * MIN

    @Test
    fun `session crossing midnight is split between days`() {
        val r = session("2026-08-27", "23:59", minutes = 2) // 23:59 -> 00:01
        val daily = StatsCalculator.dailyTotals(listOf(r), ZONE)
        assertEquals(1 * MIN, daily[d("2026-08-27")])
        assertEquals(1 * MIN, daily[d("2026-08-28")])
    }

    @Test
    fun `day buckets follow the configured time zone`() {
        val utc = ZoneId.of("UTC")
        // 02:00 in Kolkata is 20:30 of the previous day in UTC.
        val r = session("2026-08-27", "02:00", minutes = 60)
        assertEquals(setOf(d("2026-08-27")), StatsCalculator.dailyTotals(listOf(r), ZONE).keys)
        assertEquals(setOf(d("2026-08-26")), StatsCalculator.dailyTotals(listOf(r), utc).keys)
    }

    @Test
    fun `streak from the spec example is 1`() {
        // Mon yes, Tue yes, Wed no, Thu yes -> current = 1, longest = 2
        val records = listOf(
            session("2026-08-24", "10:00", 60),
            session("2026-08-25", "10:00", 60),
            session("2026-08-27", "10:00", 60),
        )
        val s = StatsCalculator.streak(StatsCalculator.dailyTotals(records, ZONE), d("2026-08-27"), threshold)
        assertEquals(1, s.current)
        assertEquals(2, s.longest)
        assertEquals(3, s.daysStudied)
        assertTrue(s.todayCounts)
    }

    @Test
    fun `streak survives until today ends`() {
        val records = (20..26).map { session("2026-08-$it", "09:00", 45) }
        val daily = StatsCalculator.dailyTotals(records, ZONE)
        val s = StatsCalculator.streak(daily, d("2026-08-27"), threshold)
        assertEquals(7, s.current)
        assertFalse(s.todayCounts)
        // Missing an entire day breaks it.
        assertEquals(0, StatsCalculator.streak(daily, d("2026-08-28"), threshold).current)
    }

    @Test
    fun `short sessions do not count toward the streak`() {
        val records = listOf(
            session("2026-08-26", "10:00", 60),
            session("2026-08-27", "10:00", 1), // accidental 1 minute session
        )
        val s = StatsCalculator.streak(StatsCalculator.dailyTotals(records, ZONE), d("2026-08-27"), threshold)
        assertEquals(1, s.current)
        assertFalse(s.todayCounts)
    }

    @Test
    fun `several small sessions in a day add up to a qualifying day`() {
        val records = listOf(
            session("2026-08-27", "08:00", 20),
            session("2026-08-27", "18:00", 15),
        )
        val s = StatsCalculator.streak(StatsCalculator.dailyTotals(records, ZONE), d("2026-08-27"), threshold)
        assertEquals(1, s.current)
    }

    @Test
    fun `midnight session makes both days qualify when long enough`() {
        val r = session("2026-08-27", "23:00", 120) // 60 min each side
        val daily = StatsCalculator.dailyTotals(listOf(r), ZONE)
        assertEquals(2, StatsCalculator.streak(daily, d("2026-08-28"), threshold).current)
    }

    @Test
    fun `weekly stats`() {
        val records = listOf(
            session("2026-08-24", "10:00", 90, "CS"),
            session("2026-08-25", "10:00", 30, "Math"),
            session("2026-08-25", "15:00", 60, "CS"),
            session("2026-08-31", "10:00", 60, "CS"), // next week
        )
        val start = StatsCalculator.weekStart(d("2026-08-27"))
        assertEquals(d("2026-08-24"), start)
        val w = StatsCalculator.periodStats(records, ZONE, start, start.plusDays(6), d("2026-08-27"), threshold)
        assertEquals(180 * MIN, w.totalMs)
        assertEquals(3, w.sessions)
        assertEquals(60 * MIN, w.averageSessionMs)
        assertEquals(90 * MIN, w.longestSessionMs)
        assertEquals(45 * MIN, w.averageDailyMs) // 180 min over the 4 elapsed days (Mon..Thu)
        assertEquals("CS", w.subjects.first().subject)
        assertEquals(150 * MIN, w.subjects.first().ms)
        assertEquals(50, w.consistencyPercent) // Mon + Tue qualify out of 4 elapsed days
    }

    @Test
    fun `goal progress for day week and month`() {
        val records = listOf(
            session("2026-08-01", "10:00", 120),
            session("2026-08-26", "10:00", 60),
            session("2026-08-27", "10:00", 90),
        )
        val daily = StatsCalculator.dailyTotals(records, ZONE)
        val today = d("2026-08-27")
        val dayGoal = StatsCalculator.goalProgress(Goal(1, GoalPeriod.DAY, 120), daily, today)
        assertEquals(90 * MIN, dayGoal.achievedMs)
        assertEquals(75, dayGoal.percent)
        val week = StatsCalculator.goalProgress(Goal(2, GoalPeriod.WEEK, 20 * 60), daily, today)
        assertEquals(150 * MIN, week.achievedMs)
        val month = StatsCalculator.goalProgress(Goal(3, GoalPeriod.MONTH, 60 * 60), daily, today)
        assertEquals(270 * MIN, month.achievedMs)
        assertFalse(month.isComplete)
        assertEquals(60 * HOUR - 270 * MIN, month.remainingMs)
    }

    @Test
    fun `server records without spans are spread over the session`() {
        val start = at("2026-08-27", "23:00")
        val r = StudyRecord("x", "CS", start, start + 2 * HOUR, 1 * HOUR) // 50% studied
        val daily = StatsCalculator.dailyTotals(listOf(r), ZONE)
        assertEquals(30 * MIN, daily[d("2026-08-27")])
        assertEquals(30 * MIN, daily[d("2026-08-28")])
    }

    @Test
    fun `personal records and best hour`() {
        val records = listOf(
            session("2026-08-24", "21:00", 150, "CS"),
            session("2026-08-25", "07:00", 30, "Math"),
            session("2026-08-25", "21:10", 40, "CS"),
        )
        val pr = StatsCalculator.personalRecords(records, ZONE, d("2026-08-27"), threshold)
        assertEquals(150 * MIN, pr.longestSessionMs)
        assertEquals(150 * MIN, pr.mostInDayMs)
        assertEquals(2, pr.mostSessionsInDay)
        assertEquals("CS", pr.topSubject)
        assertEquals(21, pr.bestHour)
        assertEquals(2, pr.longestStreak)
    }

    @Test
    fun `heat levels`() {
        assertEquals(0, StatsCalculator.heatLevel(0))
        assertEquals(1, StatsCalculator.heatLevel(30 * MIN))
        assertEquals(2, StatsCalculator.heatLevel(HOUR))
        assertEquals(4, StatsCalculator.heatLevel(3 * HOUR + 59 * MIN))
        assertEquals(5, StatsCalculator.heatLevel(6 * HOUR))
    }
}
