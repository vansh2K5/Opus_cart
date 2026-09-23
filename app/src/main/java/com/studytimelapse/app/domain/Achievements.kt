package com.studytimelapse.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class AchievementType(val title: String, val description: String) {
    FIRST_SESSION("First Session", "Complete your first study session."),
    HOURS_10("10 Hours", "Study for 10 total hours."),
    HOURS_50("50 Hours", "Study for 50 total hours."),
    HOURS_100("100 Hours", "Study for 100 total hours."),
    STREAK_7("7-Day Streak", "Study 7 days in a row."),
    STREAK_30("30-Day Streak", "Study 30 days in a row."),
    MARATHON("Marathon", "Study 4+ hours in a single day."),
    EARLY_BIRD("Early Bird", "Finish a study session before 8 AM."),
    NIGHT_OWL("Night Owl", "Study past 10 PM."),
    CONSISTENCY("Consistency", "Study at least 1 hour a day for 7 days in a row."),
}

object AchievementEvaluator {

    /** Sessions shorter than this never unlock time-of-day achievements. */
    private const val MIN_QUALIFYING_SESSION_MS = 15 * 60_000L

    fun evaluate(records: List<StudyRecord>, zone: ZoneId, today: LocalDate, streakThresholdMs: Long): Set<AchievementType> {
        val unlocked = mutableSetOf<AchievementType>()
        val valid = records.filter { it.studyMs > 0 }
        if (valid.isEmpty()) return unlocked
        unlocked += AchievementType.FIRST_SESSION

        val totalHours = valid.sumOf { it.studyMs } / 3_600_000.0
        if (totalHours >= 10) unlocked += AchievementType.HOURS_10
        if (totalHours >= 50) unlocked += AchievementType.HOURS_50
        if (totalHours >= 100) unlocked += AchievementType.HOURS_100

        val daily = StatsCalculator.dailyTotals(valid, zone)
        val longest = StatsCalculator.streak(daily, today, streakThresholdMs).longest
        if (longest >= 7) unlocked += AchievementType.STREAK_7
        if (longest >= 30) unlocked += AchievementType.STREAK_30
        if (daily.values.any { it >= 4 * 3_600_000L }) unlocked += AchievementType.MARATHON
        if (StatsCalculator.streak(daily, today, 3_600_000L).longest >= 7) unlocked += AchievementType.CONSISTENCY

        for (r in valid.filter { it.studyMs >= MIN_QUALIFYING_SESSION_MS }) {
            val end = Instant.ofEpochMilli(r.endUtc).atZone(zone)
            val start = Instant.ofEpochMilli(r.startUtc).atZone(zone)
            if (end.hour < 8 && end.hour >= 4) unlocked += AchievementType.EARLY_BIRD
            // Night owl: the session ran past 22:00 (or into the small hours).
            val ranLate = end.hour >= 22 || end.toLocalDate().isAfter(start.toLocalDate()) || end.hour < 4
            if (ranLate) unlocked += AchievementType.NIGHT_OWL
        }
        return unlocked
    }
}
