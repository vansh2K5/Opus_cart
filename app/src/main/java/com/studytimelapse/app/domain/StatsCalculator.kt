package com.studytimelapse.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Local, deterministic study statistics.
 *
 * Study time is attributed to calendar days by splitting every study span at local midnight in
 * the user's zone, so a session from 23:30 to 00:45 contributes 30 minutes to the first day and
 * 45 minutes to the second. This is what keeps streaks correct around midnight.
 */
object StatsCalculator {

    /** A slice of study time that falls entirely inside one local day. */
    data class DaySlice(val date: LocalDate, val subject: String, val startUtc: Long, val ms: Long)

    fun slices(records: List<StudyRecord>, zone: ZoneId): List<DaySlice> {
        val out = ArrayList<DaySlice>()
        for (r in records) {
            if (r.studyMs <= 0) continue
            if (r.spans.isNotEmpty()) {
                for (span in r.spans) splitSpan(span.startUtc, span.endUtc, 1.0, r.subject, zone, out)
            } else {
                // Server-side records carry no spans: spread study time evenly over the session.
                val wall = (r.endUtc - r.startUtc).coerceAtLeast(1)
                val scale = (r.studyMs.toDouble() / wall).coerceAtMost(1.0)
                if (r.endUtc <= r.startUtc) {
                    out += DaySlice(dateOf(r.startUtc, zone), r.subject, r.startUtc, r.studyMs)
                } else {
                    splitSpan(r.startUtc, r.endUtc, scale, r.subject, zone, out)
                }
            }
        }
        return out
    }

    private fun splitSpan(
        start: Long,
        end: Long,
        scale: Double,
        subject: String,
        zone: ZoneId,
        out: MutableList<DaySlice>,
    ) {
        var cursor = start
        while (cursor < end) {
            val date = dateOf(cursor, zone)
            val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val sliceEnd = minOf(end, nextMidnight)
            val ms = ((sliceEnd - cursor) * scale).toLong()
            if (ms > 0) out += DaySlice(date, subject, cursor, ms)
            cursor = sliceEnd
        }
    }

    fun dateOf(utcMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(utcMs).atZone(zone).toLocalDate()

    fun dailyTotals(records: List<StudyRecord>, zone: ZoneId): Map<LocalDate, Long> =
        slices(records, zone).groupBy { it.date }.mapValues { (_, v) -> v.sumOf { it.ms } }

    /**
     * Streaks. A day counts when its study time is at least [thresholdMs].
     * The current streak stays alive through today until today ends: if today does not count yet
     * the streak is measured up to yesterday.
     */
    fun streak(daily: Map<LocalDate, Long>, today: LocalDate, thresholdMs: Long): StreakInfo {
        val qualifying = daily.filter { it.value >= thresholdMs }.keys.toSortedSet()
        val todayCounts = today in qualifying

        var current = 0
        var day = if (todayCounts) today else today.minusDays(1)
        while (day in qualifying) {
            current++
            day = day.minusDays(1)
        }

        var longest = 0
        var run = 0
        var prev: LocalDate? = null
        for (d in qualifying) {
            if (d.isAfter(today)) break
            run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1
            longest = maxOf(longest, run)
            prev = d
        }
        return StreakInfo(current, maxOf(longest, current), qualifying.count { !it.isAfter(today) }, todayCounts)
    }

    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(WEEK_START))

    fun monthStart(date: LocalDate): LocalDate = date.withDayOfMonth(1)

    fun periodRange(period: GoalPeriod, today: LocalDate): Pair<LocalDate, LocalDate> = when (period) {
        GoalPeriod.DAY -> today to today
        GoalPeriod.WEEK -> weekStart(today).let { it to it.plusDays(6) }
        GoalPeriod.MONTH -> monthStart(today).let { it to it.withDayOfMonth(it.lengthOfMonth()) }
    }

