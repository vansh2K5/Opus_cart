package com.studytimelapse.app

import android.app.Application
import com.studytimelapse.app.notifications.Channels
import com.studytimelapse.app.worker.ReminderWorker
import com.studytimelapse.app.worker.SyncWorker
import com.studytimelapse.app.worker.TimelapseProcessingWorker
import kotlinx.coroutines.launch

class StudyApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Channels.create(this)
        // Recover a session that was active when the process died (crash, OS kill, reboot).
        container.sessionManager.restore()
        container.appScope.launch {
            container.subjects.seedDefaults()
            // Videos whose processing was interrupted are picked up again.
            container.db.sessions().processing().forEach { TimelapseProcessingWorker.enqueue(this@StudyApp, it.id) }
            ReminderWorker.schedule(this@StudyApp, container.settings.current())
        }
        SyncWorker.schedulePeriodic(this)
        SyncWorker.enqueue(this)
    }
}
