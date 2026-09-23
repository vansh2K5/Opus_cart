package com.studytimelapse.app.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.firestore.FirebaseFirestoreException
import com.studytimelapse.app.StudyApp
import com.studytimelapse.app.data.db.SessionStatus
import com.studytimelapse.app.data.db.SyncState
import com.studytimelapse.app.domain.GoalPeriod
import java.util.concurrent.TimeUnit

/**
 * Offline-first sync: the local Room database is the source of truth. Whenever the network is
 * available, finished sessions that changed locally are pushed (metadata only) and deletions are
 * propagated. Safe to run any number of times.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as StudyApp).container
        if (!c.social.available) {
            c.db.sessions().purgeDeleted()
            return Result.success()
        }
        if (c.auth.currentUser == null) return Result.success()
        return try {
            val s = c.settings.current()
            val weekly = c.db.goals().active().firstOrNull { it.period == GoalPeriod.WEEK.name }?.targetMinutes
            c.social.upsertProfile(
                username = s.username.ifBlank { "Student" },
                avatar = s.avatar,
                zone = s.zone.id,
                weeklyGoalMinutes = weekly,
                achievements = c.db.achievements().unlockedTypes(),
            )
            for (session in c.db.sessions().pendingSync()) {
                try {
                    if (session.status == SessionStatus.DELETED) {
                        c.social.deleteRemoteSession(session.id)
                        c.db.sessions().delete(session.id)
                    } else {
                        c.social.uploadSession(session, s.shareSubjectsWithFriend)
                        c.db.sessions().markSynced(session.id, session.updatedAt, SyncState.SYNCED)
                    }
                } catch (e: FirebaseFirestoreException) {
                    if (e.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) throw e
                    // The server rules rejected this session (e.g. older than the 60-day sync
                    // window). It stays in local statistics; stop retrying it forever.
                    Log.w(TAG, "Server rejected session ${session.id}; keeping it local only", e)
                    if (session.status == SessionStatus.DELETED) c.db.sessions().delete(session.id)
                    else c.db.sessions().markSynced(session.id, session.updatedAt, SyncState.SYNCED)
                }
            }
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed, will retry", e)
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("sync", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
