package com.studytimelapse.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.studytimelapse.app.AppContainer
import com.studytimelapse.app.data.prefs.CameraFacing
import com.studytimelapse.app.data.prefs.CaptureOrientation
import com.studytimelapse.app.data.prefs.StudyScreenMode
import com.studytimelapse.app.domain.Format
import com.studytimelapse.app.domain.TimelapseMath
import com.studytimelapse.app.service.StartRequest
import com.studytimelapse.app.ui.components.ChoicePill
import com.studytimelapse.app.ui.components.Kicker
import com.studytimelapse.app.ui.components.PrimaryButton
import com.studytimelapse.app.ui.components.SurfaceCard
import com.studytimelapse.app.ui.navigation.Routes
import com.studytimelapse.app.ui.theme.LocalExtraColors
import com.studytimelapse.app.ui.theme.Spacing
import com.studytimelapse.app.util.Storage
import kotlinx.coroutines.launch

private val TARGETS = listOf(null, 25, 45, 60, 90, 120, 180, 240)
private val INTERVALS = listOf(1, 2, 5, 10, 30)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionSetupScreen(container: AppContainer, nav: NavController) {
    val context = LocalContext.current
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val subjects by container.subjects.subjects.collectAsStateWithLifecycle(initialValue = emptyList())
    val s = settings ?: return
    val scope = rememberCoroutineScope()

    var subject by rememberSaveable { mutableStateOf(s.defaultSubject) }
    var title by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf<Int?>(120) }
    var interval by rememberSaveable { mutableIntStateOf(s.effectiveIntervalSec) }
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var timelapse by rememberSaveable { mutableStateOf(s.timelapseEnabled) }
    var backCamera by rememberSaveable { mutableStateOf(s.cameraFacing == CameraFacing.BACK) }
    var screenMode by rememberSaveable { mutableStateOf(s.screenMode) }
    var addingSubject by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCameraPermission = it }
    val deviceRotation = rememberDeviceRotation()

    LaunchedEffect(subjects) {
        if (subject.isBlank()) subject = subjects.firstOrNull()?.name ?: "Study"
    }

    val controller = remember {
        LifecycleCameraController(context).apply { setEnabledUseCases(0) } // preview only
    }
    val useCamera = timelapse && hasCameraPermission

    Page(title = "New session", onBack = { nav.popBackStack() }) {
        // ---- Camera preview -------------------------------------------------------------
        if (timelapse) {
            if (hasCameraPermission) {
                CameraPreview(controller, backCamera, onSwitch = { backCamera = !backCamera })
                Text(
                    "Position your desk inside the frame. Tap to focus.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalExtraColors.current.slate,
                )
            } else {
                SurfaceCard {
                    Text("Camera permission needed for timelapses", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The camera is only used to record your study timelapse, which stays on this phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalExtraColors.current.slate,
                    )
                    TextButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                }
            }
        }

        // ---- Subject ----------------------------------------------------------------------
        Kicker("Subject")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            subjects.forEach { sub -> ChoicePill(sub.name, sub.name == subject, { subject = sub.name }) }
            ChoicePill("+ Add", false, { addingSubject = true })
        }
        OutlinedTextField(
            value = title,
            onValueChange = { title = it.take(80) },
            label = { Text("Session title (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // ---- Target -----------------------------------------------------------------------
        Kicker("Target")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            TARGETS.forEach { m -> ChoicePill(m?.let { Format.duration(it * 60_000L) } ?: "Open", target == m, { target = m }) }
        }

        // ---- Timelapse --------------------------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Timelapse", style = MaterialTheme.typography.titleMedium)
                Text("Record one frame every few seconds", style = MaterialTheme.typography.bodySmall, color = LocalExtraColors.current.slate)
            }
            Switch(checked = timelapse, onCheckedChange = { timelapse = it })
        }
        if (timelapse) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                INTERVALS.forEach { sec -> ChoicePill("${sec}s", interval == sec, { interval = sec }) }
            }
            val planMs = (target ?: 120) * 60_000L
            val frames = TimelapseMath.expectedFrames(planMs, interval)
            val bytes = TimelapseMath.estimatedBytes(frames, s.bitrate())
            Text(
                "1 frame every ${interval}s · ${TimelapseMath.speedUp(interval)}× faster · " +
                    "${Format.duration(planMs)} → ${Format.shortDuration(TimelapseMath.videoLengthMs(frames))} video, ≈ ${Storage.formatBytes(bytes)}" +
                    if (s.batterySaver) " · Battery Saver on" else "",
                style = MaterialTheme.typography.bodySmall,
                color = LocalExtraColors.current.slate,
            )

            // ---- Screen behaviour -------------------------------------------------------------
            Kicker("While studying")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                ChoicePill("Dim screen (reliable)", screenMode == StudyScreenMode.DIM, { screenMode = StudyScreenMode.DIM })
                ChoicePill("Lock screen (test first)", screenMode == StudyScreenMode.SCREEN_OFF, { screenMode = StudyScreenMode.SCREEN_OFF })
            }
            Text(
                when {
                    screenMode == StudyScreenMode.DIM ->
                        "The screen stays on at minimum brightness with a black display, which works on every phone. On OLED screens this uses very little power."
                    s.screenOffCapability == 1 -> "Your phone passed the screen-off test: recording continues while locked."
                    s.screenOffCapability == 0 -> "Your phone stopped the camera when locked during the test. Dim mode is recommended."
                    else -> "Some phones stop the camera when locked. Run the test in Settings → Screen-off test. We'll alert you if frames stop."
                },
                style = MaterialTheme.typography.bodySmall,
                color = LocalExtraColors.current.slate,
            )
            BatteryAndStorageWarnings(context, s.showBatteryWarning, bytes, onDismissBattery = {
                scope.launch { container.settings.update { it.copy(showBatteryWarning = false) } }
            })
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(Spacing.s))
        PrimaryButton(
            text = if (starting) "Starting…" else "Start",
            icon = Icons.Rounded.PlayArrow,
            enabled = !starting && subject.isNotBlank(),
            onClick = {
                starting = true
                // Release the preview camera so the recording service can open it.
                controller.unbind()
                val rotation = when (s.orientation) {
                    CaptureOrientation.AUTO -> deviceRotation
                    CaptureOrientation.PORTRAIT -> Surface.ROTATION_0
                    CaptureOrientation.LANDSCAPE -> Surface.ROTATION_90
                }
                scope.launch {
                    try {
                        container.settings.update {
                            it.copy(
                                captureIntervalSec = if (it.batterySaver) it.captureIntervalSec else interval,
                                cameraFacing = if (backCamera) CameraFacing.BACK else CameraFacing.FRONT,
                                screenMode = screenMode,
                                timelapseEnabled = timelapse,
                                defaultSubject = subject,
                            )
                        }
                        container.sessionManager.start(
                            StartRequest(
                                subject = subject,
                                title = title.ifBlank { null },
                                targetMinutes = target,
                                timelapse = useCamera,
                                intervalSec = if (s.batterySaver) maxOf(interval, 10) else interval,
                                resolution = s.effectiveResolution,
                                bitrate = s.bitrate(),
                                backCamera = backCamera,
                                targetRotation = rotation,
                                screenMode = screenMode,
                            ),
                        )
                        nav.navigate(Routes.study()) { popUpTo(Routes.HOME) }
                    } catch (e: Exception) {
                        error = e.message ?: "Could not start the session."
                        starting = false
                    }
                }
            },
        )
    }

    if (addingSubject) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addingSubject = false },
            title = { Text("New subject") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, label = { Text("Name") })
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.subjects.add(name) }
                    subject = name.trim()
                    addingSubject = false
                }, enabled = name.isNotBlank()) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addingSubject = false }) { Text("Cancel") } },
        )
    }

    // The preview is bound to this screen only; it is released before the session starts.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(useCamera, lifecycleOwner) {
        if (useCamera && !starting) controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.unbind() }
    }
}

