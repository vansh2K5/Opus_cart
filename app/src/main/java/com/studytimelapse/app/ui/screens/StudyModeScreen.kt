package com.studytimelapse.app.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.studytimelapse.app.AppContainer
import com.studytimelapse.app.data.prefs.StudyScreenMode
import com.studytimelapse.app.domain.Format
import com.studytimelapse.app.domain.TimelapseMath
import com.studytimelapse.app.service.CameraStatus
import com.studytimelapse.app.service.LiveSession
import com.studytimelapse.app.ui.components.ProgressBar
import com.studytimelapse.app.ui.navigation.Routes
import com.studytimelapse.app.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Ink = Color(0xFF8A7B6C) // warm, low-luminance text on black (OLED friendly)
private val InkDim = Color(0xFF4A4038)
private val Accent = Color(0xFFB6602A)
private val Problem = Color(0xFFD08A5A)

@Composable
fun StudyModeScreen(container: AppContainer, nav: NavController, confirmFinish: Boolean) {
    val live by container.sessionManager.live.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showFinish by remember { mutableStateOf(confirmFinish) }
    var controlsVisible by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableIntStateOf(0) }
    var finishing by remember { mutableStateOf(false) }

    val l = live
    LaunchedEffect(l == null) {
        if (l == null && !finishing) nav.popBackStack(Routes.HOME, inclusive = false)
    }
    if (l == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }

    val dim = l.screenMode == StudyScreenMode.DIM
    // Controls fade away after a few seconds so only the essentials remain.
    LaunchedEffect(lastTouch, showFinish) {
        controlsVisible = true
        if (showFinish) return@LaunchedEffect
        delay(8_000)
        controlsVisible = false
    }
    StudyWindowEffects(keepScreenOn = dim, lowBrightness = dim && !controlsVisible)
    BackHandler { nav.popBackStack(Routes.HOME, inclusive = false) }

    val now = rememberElapsedNow()
    val studyMs = l.studyMsAt(now)
    // Move the timer a few pixels every minute to avoid OLED burn-in over long sessions.
    val drift = ((now / 60_000) % 5).toInt() * 3 - 6

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { lastTouch++ }
            .safeDrawingPadding()
            .padding(horizontal = Spacing.gutter),
    ) {
        Column(
            Modifier.fillMaxSize().offset(x = drift.dp, y = drift.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(l.subject.uppercase(), style = MaterialTheme.typography.titleSmall, color = Ink)
            Spacer(Modifier.height(Spacing.l))
            Text(
                Format.clock(studyMs),
                style = MaterialTheme.typography.displayLarge,
                color = if (l.running) Ink else InkDim,
                modifier = Modifier.semantics { contentDescription = "Studied ${Format.spokenDuration(studyMs)}" },
            )
            l.targetMs?.let { t ->
                Text("/ ${Format.clock(t)}", style = MaterialTheme.typography.titleMedium, color = InkDim)
                Spacer(Modifier.height(Spacing.l))
                ProgressBar((studyMs.toFloat() / t), Modifier.padding(horizontal = 32.dp), height = 4.dp, color = Accent)
            }
            Spacer(Modifier.height(Spacing.xl))
            CameraStatusLine(l, now)
            if (!l.running) {
                Spacer(Modifier.height(Spacing.s))
                Text("Paused", style = MaterialTheme.typography.labelLarge, color = Problem)
            }
            l.warning?.let {
                Spacer(Modifier.height(Spacing.s))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Problem, textAlign = TextAlign.Center)
            }
            if (l.screenMode == StudyScreenMode.SCREEN_OFF && l.running && l.timelapse) {
                Spacer(Modifier.height(Spacing.s))
                Text(
                    "You can lock your phone now. We'll alert you if the camera stops.",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                    textAlign = TextAlign.Center,
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible || !l.running || l.interrupted || needsAttention(l),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Spacing.xxl),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                if (l.interrupted) {
                    Text(
                        "Your phone restarted or the app was closed. Study time up to the last checkpoint is saved.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Problem,
                        textAlign = TextAlign.Center,
                    )
                }
                if (l.running && (l.camera == CameraStatus.NOT_RUNNING || l.camera == CameraStatus.ERROR || l.camera == CameraStatus.STALLED)) {
                    TextButton(onClick = { scope.launch { container.sessionManager.restartCamera() } }) {
                        Text("Restart camera", color = Accent)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    OutlinedButton(
                        onClick = {
                            lastTouch++
                            scope.launch { if (l.running) container.sessionManager.pause() else container.sessionManager.resume() }
                        },
                        modifier = Modifier.heightIn(min = 56.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                    ) { Text(if (l.running) "Pause" else "Resume", style = MaterialTheme.typography.titleMedium) }
                    Button(
                        onClick = { showFinish = true },
                        modifier = Modifier.heightIn(min = 56.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A221C), contentColor = Ink),
                    ) { Text("Finish", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }

    if (showFinish) {
        FinishDialog(
            live = l,
            studyMs = studyMs,
            onDismiss = { showFinish = false; lastTouch++ },
            onDiscard = {
                showFinish = false
                finishing = true
                scope.launch {
                    container.sessionManager.discard()
                    nav.popBackStack(Routes.HOME, inclusive = false)
                }
            },
            onFinish = {
                showFinish = false
                finishing = true
                scope.launch {
                    val id = container.sessionManager.finish()
                    if (id != null) {
                        nav.navigate(Routes.summary(id)) { popUpTo(Routes.HOME) }
                    } else {
                        nav.popBackStack(Routes.HOME, inclusive = false)
                    }
                }
            },
        )
    }
}

private fun needsAttention(l: LiveSession) = l.timelapse && l.running &&
    l.camera in setOf(CameraStatus.ERROR, CameraStatus.STALLED, CameraStatus.NOT_RUNNING, CameraStatus.STOPPED_THERMAL, CameraStatus.STOPPED_STORAGE)

@Composable
private fun CameraStatusLine(l: LiveSession, now: Long) {
    if (!l.timelapse) return
    val recording = l.camera == CameraStatus.RECORDING && l.running
    val problem = needsAttention(l)
    val secondsAgo = if (l.lastFrameElapsed > 0) (now - l.lastFrameElapsed) / 1000 else -1
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Box(Modifier.size(8.dp).background(if (recording) Accent else if (problem) Problem else InkDim, CircleShape))
        Spacer(Modifier.size(8.dp))
        Text(
            when {
                recording -> "TIMELAPSE RECORDING · ${Format.thousands(l.frames)} frames"
                else -> l.camera.label.uppercase()
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (problem) Problem else Ink,
        )
    }
    if (recording && secondsAgo >= 0) {
        Text(
            "Last frame ${secondsAgo}s ago · every ${l.intervalSec}s",
            style = MaterialTheme.typography.labelSmall,
            color = InkDim,
        )
    }
    l.cameraMessage?.let {
        Spacer(Modifier.height(4.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = Problem, textAlign = TextAlign.Center)
    }
    if (l.gaps > 0) {
        Text("${l.gaps} gap${if (l.gaps == 1) "" else "s"} in this timelapse", style = MaterialTheme.typography.labelSmall, color = InkDim)
    }
}

@Composable
private fun FinishDialog(live: LiveSession, studyMs: Long, onDismiss: () -> Unit, onDiscard: () -> Unit, onFinish: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Finish study session?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(live.subject, style = MaterialTheme.typography.titleMedium)
                Text("Study time: ${Format.duration(studyMs)}")
                Text("Pauses: ${live.pauseCount}")
                if (live.timelapse) {
                    Text("Frames captured: ${Format.thousands(live.frames)}")
                    Text("Timelapse length: ~${Format.shortDuration(TimelapseMath.videoLengthMs(live.frames))}")
                }
                if (studyMs < 60_000) {
                    Text(
                        "Under a minute. You can discard it instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onFinish) { Text(if (live.timelapse) "Finish & process timelapse" else "Finish") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Keep studying") }
            }
        },
    )
}

/**
 * Dim mode: keep the screen on (so Android never locks it and stops the camera) but at the
 * lowest brightness, with an all-black UI and hidden system bars. Restored when leaving.
 */
@Composable
private fun StudyWindowEffects(keepScreenOn: Boolean, lowBrightness: Boolean) {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(keepScreenOn) {
        val window = activity.window
        if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insets.show(WindowInsetsCompat.Type.systemBars())
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }
    LaunchedEffect(lowBrightness) {
        val window = activity.window
        window.attributes = window.attributes.apply {
            screenBrightness = if (lowBrightness) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }
}
