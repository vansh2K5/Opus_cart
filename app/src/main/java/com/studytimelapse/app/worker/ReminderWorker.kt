package com.studytimelapse.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.studytimelapse.app.StudyApp
import com.studytimelapse.app.data.prefs.AppSettings
import com.studytimelapse.app.data.repository.StatsRepository
import com.studytimelapse.app.data.repository.toDomain
import com.studytimelapse.app.domain.Format
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * One gentle reminder at the user's chosen time on the chosen days. It re-schedules itself for
 * the next occurrence (no exact alarms needed, no extra permission). Quiet when there's nothing
 * useful to say, e.g. today's goal is already done or a session is running.
 */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as StudyApp).container
        val s = c.settings.current()
        if (!s.remindersEnabled) return Result.success()
        try {
            val today = LocalDateTime.now(s.zone)
            val dayBit = 1 shl (today.dayOfWeek.value - 1)
            if (s.reminderDays and dayBit != 0 && c.sessionManager.live.value == null) {
                val records = c.sessions.allRecords()
                val goals = c.db.goals().active().map { it.toDomain() }
                val d = StatsRepository.compute(records, s, goals)
                val daily = d.dailyGoal
                val weekly = d.weeklyGoal
                when {
                    daily != null && daily.isComplete -> Unit // nothing to nag about
                    weekly != null && !weekly.isComplete ->
                        c.notifier.reminder("Time to study?", "Your weekly goal needs another ${Format.duration(weekly.remainingMs)}.")
                    d.streak.current > 1 && !d.streak.todayCounts ->
                        c.notifier.reminder("Keep your ${d.streak.current}-day streak", "${s.streakThresholdMinutes} minutes today keeps it alive.")
                    else -> c.notifier.reminder("Time to study?", "Your desk is waiting.")
                }
            }
        } finally {
            schedule(applicationContext, s)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "reminder"

        /** (Re)schedules the next reminder, or cancels it when reminders are off. */
        fun schedule(context: Context, s: AppSettings) {
            val wm = WorkManager.getInstance(context)
            if (!s.remindersEnabled) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val now = LocalDateTime.now(s.zone)
            var next = now.toLocalDate().atTime(s.reminderMinuteOfDay / 60, s.reminderMinuteOfDay % 60)
            if (!next.isAfter(now.plusMinutes(1))) next = next.plusDays(1)
            val delay = Duration.between(now, next).toMillis()
            val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
            wm.enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
