package com.studytimelapse.app.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

internal val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
internal const val MIN = 60_000L
internal const val HOUR = 60 * MIN

internal fun at(date: String, time: String, zone: ZoneId = ZONE): Long =
    LocalDateTime.parse("${date}T$time").atZone(zone).toInstant().toEpochMilli()

internal fun d(date: String): LocalDate = LocalDate.parse(date)

/** A single uninterrupted session starting at [date] [time] lasting [minutes]. */
internal fun session(
    date: String,
    time: String,
    minutes: Long,
    subject: String = "Math",
    zone: ZoneId = ZONE,
    id: String = "$date $time",
): StudyRecord {
    val start = at(date, time, zone)
    val end = start + minutes * MIN
    return StudyRecord(id, subject, start, end, minutes * MIN, listOf(StudySpan(start, end)))
}

internal class FakeClock(var elapsed: Long = 0, var wall: Long = 1_700_000_000_000) {
    fun advance(ms: Long) {
        elapsed += ms
        wall += ms
    }
}
