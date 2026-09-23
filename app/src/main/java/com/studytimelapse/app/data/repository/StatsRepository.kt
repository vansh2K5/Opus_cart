package com.studytimelapse.app.data.repository

import com.studytimelapse.app.data.db.AppDatabase
import com.studytimelapse.app.data.db.GoalEntity
import com.studytimelapse.app.data.prefs.AppSettings
import com.studytimelapse.app.data.prefs.SettingsRepository
import com.studytimelapse.app.domain.Goal
import com.studytimelapse.app.domain.GoalPeriod
import com.studytimelapse.app.domain.GoalProgress
import com.studytimelapse.app.domain.PeriodStats
import com.studytimelapse.app.domain.PersonalRecords
import com.studytimelapse.app.domain.StatsCalculator
import com.studytimelapse.app.domain.StreakInfo
import com.studytimelapse.app.domain.StudyRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import java.time.LocalDate
import java.time.ZoneId

/** Everything the Home, Activity and Profile screens need, recomputed when data changes. */
data class Dashboard(
    val zone: ZoneId,
    val today: LocalDate,
    val records: List<StudyRecord>,
    val daily: Map<LocalDate, Long>,
    val streak: StreakInfo,
    val todayStats: PeriodStats,
    val week: PeriodStats,
    val month: PeriodStats,
    val allTime: PeriodStats,
    val goals: List<GoalProgress>,
    val personal: PersonalRecords,
    val settings: AppSettings,
) {
    val dailyGoal: GoalProgress? get() = goals.firstOrNull { it.goal.period == GoalPeriod.DAY }
    val weeklyGoal: GoalProgress? get() = goals.firstOrNull { it.goal.period == GoalPeriod.WEEK }
}

fun GoalEntity.toDomain() = Goal(id, runCatching { GoalPeriod.valueOf(period) }.getOrDefault(GoalPeriod.DAY), targetMinutes)

class StatsRepository(
    sessions: SessionRepository,
    settings: SettingsRepository,
    private val db: AppDatabase,
    scope: CoroutineScope,
) {
    /** Emits the current minute so "today" rolls over at midnight without an app restart. */
    private val minuteTicker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis() / 60_000)
            delay(60_000)
        }
    }

    val dashboard: Flow<Dashboard> = combine(
        sessions.records,
        settings.settings,
        db.goals().observeActive(),
        minuteTicker,
    ) { records, s, goals, _ ->
        compute(records, s, goals.map { it.toDomain() })
    }.distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        // One shared computation for every screen; stops 5 s after the UI goes away.
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    val goals: Flow<List<Goal>> = db.goals().observeActive().map { list -> list.map { it.toDomain() } }

    suspend fun setGoal(period: GoalPeriod, minutes: Int) {
        val existing = db.goals().active().firstOrNull { it.period == period.name }
        db.goals().upsert(
            existing?.copy(targetMinutes = minutes)
                ?: GoalEntity(period = period.name, targetMinutes = minutes, createdAt = System.currentTimeMillis()),
        )
    }

    suspend fun removeGoal(id: Long) = db.goals().deactivate(id)

    companion object {
        fun compute(records: List<StudyRecord>, s: AppSettings, goals: List<Goal>): Dashboard {
            val zone = s.zone
            val today = LocalDate.now(zone)
            val threshold = s.streakThresholdMs
            val daily = StatsCalculator.dailyTotals(records, zone)
            val rawStreak = StatsCalculator.streak(daily, today, threshold)
            val weekStart = StatsCalculator.weekStart(today)
            val monthStart = StatsCalculator.monthStart(today)
            return Dashboard(
                zone = zone,
                today = today,
                records = records,
                daily = daily,
                // The longest streak is also persisted so it survives deleting old sessions.
                streak = rawStreak.copy(longest = maxOf(rawStreak.longest, s.longestStreakEver)),
                todayStats = StatsCalculator.periodStats(records, zone, today, today, today, threshold),
                week = StatsCalculator.periodStats(records, zone, weekStart, weekStart.plusDays(6), today, threshold),
                month = StatsCalculator.periodStats(
                    records, zone, monthStart, monthStart.withDayOfMonth(monthStart.lengthOfMonth()), today, threshold,
                ),
                allTime = StatsCalculator.allTime(records, zone, today, threshold),
                goals = goals.map { StatsCalculator.goalProgress(it, daily, today) },
                personal = StatsCalculator.personalRecords(records, zone, today, threshold),
                settings = s,
            )
        }
    }
}
