package com.studytimelapse.app.ui.screens

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.studytimelapse.app.AppContainer
import com.studytimelapse.app.data.db.SessionEntity
import com.studytimelapse.app.data.db.TimelapseStatus
import com.studytimelapse.app.domain.Format
import com.studytimelapse.app.ui.components.Hairline
import com.studytimelapse.app.ui.components.KeyValueRow
import com.studytimelapse.app.ui.components.Kicker
import com.studytimelapse.app.ui.components.PrimaryButton
import com.studytimelapse.app.ui.components.SecondaryButton
import com.studytimelapse.app.ui.components.SurfaceCard
import com.studytimelapse.app.ui.navigation.Routes
import com.studytimelapse.app.ui.theme.LocalExtraColors
import com.studytimelapse.app.ui.theme.Spacing
import com.studytimelapse.app.util.Sharing
import com.studytimelapse.app.worker.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SessionSummaryScreen(container: AppContainer, nav: NavController, sessionId: String) {
    val session by container.sessions.observe(sessionId).collectAsStateWithLifecycle(initialValue = null)
    val outcome by container.sessionManager.lastOutcome.collectAsStateWithLifecycle()
    val dashboard by container.stats.dashboard.collectAsStateWithLifecycle(initialValue = null)
    val s = session
    val done = { nav.popBackStack(Routes.HOME, inclusive = false) }

    Page(title = null, onBack = { done() }) {
        if (s == null) return@Page
        Text("Great session.", style = MaterialTheme.typography.headlineLarge)
        Text(Format.duration(s.studyMs), style = MaterialTheme.typography.displayLarge)
        Text(s.subject, style = MaterialTheme.typography.titleLarge, color = LocalExtraColors.current.slate)

        outcome?.takeIf { it.sessionId == sessionId }?.let { o ->
            val lines = buildList {
                if (o.dailyGoalReached) add("Daily goal complete 🎉")
                if (o.streakIncreased) add("🔥 ${o.streak}-day streak")
                o.newAchievements.forEach { add("Achievement unlocked: $it") }
            }
            if (lines.isNotEmpty()) {
                SurfaceCard(color = MaterialTheme.colorScheme.primaryContainer) {
                    lines.forEach { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                }
            }
        }

        SessionBody(container, nav, s, streak = dashboard?.streak?.current ?: 0)
        Spacer(Modifier.height(Spacing.s))
        PrimaryButton("Done", { done() })
    }
}

@Composable
fun SessionDetailScreen(container: AppContainer, nav: NavController, sessionId: String) {
    val session by container.sessions.observe(sessionId).collectAsStateWithLifecycle(initialValue = null)
    val dashboard by container.stats.dashboard.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    val s = session
    val zone = dashboard?.zone ?: java.time.ZoneId.systemDefault()

    Page(title = s?.subject, onBack = { nav.popBackStack() }) {
        if (s == null) return@Page
        Kicker(Dates.relative(s.startUtc, zone))
        Text(Format.duration(s.studyMs), style = MaterialTheme.typography.displayMedium)
        s.title?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = LocalExtraColors.current.slate) }
        SessionBody(container, nav, s, streak = dashboard?.streak?.current ?: 0)
        TextButton(onClick = { confirmDelete = true }) {
            Text("Delete session", color = MaterialTheme.colorScheme.error)
        }
    }
    if (confirmDelete && s != null) {
        ConfirmDialog(
            title = "Delete this session?",
            text = "This removes ${Format.duration(s.studyMs)} of study time from your statistics, streak and the leaderboard, and deletes its timelapse. This can't be undone.",
            confirm = "Delete session",
            destructive = true,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    container.sessions.deleteSession(s.id)
                    SyncWorker.enqueue(context)
                    nav.popBackStack()
                }
            },
        )
    }
}

