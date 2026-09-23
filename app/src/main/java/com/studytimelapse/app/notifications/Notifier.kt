package com.studytimelapse.app.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.studytimelapse.app.MainActivity
import com.studytimelapse.app.R
import com.studytimelapse.app.domain.Format
import com.studytimelapse.app.service.StudySessionService

object Channels {
    const val SESSION = "session"
    const val ALERTS = "alerts"
    const val PROGRESS = "progress"
    const val REMINDERS = "reminders"
    const val PROCESSING = "processing"

    fun create(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        fun ch(id: String, name: Int, desc: Int, importance: Int) =
            NotificationChannel(id, context.getString(name), importance).apply {
                description = context.getString(desc)
                setShowBadge(false)
            }
        nm.createNotificationChannels(
            listOf(
                ch(SESSION, R.string.channel_session, R.string.channel_session_desc, NotificationManager.IMPORTANCE_LOW),
                ch(ALERTS, R.string.channel_alerts, R.string.channel_alerts_desc, NotificationManager.IMPORTANCE_HIGH),
                ch(PROGRESS, R.string.channel_progress, R.string.channel_progress_desc, NotificationManager.IMPORTANCE_DEFAULT),
                ch(REMINDERS, R.string.channel_reminders, R.string.channel_reminders_desc, NotificationManager.IMPORTANCE_DEFAULT),
                ch(PROCESSING, R.string.channel_processing, R.string.channel_processing_desc, NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }
}

/**
 * Builds and posts every notification the app shows. Never spams: one id per purpose.
 * Every post is guarded by [canPost], hence the MissingPermission suppression.
 */
@SuppressLint("MissingPermission")
class Notifier(private val context: Context) {

    companion object {
        const val ID_SESSION = 1
        const val ID_ALERT = 2
        const val ID_PROGRESS = 3
        const val ID_REMINDER = 4
        const val ID_PROCESSING = 5
        const val EXTRA_OPEN = "open"
        const val OPEN_STUDY = "study"
        const val OPEN_FINISH = "finish"
        const val OPEN_HOME = "home"
    }

    fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openAppIntent(target: String, requestCode: Int = 0): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN, target)
        return PendingIntent.getActivity(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * The ongoing session notification. While running, the system chronometer ticks by itself, so
     * we never wake the CPU just to update a number.
     */
    fun sessionNotification(
        subject: String,
        running: Boolean,
        studyMs: Long,
        statusLine: String,
    ): android.app.Notification {
        val pauseResume = Intent(context, StudySessionService::class.java)
            .setAction(if (running) StudySessionService.ACTION_USER_PAUSE else StudySessionService.ACTION_USER_RESUME)
        val pausePi = PendingIntent.getService(
            context, 10, pauseResume, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(context, Channels.SESSION)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(subject)
            .setContentText(if (running) statusLine else "Paused · ${Format.duration(studyMs)} studied")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(OPEN_STUDY, 1))
            .addAction(0, if (running) "Pause" else "Resume", pausePi)
            .addAction(0, "Finish", openAppIntent(OPEN_FINISH, 2))
        if (running) {
            b.setUsesChronometer(true).setShowWhen(true).setWhen(System.currentTimeMillis() - studyMs)
        } else {
            b.setUsesChronometer(false).setShowWhen(false)
        }
        return b.build()
    }

    fun postSession(notification: android.app.Notification) {
        if (canPost()) NotificationManagerCompat.from(context).notify(ID_SESSION, notification)
    }

    fun cancelSession() = NotificationManagerCompat.from(context).cancel(ID_SESSION)

    /** High-priority problem with an active recording. Always truthful about what stopped. */
    fun alert(title: String, text: String) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, Channels.ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(OPEN_STUDY, 3))
            .build()
        NotificationManagerCompat.from(context).notify(ID_ALERT, n)
    }

    fun clearAlert() = NotificationManagerCompat.from(context).cancel(ID_ALERT)

    fun progress(title: String, text: String) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, Channels.PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(OPEN_HOME, 4))
            .build()
        NotificationManagerCompat.from(context).notify(ID_PROGRESS, n)
    }

    fun reminder(title: String, text: String) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, Channels.REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(OPEN_HOME, 5))
            .build()
        NotificationManagerCompat.from(context).notify(ID_REMINDER, n)
    }
}
