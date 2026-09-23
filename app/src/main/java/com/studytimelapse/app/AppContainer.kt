package com.studytimelapse.app

import android.content.Context
import com.studytimelapse.app.data.db.AppDatabase
import com.studytimelapse.app.data.prefs.SettingsRepository
import com.studytimelapse.app.data.remote.AuthRepository
import com.studytimelapse.app.data.remote.SocialRepository
import com.studytimelapse.app.data.repository.AchievementRepository
import com.studytimelapse.app.data.repository.SessionRepository
import com.studytimelapse.app.data.repository.StatsRepository
import com.studytimelapse.app.data.repository.SubjectRepository
import com.studytimelapse.app.notifications.Notifier
import com.studytimelapse.app.service.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency injection: one instance of each long-lived object for the whole process.
 * (A DI framework would be overkill for an app of this size.)
 */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDatabase = AppDatabase.create(context)
    val settings = SettingsRepository(context)
    val notifier = Notifier(context)
    val sessions = SessionRepository(context, db)
    val stats = StatsRepository(sessions, settings, db, appScope)
    val subjects = SubjectRepository(db)
    val achievements = AchievementRepository(db)
    val auth = AuthRepository(context)
    val social = SocialRepository(auth)

    val sessionManager = SessionManager(
        context = context,
        db = db,
        settings = settings,
        sessions = sessions,
        subjects = subjects,
        achievements = achievements,
        notifier = notifier,
        scope = appScope,
        publishLive = { subject, startedAt, target ->
            if (settings.current().shareActivityWithFriend) social.setLive(subject, startedAt, target)
        },
    )
}
