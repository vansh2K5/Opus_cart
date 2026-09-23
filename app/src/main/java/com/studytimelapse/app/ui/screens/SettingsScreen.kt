package com.studytimelapse.app.ui.screens

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.studytimelapse.app.AppContainer
import com.studytimelapse.app.BuildConfig
import com.studytimelapse.app.data.prefs.AppSettings
import com.studytimelapse.app.data.prefs.CameraFacing
import com.studytimelapse.app.data.prefs.CaptureOrientation
import com.studytimelapse.app.data.prefs.CaptureResolution
import com.studytimelapse.app.data.prefs.StudyScreenMode
import com.studytimelapse.app.data.prefs.ThemeMode
import com.studytimelapse.app.data.prefs.VideoQuality
import com.studytimelapse.app.domain.Format
import com.studytimelapse.app.service.CameraStatus
import com.studytimelapse.app.ui.components.ChoicePill
import com.studytimelapse.app.ui.components.Hairline
import com.studytimelapse.app.ui.components.KeyValueRow
import com.studytimelapse.app.ui.components.Kicker
import com.studytimelapse.app.ui.components.PrimaryButton
import com.studytimelapse.app.ui.components.SectionHeader
import com.studytimelapse.app.ui.components.SecondaryButton
import com.studytimelapse.app.ui.components.SurfaceCard
import com.studytimelapse.app.ui.navigation.Routes
import com.studytimelapse.app.ui.theme.LocalExtraColors
import com.studytimelapse.app.ui.theme.Spacing
import com.studytimelapse.app.util.Sharing
import com.studytimelapse.app.util.Storage
import com.studytimelapse.app.worker.ReminderWorker
import com.studytimelapse.app.worker.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(container: AppContainer, nav: NavController) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val s = settings ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun update(block: (AppSettings) -> AppSettings) {
        scope.launch {
            container.settings.update(block)
            ReminderWorker.schedule(context, container.settings.current())
        }
    }

    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { export(container, context, it, csv = true) } }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { export(container, context, it, csv = false) } }

    var confirmWipe by remember { mutableStateOf(false) }
    var deletingAccount by remember { mutableStateOf(false) }
    var zoneDialog by remember { mutableStateOf(false) }

    Page(title = "Settings", onBack = { nav.popBackStack() }) {
        // ---------------------------------------------------------------- Study
        SectionHeader("Study")
        SurfaceCard {
            var threshold by remember(s.streakThresholdMinutes) { mutableFloatStateOf(s.streakThresholdMinutes.toFloat()) }
            Text("Streak minimum: ${threshold.toInt()} min", style = MaterialTheme.typography.titleMedium)
            Text("A day counts toward your streak once you've studied this long.", style = MaterialTheme.typography.bodySmall, color = LocalExtraColors.current.slate)
            Slider(
                value = threshold,
                onValueChange = { threshold = (Math.round(it / 5f) * 5).toFloat() },
                onValueChangeFinished = { update { it.copy(streakThresholdMinutes = threshold.toInt()) } },
                valueRange = 5f..180f,
            )
            Hairline()
            TextButton(onClick = { nav.navigate(Routes.GOALS) }) { Text("Daily, weekly and monthly goals") }
        }

        // ---------------------------------------------------------------- Camera
        SectionHeader("Camera & timelapse")
        SurfaceCard {
            Kicker("Default camera")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                ChoicePill("Back", s.cameraFacing == CameraFacing.BACK, { update { it.copy(cameraFacing = CameraFacing.BACK) } })
                ChoicePill("Front", s.cameraFacing == CameraFacing.FRONT, { update { it.copy(cameraFacing = CameraFacing.FRONT) } })
            }
            Spacer(Modifier.height(Spacing.m))
            Kicker("Capture interval")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                listOf(1, 2, 5, 10, 30).forEach { v -> ChoicePill("${v}s", s.captureIntervalSec == v, { update { it.copy(captureIntervalSec = v) } }) }
            }
            Spacer(Modifier.height(Spacing.m))
            Kicker("Resolution")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                CaptureResolution.entries.forEach { r -> ChoicePill(r.label, s.resolution == r, { update { it.copy(resolution = r) } }) }
            }
            Spacer(Modifier.height(Spacing.m))
            Kicker("Quality")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                VideoQuality.entries.forEach { q -> ChoicePill(q.label, s.quality == q, { update { it.copy(quality = q) } }) }
            }
            Spacer(Modifier.height(Spacing.m))
            Kicker("Orientation")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                CaptureOrientation.entries.forEach { o -> ChoicePill(o.label, s.orientation == o, { update { it.copy(orientation = o) } }) }
            }
            Spacer(Modifier.height(Spacing.m))
            ToggleRow(
                "Battery Saver Timelapse",
                "One frame every 10s or more, 480p, low bitrate. Much lower power and heat.",
                s.batterySaver,
            ) { v -> update { it.copy(batterySaver = v) } }
            ToggleRow("Record timelapse by default", null, s.timelapseEnabled) { v -> update { it.copy(timelapseEnabled = v) } }
            Text(
                "Audio: timelapses are silent. At one frame every few seconds sound is meaningless, so the app never asks for the microphone.",
                style = MaterialTheme.typography.bodySmall,
                color = LocalExtraColors.current.slate,
            )
        }
        SurfaceCard {
            Kicker("Screen while studying")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                ChoicePill("Dim (reliable)", s.screenMode == StudyScreenMode.DIM, { update { it.copy(screenMode = StudyScreenMode.DIM) } })
                ChoicePill("Lock screen", s.screenMode == StudyScreenMode.SCREEN_OFF, { update { it.copy(screenMode = StudyScreenMode.SCREEN_OFF) } })
            }
            Spacer(Modifier.height(Spacing.s))
            Text(
                when (s.screenOffCapability) {
                    1 -> "✓ This phone keeps recording with the screen locked."
                    0 -> "✗ This phone stopped the camera when locked. Use Dim."
                    else -> "Not tested on this phone yet."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { nav.navigate(Routes.SCREEN_OFF_TEST) }) { Text("Run the 30-second screen-off test") }
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }) { Text("Battery optimisation settings") }
            Text(
                "Some manufacturers (Xiaomi, Huawei, OnePlus, Samsung…) stop apps aggressively. If recordings stop, allow StudyTimelapse to run unrestricted. See dontkillmyapp.com for your phone.",
                style = MaterialTheme.typography.bodySmall,
                color = LocalExtraColors.current.slate,
            )
        }

        // ---------------------------------------------------------------- Notifications
        SectionHeader("Notifications")
        SurfaceCard {
            ToggleRow("Goal completed", null, s.notifyGoals) { v -> update { it.copy(notifyGoals = v) } }
            ToggleRow("Streak milestones", null, s.notifyStreaks) { v -> update { it.copy(notifyStreaks = v) } }
            ToggleRow("Friend competition in reminders", null, s.notifyFriends) { v -> update { it.copy(notifyFriends = v) } }
            Hairline()
            ToggleRow("Study reminder", "One gentle reminder on the days you choose.", s.remindersEnabled) { v -> update { it.copy(remindersEnabled = v) } }
            if (s.remindersEnabled) {
                TextButton(onClick = {
                    TimePickerDialog(context, { _, h, m -> update { it.copy(reminderMinuteOfDay = h * 60 + m) } },
                        s.reminderMinuteOfDay / 60, s.reminderMinuteOfDay % 60, android.text.format.DateFormat.is24HourFormat(context)).show()
                }) { Text("Time: %02d:%02d".format(s.reminderMinuteOfDay / 60, s.reminderMinuteOfDay % 60)) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    DayOfWeek.entries.forEachIndexed { i, dow ->
                        val bit = 1 shl i
                        ChoicePill(dow.getDisplayName(TextStyle.SHORT, Locale.getDefault()), s.reminderDays and bit != 0, {
                            update { it.copy(reminderDays = it.reminderDays xor bit) }
                        })
                    }
                }
            }
        }

        // ---------------------------------------------------------------- Appearance
        SectionHeader("Appearance")
        SurfaceCard {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                ThemeMode.entries.forEach { m ->
                    ChoicePill(m.name.lowercase().replaceFirstChar { it.uppercase() }, s.themeMode == m, { update { it.copy(themeMode = m) } })
                }
            }
            Spacer(Modifier.height(Spacing.m))
            KeyValueRow("Time zone", s.zone.id + if (s.timeZoneId.isBlank()) " (device)" else "")
            TextButton(onClick = { zoneDialog = true }) { Text("Change time zone") }
        }

        // ---------------------------------------------------------------- Privacy
        SectionHeader("Privacy")
        SurfaceCard {
            Text(
                "Timelapses, thumbnails and notes stay on this phone. They're never uploaded and are excluded from cloud backups. Only study statistics are shared with your friend.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(Spacing.s))
            ToggleRow("Share subjects with friend", "Off: sessions show as \"Study\".", s.shareSubjectsWithFriend) { v ->
                update { it.copy(shareSubjectsWithFriend = v) }
            }
            ToggleRow("Show friend when I'm studying", null, s.shareActivityWithFriend) { v -> update { it.copy(shareActivityWithFriend = v) } }
            TextButton(onClick = { openAppSettings(context) }) { Text("Camera & notification permissions") }
        }

        // ---------------------------------------------------------------- Data
        SectionHeader("Your data")
        SurfaceCard {
            TextButton(onClick = { nav.navigate(Routes.STORAGE) }) { Text("Storage & timelapses") }
            TextButton(onClick = { exportCsv.launch("study-history.csv") }) { Text("Export history (CSV)") }
            TextButton(onClick = { exportJson.launch("study-history.json") }) { Text("Export history (JSON)") }
            TextButton(onClick = { confirmWipe = true }) { Text("Delete all local data", color = MaterialTheme.colorScheme.error) }
        }

        // ---------------------------------------------------------------- Account
        SectionHeader("Account")
        SurfaceCard {
            val user = container.auth.currentUser
            when {
                !container.auth.available -> Text("Accounts are not configured in this build (on-device mode).", style = MaterialTheme.typography.bodyMedium)
                user == null -> TextButton(onClick = { nav.navigate(Routes.AUTH) }) { Text("Sign in or create account") }
                else -> {
                    KeyValueRow("Email", user.email ?: "—")
                    TextButton(onClick = { container.auth.signOut() }) { Text("Sign out") }
                    TextButton(onClick = { deletingAccount = true }) { Text("Delete account", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        Text("StudyTimelapse ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = LocalExtraColors.current.slate)
    }

    if (confirmWipe) {
        ConfirmDialog(
            title = "Delete all local data?",
            text = "Every session, timelapse, goal and achievement on this phone will be deleted. Your cloud account (if any) is not affected. This can't be undone.",
            confirm = "Delete everything",
            destructive = true,
            onDismiss = { confirmWipe = false },
            onConfirm = {
                confirmWipe = false
                scope.launch {
                    if (container.sessionManager.live.value != null) container.sessionManager.discard()
                    container.sessions.deleteAllLocal()
                    container.settings.clear()
                    (context as? Activity)?.recreate()
                }
            },
        )
    }
    if (deletingAccount) DeleteAccountDialog(container) { deletingAccount = false }
    if (zoneDialog) ZoneDialog(s.timeZoneId, onDismiss = { zoneDialog = false }) { z ->
        zoneDialog = false
        update { it.copy(timeZoneId = z) }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalExtraColors.current.slate) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun export(container: AppContainer, context: android.content.Context, uri: Uri, csv: Boolean) {
    container.appScope.launch {
        val ok = runCatching {
            val sessions = container.db.sessions().finished()
            val zone = container.settings.current().zone
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    if (csv) Sharing.writeCsv(sessions, zone, out) else Sharing.writeJson(sessions, zone, out)
                } ?: error("Could not open file")
            }
        }.isSuccess
        withContext(Dispatchers.Main) {
            Toast.makeText(context, if (ok) "Exported" else "Export failed", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun ZoneDialog(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    val valid = text.isBlank() || runCatching { ZoneId.of(text.trim()) }.isSuccess
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Time zone") },
        text = {
            Column {
                Text("Streaks and days use this zone. Leave empty to follow the phone.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(Spacing.s))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("e.g. Asia/Kolkata") },
                    singleLine = true,
                    isError = !valid,
                )
                Text("Device: ${ZoneId.systemDefault().id}", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onPick(text.trim()) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeleteAccountDialog(container: AppContainer, onDone: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text("Delete your account?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(
                    "This permanently deletes your account and everything stored in the cloud: profile, synced sessions, friendship, challenges and invite codes. Data on this phone stays until you delete it separately.",
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Confirm with your password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && password.isNotEmpty(), onClick = {
                busy = true
                scope.launch {
                    val result = container.auth.reauthenticate(password).mapCatching {
                        container.social.deleteAllMyData()
                        container.auth.deleteAuthUser().getOrThrow()
                    }
                    busy = false
                    result.onSuccess { onDone() }.onFailure { error = it.message }
                }
            }) { Text(if (busy) "Deleting…" else "Delete account", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDone, enabled = !busy) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------------ Storage

@Composable
fun StorageScreen(container: AppContainer, nav: NavController) {
    val context = LocalContext.current
    val items by container.sessions.timelapses.collectAsStateWithLifecycle(initialValue = emptyList())
    val bytes by container.sessions.timelapseBytes.collectAsStateWithLifecycle(initialValue = 0L)
    val free = remember(bytes) { Storage.freeBytes(context) }
    val scope = rememberCoroutineScope()
    var confirmAll by remember { mutableStateOf(false) }
    Page(title = "Storage", onBack = { nav.popBackStack() }) {
        SurfaceCard {
            KeyValueRow("Timelapses", Storage.formatBytes(bytes))
            Hairline()
            KeyValueRow("Free on this phone", Storage.formatBytes(free))
            if (free < 1024L * 1024 * 1024) {
                Text("Storage is getting low. Delete old videos below; study statistics are always kept.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Deleting a video never deletes the study session or its statistics.", style = MaterialTheme.typography.bodyMedium, color = LocalExtraColors.current.slate)
        items.filter { it.timelapseBytes > 0 }.sortedByDescending { it.timelapseBytes }.forEach { s ->
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title ?: s.subject, style = MaterialTheme.typography.titleMedium)
                        Text("${Dates.date(s.startUtc, ZoneId.systemDefault())} · ${Format.duration(s.studyMs)} · ${Storage.formatBytes(s.timelapseBytes)}", style = MaterialTheme.typography.bodySmall, color = LocalExtraColors.current.slate)
                    }
                    TextButton(onClick = { scope.launch { container.sessions.deleteTimelapse(s.id) } }) { Text("Delete") }
                }
            }
        }
        if (bytes > 0) SecondaryButton("Delete all timelapses", { confirmAll = true })
    }
    if (confirmAll) {
        ConfirmDialog(
            title = "Delete all timelapses?",
            text = "Frees ${Storage.formatBytes(bytes)}. All study sessions and statistics are kept.",
            confirm = "Delete all videos",
            destructive = true,
            onDismiss = { confirmAll = false },
            onConfirm = {
                confirmAll = false
                scope.launch { container.sessions.deleteAllTimelapses() }
            },
        )
    }
}

// ------------------------------------------------------------------------ Screen-off test

@Composable
fun ScreenOffTestScreen(container: AppContainer, nav: NavController) {
    val context = LocalContext.current
    val test by container.sessionManager.screenOffTest.collectAsStateWithLifecycle()
    val live by container.sessionManager.live.collectAsStateWithLifecycle()
    val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    DisposableEffect(Unit) {
        onDispose { if (container.sessionManager.screenOffTest.value?.running == true) container.sessionManager.stopScreenOffTest() }
    }
    Page(title = "Screen-off test", onBack = { nav.popBackStack() }) {
        Text(
            "Android lets a camera app keep recording in a foreground service, but some phones still stop the camera when the screen is locked. This test tells you what your phone does.",
            style = MaterialTheme.typography.bodyLarge,
        )
        SurfaceCard {
            Text("1. Tap Start test.", style = MaterialTheme.typography.bodyLarge)
            Text("2. Lock the phone with the power button for about 20 seconds.", style = MaterialTheme.typography.bodyLarge)
            Text("3. Unlock, come back here and tap See result.", style = MaterialTheme.typography.bodyLarge)
            Text("Test frames are deleted immediately.", style = MaterialTheme.typography.bodySmall, color = LocalExtraColors.current.slate)
        }
        val t = test
        when {
            live != null -> Text("Finish your current study session before running the test.", color = MaterialTheme.colorScheme.error)
            !hasCamera -> Text("Allow camera access first.", color = MaterialTheme.colorScheme.error)
            t == null || (!t.running && t.works == null && t.frames == 0) -> PrimaryButton("Start test", { container.sessionManager.startScreenOffTest() })
            t.running -> {
                SurfaceCard {
                    KeyValueRow("Camera", t.cameraStatus.label)
                    Hairline()
                    KeyValueRow("Frames captured", "${t.frames}")
                    Hairline()
                    KeyValueRow("Frames while screen off", "${t.framesScreenOff}")
                    Hairline()
                    KeyValueRow("Screen-off time", "${t.screenOffMs / 1000}s")
                }
                PrimaryButton("See result", { container.sessionManager.stopScreenOffTest() })
            }
            else -> {
                SurfaceCard(color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(
                        when (t.works) {
                            true -> "✓ Your phone keeps recording with the screen off."
                            false -> "✗ Your phone stopped the camera while locked."
                            null -> if (t.cameraStatus == CameraStatus.ERROR) "The camera couldn't start." else "The screen wasn't off long enough to tell. Try again and keep it locked ~20 s."
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        "${t.framesScreenOff} frames in ${t.screenOffMs / 1000}s with the screen off.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (t.works == false) {
                        Text(
                            "Use Dim mode: the screen stays on at minimum brightness with a black UI. On OLED phones that costs very little battery.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                SecondaryButton("Run again", {
                    container.sessionManager.clearScreenOffTest()
                    container.sessionManager.startScreenOffTest()
                })
            }
        }
    }
}

private fun openAppSettings(context: android.content.Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
}
