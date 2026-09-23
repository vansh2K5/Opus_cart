package com.studytimelapse.app.domain

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Pure domain types. Nothing in the `domain` package depends on Android, so all of the
 * statistics/streak/leaderboard maths can be unit-tested on a plain JVM.
 *
 * All instants are epoch milliseconds in UTC. Days are always resolved against an explicit
 * [java.time.ZoneId] supplied by the caller (the user's configured time zone).
 */

/** A continuous stretch of actual studying (the timer was running). */
data class StudySpan(val startUtc: Long, val endUtc: Long) {
    init {
        require(endUtc >= startUtc) { "span ends before it starts" }
    }

    val durationMs: Long get() = endUtc - startUtc
}

/**
 * One finished study session, as seen by the statistics engine.
 *
 * [spans] may be empty for sessions that came from the server (the friend's sessions only carry
 * start/end/study time). In that case the study time is spread evenly across [startUtc, endUtc].
 */
data class StudyRecord(
    val id: String,
    val subject: String,
    val startUtc: Long,
    val endUtc: Long,
    val studyMs: Long,
    val spans: List<StudySpan> = emptyList(),
    val hasTimelapse: Boolean = false,
)

enum class GoalPeriod { DAY, WEEK, MONTH }

data class Goal(val id: Long, val period: GoalPeriod, val targetMinutes: Int)

data class GoalProgress(val goal: Goal, val achievedMs: Long) {
    val targetMs: Long get() = goal.targetMinutes * 60_000L
    val fraction: Float get() = if (targetMs <= 0) 0f else (achievedMs.toFloat() / targetMs).coerceAtMost(1f)
    val percent: Int get() = if (targetMs <= 0) 0 else ((achievedMs * 100) / targetMs).toInt()
    val remainingMs: Long get() = (targetMs - achievedMs).coerceAtLeast(0)
    val isComplete: Boolean get() = achievedMs >= targetMs
}

data class StreakInfo(
    val current: Int,
    val longest: Int,
    val daysStudied: Int,
    /** True when today already counts towards the streak. */
    val todayCounts: Boolean,
)

data class PeriodStats(
    val start: LocalDate,
    val endInclusive: LocalDate,
    val totalMs: Long,
    val sessions: Int,
    val averageSessionMs: Long,
    val longestSessionMs: Long,
    val averageDailyMs: Long,
    val bestDay: LocalDate?,
    val bestDayMs: Long,
    val subjects: List<SubjectTime>,
    val daysStudied: Int,
    /** Qualifying days / elapsed days in the period (0..100). */
    val consistencyPercent: Int,
    val timelapses: Int,
)

data class SubjectTime(val subject: String, val ms: Long)

data class PersonalRecords(
    val longestSessionMs: Long,
    val mostInDayMs: Long,
    val mostInDayDate: LocalDate?,
    val mostInWeekMs: Long,
    val mostInWeekStart: LocalDate?,
    val longestStreak: Int,
    val mostSessionsInDay: Int,
    val topSubject: String?,
    /** Hour of day (0-23) with the most study time, or null with no data. */
    val bestHour: Int?,
)

val WEEK_START: DayOfWeek = DayOfWeek.MONDAY
