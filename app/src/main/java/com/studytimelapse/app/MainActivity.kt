package com.studytimelapse.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studytimelapse.app.notifications.Notifier
import com.studytimelapse.app.ui.navigation.AppNavHost
import com.studytimelapse.app.ui.theme.StudyTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** Deep-link target from a notification tap ("study", "finish", "home"). */
    val pendingOpen = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        val container = (application as StudyApp).container
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val s = settings
            if (s == null) {
                // DataStore loads in a few ms; avoid flashing the onboarding screen meanwhile.
                StudyTheme { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) }
            } else {
                StudyTheme(s.themeMode) {
                    AppNavHost(
                        container = container,
                        onboardingDone = s.onboardingDone,
                        pendingOpen = pendingOpen,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val target = intent?.getStringExtra(Notifier.EXTRA_OPEN) ?: return
        pendingOpen.value = target
        intent.removeExtra(Notifier.EXTRA_OPEN)
    }
}