    fun periodStats(
        records: List<StudyRecord>,
        zone: ZoneId,
        start: LocalDate,
        endInclusive: LocalDate,
        today: LocalDate,
        thresholdMs: Long,
    ): PeriodStats {
        val slices = slices(records, zone).filter { !it.date.isBefore(start) && !it.date.isAfter(endInclusive) }
        val perDay = slices.groupBy { it.date }.mapValues { (_, v) -> v.sumOf { it.ms } }
        val total = perDay.values.sum()
        // Sessions belong to the day they started.
        val sessions = records.filter {
            val d = dateOf(it.startUtc, zone)
            !d.isBefore(start) && !d.isAfter(endInclusive) && it.studyMs > 0
        }
        val best = perDay.maxByOrNull { it.value }
        val lastCountedDay = if (today.isBefore(endInclusive)) today else endInclusive
        val elapsedDays = if (lastCountedDay.isBefore(start)) 0 else ChronoUnit.DAYS.between(start, lastCountedDay).toInt() + 1
        val qualifyingDays = perDay.count { it.value >= thresholdMs }
        return PeriodStats(
            start = start,
            endInclusive = endInclusive,
            totalMs = total,
            sessions = sessions.size,
            averageSessionMs = if (sessions.isEmpty()) 0 else sessions.sumOf { it.studyMs } / sessions.size,
            longestSessionMs = sessions.maxOfOrNull { it.studyMs } ?: 0,
            averageDailyMs = if (elapsedDays == 0) 0 else total / elapsedDays,
            bestDay = best?.key,
            bestDayMs = best?.value ?: 0,
            subjects = slices.groupBy { it.subject }
                .map { (s, v) -> SubjectTime(s, v.sumOf { it.ms }) }
                .sortedByDescending { it.ms },
            daysStudied = perDay.count { it.value > 0 },
            consistencyPercent = if (elapsedDays == 0) 0 else (qualifyingDays * 100 / elapsedDays),
            timelapses = sessions.count { it.hasTimelapse },
        )
    }

    fun allTime(records: List<StudyRecord>, zone: ZoneId, today: LocalDate, thresholdMs: Long): PeriodStats {
        val first = records.minOfOrNull { dateOf(it.startUtc, zone) } ?: today
        return periodStats(records, zone, first, today, today, thresholdMs)
    }

    fun personalRecords(records: List<StudyRecord>, zone: ZoneId, today: LocalDate, thresholdMs: Long): PersonalRecords {
        val slices = slices(records, zone)
        val perDay = slices.groupBy { it.date }.mapValues { (_, v) -> v.sumOf { it.ms } }
        val perWeek = perDay.entries.groupBy { weekStart(it.key) }.mapValues { (_, v) -> v.sumOf { it.value } }
        val sessionsPerDay = records.filter { it.studyMs > 0 }.groupBy { dateOf(it.startUtc, zone) }.mapValues { it.value.size }
        val bestDay = perDay.maxByOrNull { it.value }
        val bestWeek = perWeek.maxByOrNull { it.value }
        val topSubject = slices.groupBy { it.subject }.maxByOrNull { (_, v) -> v.sumOf { it.ms } }?.key
        return PersonalRecords(
            longestSessionMs = records.maxOfOrNull { it.studyMs } ?: 0,
            mostInDayMs = bestDay?.value ?: 0,
            mostInDayDate = bestDay?.key,
            mostInWeekMs = bestWeek?.value ?: 0,
            mostInWeekStart = bestWeek?.key,
            longestStreak = streak(perDay, today, thresholdMs).longest,
            mostSessionsInDay = sessionsPerDay.values.maxOrNull() ?: 0,
            topSubject = topSubject,
            bestHour = bestHour(records, zone),
        )
    }

    /** Hour of the day (local) that has accumulated the most study time. */
    fun bestHour(records: List<StudyRecord>, zone: ZoneId): Int? {
        val perHour = LongArray(24)
        for (r in records) {
            val spans = r.spans.ifEmpty { listOf(StudySpan(r.startUtc, maxOf(r.startUtc, r.endUtc))) }
            for (s in spans) {
                var cursor = s.startUtc
                while (cursor < s.endUtc) {
                    val zdt = Instant.ofEpochMilli(cursor).atZone(zone)
                    val nextHour = zdt.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
                    val end = minOf(s.endUtc, nextHour)
                    perHour[zdt.hour] += end - cursor
                    cursor = end
                }
            }
        }
        val max = perHour.maxOrNull() ?: 0
        return if (max <= 0) null else perHour.indexOfFirst { it == max }
    }

    /** Heatmap intensity 0..5: none, <1h, 1-2h, 2-3h, 3-4h, 4h+. */
    fun heatLevel(ms: Long): Int {
        if (ms <= 0) return 0
        val hours = ms / 3_600_000.0
        return when {
            hours < 1 -> 1
            hours < 2 -> 2
            hours < 3 -> 3
            hours < 4 -> 4
            else -> 5
        }
    }

    fun goalProgress(goal: Goal, daily: Map<LocalDate, Long>, today: LocalDate): GoalProgress {
        val (start, end) = periodRange(goal.period, today)
        val achieved = daily.filterKeys { !it.isBefore(start) && !it.isAfter(end) }.values.sum()
        return GoalProgress(goal, achieved)
    }
}