@Composable
private fun CameraPreview(controller: LifecycleCameraController, backCamera: Boolean, onSwitch: () -> Unit) {
    val context = LocalContext.current
    var exposureRange by remember { mutableStateOf<IntRange?>(null) }
    var exposure by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(backCamera) {
        controller.cameraSelector = if (backCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        controller.initializationFuture.addListener({
            val state = controller.cameraInfo?.exposureState
            exposureRange = if (state != null && state.isExposureCompensationSupported) {
                state.exposureCompensationRange.lower..state.exposureCompensationRange.upper
            } else null
            exposure = 0f
        }, ContextCompat.getMainExecutor(context))
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(28.dp))
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    this.controller = controller
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        FilledTonalIconButton(
            onClick = onSwitch,
            modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.m),
        ) {
            Icon(Icons.Outlined.Cameraswitch, contentDescription = if (backCamera) "Switch to front camera" else "Switch to back camera")
        }
    }
    exposureRange?.let { range ->
        if (range.first < range.last) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Exposure", style = MaterialTheme.typography.labelMedium, color = LocalExtraColors.current.slate)
                Slider(
                    value = exposure,
                    onValueChange = {
                        exposure = it
                        controller.cameraControl?.setExposureCompensationIndex(it.toInt())
                    },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    modifier = Modifier.weight(1f).padding(start = Spacing.m),
                )
            }
        }
    }
}

@Composable
private fun BatteryAndStorageWarnings(context: Context, showBattery: Boolean, estimatedBytes: Long, onDismissBattery: () -> Unit) {
    val bm = context.getSystemService(BatteryManager::class.java)
    val level = remember { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }
    val charging = remember { bm.isCharging }
    val free = remember { Storage.freeBytes(context) }
    if (showBattery && !charging && level in 0..29) {
        SurfaceCard(color = MaterialTheme.colorScheme.surfaceVariant) {
            Text("Battery at $level%", style = MaterialTheme.typography.titleSmall)
            Text(
                "Recommended: 30%+ or plugged in. Long camera sessions use significant battery and can warm the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = LocalExtraColors.current.slate,
            )
            TextButton(onClick = onDismissBattery) { Text("Don't show again") }
        }
    }
    if (free < estimatedBytes * 2 + Storage.LOW_SPACE_WARN_BYTES) {
        SurfaceCard(color = MaterialTheme.colorScheme.surfaceVariant) {
            Text("Low storage: ${Storage.formatBytes(free)} free", style = MaterialTheme.typography.titleSmall)
            Text(
                "The timelapse may stop early. Your study time is always saved. Free space in Settings → Storage.",
                style = MaterialTheme.typography.bodySmall,
                color = LocalExtraColors.current.slate,
            )
        }
    }
}

/** Physical device rotation from the accelerometer (works even though the UI is portrait). */
@Composable
private fun rememberDeviceRotation(): Int {
    val context = LocalContext.current
    var rotation by remember { mutableIntStateOf(Surface.ROTATION_0) }
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
    return rotation
}
