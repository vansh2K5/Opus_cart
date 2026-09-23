package com.studytimelapse.app.domain

import java.time.LocalDate
import java.time.ZoneId

enum class LeaderboardMetric { STUDY_TIME, SESSIONS, STREAK }

data class CompetitorStats(
    val studyMs: Long,
    val sessions: Int,
    val streak: Int,
    val weeklyGoalPercent: Int?,
)

/**
 * Fair competition maths.
 *
 * - Only sessions of at least [MIN_COUNTED_SESSION_MS] count toward the *session* metric, so
 *   starting many tiny sessions cannot inflate it.
 * - Overlapping sessions are merged so the same wall-clock minutes can never be counted twice.
 * - A session can never claim more study time than its wall-clock length, nor more than 24h.
 */
object LeaderboardCalculator {
    const val MIN_COUNTED_SESSION_MS = 10 * 60_000L
    private const val MAX_SESSION_MS = 24 * 3_600_000L

    /** Removes impossible values and double-counted overlap. Returns sanitised records. */
    fun sanitize(records: List<StudyRecord>): List<StudyRecord> {
        val sorted = records
            .filter { it.endUtc > it.startUtc && it.studyMs > 0 }
            .map { it.copy(studyMs = minOf(it.studyMs, it.endUtc - it.startUtc, MAX_SESSION_MS)) }
            .sortedBy { it.startUtc }
        val out = ArrayList<StudyRecord>(sorted.size)
        var coveredUntil = Long.MIN_VALUE
        for (r in sorted) {
            if (r.endUtc <= coveredUntil) continue // fully contained in an earlier session
            if (r.startUtc < coveredUntil) {
                // Partial overlap: only the uncovered wall-clock part may contribute.
                val uncovered = r.endUtc - coveredUntil
                val wall = r.endUtc - r.startUtc
                val scaled = r.studyMs * uncovered / wall
                out += r.copy(startUtc = coveredUntil, studyMs = scaled, spans = emptyList())
            } else {
                out += r
            }
            coveredUntil = maxOf(coveredUntil, r.endUtc)
        }
        return out
    }

    fun weekly(
        records: List<StudyRecord>,
        zone: ZoneId,
        today: LocalDate,
        streakThresholdMs: Long,
        weeklyGoalMinutes: Int?,
    ): CompetitorStats {
        val clean = sanitize(records)
        val weekStart = StatsCalculator.weekStart(today)
        val week = StatsCalculator.periodStats(
            clean, zone, weekStart, weekStart.plusDays(6), today, streakThresholdMs,
        )
        val countedSessions = clean.count {
            val d = StatsCalculator.dateOf(it.startUtc, zone)
            !d.isBefore(weekStart) && !d.isAfter(weekStart.plusDays(6)) && it.studyMs >= MIN_COUNTED_SESSION_MS
        }
        val streak = StatsCalculator.streak(StatsCalculator.dailyTotals(clean, zone), today, streakThresholdMs).current
        val goalPercent = weeklyGoalMinutes?.takeIf { it > 0 }?.let { (week.totalMs * 100 / (it * 60_000L)).toInt() }
        return CompetitorStats(week.totalMs, countedSessions, streak, goalPercent)
    }

    fun value(stats: CompetitorStats, metric: LeaderboardMetric): Long = when (metric) {
        LeaderboardMetric.STUDY_TIME -> stats.studyMs
        LeaderboardMetric.SESSIONS -> stats.sessions.toLong()
        LeaderboardMetric.STREAK -> stats.streak.toLong()
    }

    /** Positive when "me" leads, negative when the friend leads, zero on a tie. */
    fun lead(me: CompetitorStats, friend: CompetitorStats, metric: LeaderboardMetric): Long =
        value(me, metric) - value(friend, metric)
}
