package com.studytimelapse.app.ui.screens

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import com.studytimelapse.app.AppContainer
import com.studytimelapse.app.data.db.SessionEntity
import com.studytimelapse.app.data.db.TimelapseStatus
import com.studytimelapse.app.domain.Format
import com.studytimelapse.app.ui.components.ChoicePill
import com.studytimelapse.app.ui.components.EmptyState
import com.studytimelapse.app.ui.components.Kicker
import com.studytimelapse.app.ui.components.SurfaceCard
import com.studytimelapse.app.ui.navigation.Routes
import com.studytimelapse.app.ui.theme.LocalExtraColors
import com.studytimelapse.app.ui.theme.Spacing
import com.studytimelapse.app.util.Sharing
import com.studytimelapse.app.util.Storage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZoneId

@Composable
fun TimelapsesScreen(container: AppContainer, nav: NavController) {
    val items by container.sessions.timelapses.collectAsStateWithLifecycle(initialValue = null)
    val bytes by container.sessions.timelapseBytes.collectAsStateWithLifecycle(initialValue = 0L)
    val list = items

    Page(title = "Timelapses") {
        if (list == null) return@Page
        if (list.isEmpty()) {
            EmptyState(
                "Your future self is going to love this library.",
                "Every session with the camera on ends up here. Videos never leave your phone unless you share them.",
                action = "Start a session",
                onAction = { nav.navigate(Routes.SETUP) },
            )
            return@Page
        }
        Text(
            "${list.size} videos · ${Storage.formatBytes(bytes)} on this phone",
            style = MaterialTheme.typography.bodyMedium,
            color = LocalExtraColors.current.slate,
        )
        list.forEach { s -> TimelapseCard(container, nav, s) }
    }
}

@Composable
private fun TimelapseCard(container: AppContainer, nav: NavController, s: SessionEntity) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val thumb = rememberThumbnail(s.thumbnailPath)
    val ready = s.timelapseStatus == TimelapseStatus.READY

    SurfaceCard(onClick = { if (ready) nav.navigate(Routes.player(s.id)) else nav.navigate(Routes.session(s.id)) }, padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            thumb?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            when (s.timelapseStatus) {
                TimelapseStatus.PROCESSING -> CircularProgressIndicator(color = Color.White)
                TimelapseStatus.FAILED -> Text("Couldn't create video", color = Color.White)
                else -> Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
            }
        }
        Row(Modifier.padding(start = Spacing.xl, end = Spacing.s, top = Spacing.m, bottom = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f).semantics(mergeDescendants = true) {
                    contentDescription = "${s.title ?: s.subject}, ${Format.spokenDuration(s.studyMs)} studied, ${Dates.date(s.startUtc, ZoneId.systemDefault())}"
                },
            ) {
                Text(s.title ?: s.subject, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${Format.duration(s.studyMs)} · ${Dates.date(s.startUtc, ZoneId.systemDefault())}" +
                        if (s.timelapseDurationMs > 0) " · ▶ ${Format.shortDuration(s.timelapseDurationMs)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalExtraColors.current.slate,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (ready) DropdownMenuItem(text = { Text("Play") }, onClick = { menu = false; nav.navigate(Routes.player(s.id)) })
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                    if (ready) DropdownMenuItem(text = { Text("Share") }, onClick = {
                        menu = false
                        s.timelapsePath?.let { Sharing.shareVideo(context, File(it)) }
                    })
                    DropdownMenuItem(text = { Text("Session details") }, onClick = { menu = false; nav.navigate(Routes.session(s.id)) })
                    DropdownMenuItem(text = { Text("Delete video", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(s.title ?: s.subject) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    renaming = false
                    scope.launch { container.sessions.saveNotes(s.id, s.notes, name) }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this timelapse?",
            text = "Frees ${Storage.formatBytes(s.timelapseBytes)}. The ${Format.duration(s.studyMs)} study session stays in your statistics.",
            confirm = "Delete video",
            destructive = true,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                scope.launch { container.sessions.deleteTimelapse(s.id) }
            },
        )
    }
}

private val SPEEDS = listOf(0.5f, 1f, 2f, 4f, 8f, 16f)

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(container: AppContainer, nav: NavController, sessionId: String) {
    val session by container.sessions.observe(sessionId).collectAsStateWithLifecycle(initialValue = null)
    val context = LocalContext.current
    val s = session
    val path = s?.timelapsePath

    val player = remember { ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_OFF } }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1f) }
    var seeking by remember { mutableStateOf(false) }

    LaunchedEffect(path) {
        if (path != null) {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            player.prepare()
            player.playWhenReady = true
        }
    }
    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            if (!seeking) position = player.currentPosition
            duration = player.duration.coerceAtLeast(0)
            delay(200)
        }
    }

    Page(title = null, onBack = { nav.popBackStack() }) {
        if (s == null) return@Page
        if (path == null || s.timelapseStatus != TimelapseStatus.READY) {
            EmptyState("This timelapse isn't available.", "It may have been deleted. The study session is still in your history.")
            return@Page
        }
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        this.player = player
                    }
                },
                modifier = Modifier.fillMaxWidth().aspectRatio(9f / 12f),
            )
        }
        Kicker(Dates.date(s.startUtc, ZoneId.systemDefault()))
        Text(s.title ?: s.subject, style = MaterialTheme.typography.headlineSmall)
        Text("${Format.duration(s.studyMs)} of study", style = MaterialTheme.typography.bodyMedium, color = LocalExtraColors.current.slate)

        Slider(
            value = if (duration > 0) position.toFloat() / duration else 0f,
            onValueChange = {
                seeking = true
                position = (it * duration).toLong()
            },
            onValueChangeFinished = {
                player.seekTo(position)
                seeking = false
            },
            modifier = Modifier.semantics { contentDescription = "Seek" },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Format.shortDuration(position), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(Format.shortDuration(duration), style = MaterialTheme.typography.labelMedium)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            FilledIconButton(
                onClick = {
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    if (playing) player.pause() else player.play()
                },
                modifier = Modifier.size(64.dp),
            ) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (playing) "Pause" else "Play")
            }
        }
        Kicker("Speed")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            SPEEDS.forEach { v ->
                ChoicePill(if (v < 1f) "0.5×" else "${v.toInt()}×", speed == v, {
                    speed = v
                    player.setPlaybackSpeed(v)
                })
            }
        }
    }
}