/** Timelapse block, statistics, notes and share/save/delete actions shared by both screens. */
@Composable
private fun SessionBody(container: AppContainer, nav: NavController, s: SessionEntity, streak: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = java.time.ZoneId.systemDefault()
    var confirmDeleteVideo by remember { mutableStateOf(false) }

    // ---- Timelapse
    when (s.timelapseStatus) {
        TimelapseStatus.PROCESSING -> SurfaceCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(Spacing.m))
                Column2 {
                    Text("Creating your timelapse…", style = MaterialTheme.typography.titleMedium)
                    Text("Your study time is already saved.", style = MaterialTheme.typography.bodySmall, color = LocalExtraColors.current.slate)
                }
            }
        }
        TimelapseStatus.READY -> {
            val thumb = rememberThumbnail(s.thumbnailPath)
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color.Black)
                    .clickable { nav.navigate(Routes.player(s.id)) },
                contentAlignment = Alignment.Center,
            ) {
                thumb?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                Box(
                    Modifier.size(64.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Watch timelapse", tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }
        }
        TimelapseStatus.FAILED -> SurfaceCard(color = MaterialTheme.colorScheme.surfaceVariant) {
            Text("The timelapse couldn't be created", style = MaterialTheme.typography.titleMedium)
            Text(
                if (s.frameCount == 0) "No frames were captured (the camera was unavailable). Your study time is saved."
                else "The video files were damaged. Your study time is saved.",
                style = MaterialTheme.typography.bodySmall,
                color = LocalExtraColors.current.slate,
            )
        }
        TimelapseStatus.DELETED -> Text("Timelapse deleted. Study time kept.", style = MaterialTheme.typography.bodySmall, color = LocalExtraColors.current.slate)
        else -> Unit
    }

    // ---- Statistics
    SurfaceCard {
        KeyValueRow("Study time", Format.duration(s.studyMs))
        Hairline()
        KeyValueRow("Session length", Format.duration((s.endUtc ?: s.startUtc) - s.startUtc))
        Hairline()
        KeyValueRow("Paused", "${Format.duration(s.pausedMs)} · ${s.pauseCount}×")
        Hairline()
        KeyValueRow("Started", Dates.time(s.startUtc, zone))
        s.endUtc?.let {
            Hairline()
            KeyValueRow("Finished", Dates.time(it, zone))
        }
        if (s.captureIntervalSec != null) {
            Hairline()
            KeyValueRow("Frames", Format.thousands(s.frameCount))
            if (s.timelapseDurationMs > 0) {
                Hairline()
                KeyValueRow("Timelapse length", Format.shortDuration(s.timelapseDurationMs))
            }
            if (s.captureGaps > 0) {
                Hairline()
                KeyValueRow("Recording gaps", "${s.captureGaps}")
            }
            if (s.framesScreenOff > 0) {
                Hairline()
                KeyValueRow("Frames with screen off", Format.thousands(s.framesScreenOff))
            }
        }
    }

    // ---- Notes (private: never uploaded)
    var notes by remember(s.id) { mutableStateOf(s.notes) }
    OutlinedTextField(
        value = notes,
        onValueChange = { notes = it },
        label = { Text("Notes (private)") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
    )
    LaunchedEffect(notes) {
        if (notes != s.notes) {
            kotlinx.coroutines.delay(600) // debounce typing
            container.sessions.saveNotes(s.id, notes, s.title)
        }
    }

    // ---- Actions
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        SecondaryButton("Share", {
            scope.launch { Sharing.shareSessionCard(context, s, streak, zone) }
        }, Modifier.weight(1f), icon = Icons.Outlined.IosShare)
        if (s.timelapseStatus == TimelapseStatus.READY && s.timelapsePath != null) {
            SecondaryButton("Save", {
                scope.launch {
                    val ok = Sharing.saveVideoToGallery(context, File(s.timelapsePath), "${s.subject}-${s.id.take(6)}")
                    Toast.makeText(context, if (ok) "Saved to Movies/StudyTimelapse" else "Couldn't save the video", Toast.LENGTH_SHORT).show()
                }
            }, Modifier.weight(1f), icon = Icons.Outlined.Download)
        }
    }
    if (s.timelapseStatus == TimelapseStatus.READY) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            SecondaryButton("Share video", {
                s.timelapsePath?.let { Sharing.shareVideo(context, File(it)) }
            }, Modifier.weight(1f), icon = Icons.Outlined.IosShare)
            SecondaryButton("Delete video", { confirmDeleteVideo = true }, Modifier.weight(1f), icon = Icons.Outlined.Delete)
        }
    }
    if (confirmDeleteVideo) {
        ConfirmDialog(
            title = "Delete this timelapse?",
            text = "The video (${com.studytimelapse.app.util.Storage.formatBytes(s.timelapseBytes)}) is deleted. The ${Format.duration(s.studyMs)} study session stays in your statistics.",
            confirm = "Delete video",
            destructive = true,
            onDismiss = { confirmDeleteVideo = false },
            onConfirm = {
                confirmDeleteVideo = false
                scope.launch { container.sessions.deleteTimelapse(s.id) }
            },
        )
    }
}

@Composable
private fun Column2(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Column { content() }
}

@Composable
fun rememberThumbnail(path: String?): ImageBitmap? {
    val state = produceState<ImageBitmap?>(initialValue = null, path) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }
    return state.value
}
