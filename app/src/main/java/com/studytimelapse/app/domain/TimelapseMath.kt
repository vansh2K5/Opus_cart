package com.studytimelapse.app.domain

/** Timelapse sizing maths, shared by the setup screen, the encoder and the summary. */
object TimelapseMath {
    /** Output playback rate of every timelapse. Each captured frame lasts 1/30 s on screen. */
    const val OUTPUT_FPS = 30

    fun expectedFrames(studyMs: Long, intervalSec: Int): Int =
        if (intervalSec <= 0) 0 else (studyMs / (intervalSec * 1000L)).toInt()

    fun videoLengthMs(frames: Int): Long = frames * 1000L / OUTPUT_FPS

    /** Rough file size in bytes for a given frame count and encoder bitrate (bits/s). */
    fun estimatedBytes(frames: Int, bitrate: Int): Long = videoLengthMs(frames) * bitrate / 8 / 1000

    /** Speed-up factor shown to the user, e.g. 60× for one frame every 2 s. */
    fun speedUp(intervalSec: Int): Int = intervalSec * OUTPUT_FPS
}

enum class ChallengeType(val title: String) {
    WEEKLY_HOURS("Weekly hours"),
    LONGEST_SESSION("Longest session"),
    CONSISTENCY_7("7-day consistency"),
}

data class ChallengeProgress(val meValue: Long, val friendValue: Long, val target: Long) {
    val meDone: Boolean get() = target > 0 && meValue >= target
    val friendDone: Boolean get() = target > 0 && friendValue >= target
}

object ChallengeCalculator {
    /**
     * Progress for a challenge running from [start] to [endInclusive].
     * WEEKLY_HOURS: study ms vs target ms. LONGEST_SESSION: best single session ms.
     * CONSISTENCY_7: qualifying days (>= threshold) vs 7.
     */
    fun progress(
        type: ChallengeType,
        targetMinutes: Int,
        me: List<StudyRecord>,
        friend: List<StudyRecord>,
        myZone: java.time.ZoneId,
        friendZone: java.time.ZoneId,
        start: java.time.LocalDate,
        endInclusive: java.time.LocalDate,
        today: java.time.LocalDate,
        thresholdMs: Long,
    ): ChallengeProgress {
        fun value(records: List<StudyRecord>, zone: java.time.ZoneId): Long {
            val clean = LeaderboardCalculator.sanitize(records)
            val stats = StatsCalculator.periodStats(clean, zone, start, endInclusive, today, thresholdMs)
            return when (type) {
                ChallengeType.WEEKLY_HOURS -> stats.totalMs
                ChallengeType.LONGEST_SESSION -> stats.longestSessionMs
                ChallengeType.CONSISTENCY_7 -> StatsCalculator.dailyTotals(clean, zone)
                    .count { (d, ms) -> !d.isBefore(start) && !d.isAfter(endInclusive) && ms >= thresholdMs }.toLong()
            }
        }
        val target = when (type) {
            ChallengeType.CONSISTENCY_7 -> 7L
            else -> targetMinutes * 60_000L
        }
        return ChallengeProgress(value(me, myZone), value(friend, friendZone), target)
    }
}
