package com.studytimelapse.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.studytimelapse.app.R
import com.studytimelapse.app.StudyApp
import com.studytimelapse.app.camera.CapturePipeline
import com.studytimelapse.app.notifications.Channels
import com.studytimelapse.app.notifications.Notifier
import com.studytimelapse.app.timelapse.TimelapseEncoder
import com.studytimelapse.app.util.Storage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service of type `camera` that hosts the timelapse camera while studying.
 *
 * Why a foreground service: since Android 9 an app without a visible activity cannot use the
 * camera; since Android 11 camera access from a foreground service is "while-in-use" and only
 * granted when the service was started while the app was visible; since Android 14 the service
 * must declare `foregroundServiceType="camera"` and hold FOREGROUND_SERVICE_CAMERA. This service
 * satisfies all of these, keeps a partial wake lock so frames keep flowing with the screen off,
 * and runs a watchdog that reports (never hides) any interruption.
 */
class StudySessionService : LifecycleService(), CameraHost {

    companion object {
        private const val TAG = "StudySessionService"
        const val ACTION_START_CAPTURE = "com.studytimelapse.action.START_CAPTURE"
        const val ACTION_USER_PAUSE = "com.studytimelapse.action.PAUSE"
        const val ACTION_USER_RESUME = "com.studytimelapse.action.RESUME"
        private const val WATCHDOG_PERIOD_MS = 5_000L
        private const val MIN_STALL_MS = 20_000L
        private const val MAX_THERMAL_INTERVAL_SEC = 30
        private const val WAKELOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L
    }

    private val app get() = application as StudyApp
    private val manager get() = app.container.sessionManager
    private val notifier get() = app.container.notifier

    private var config: CaptureConfig? = null
    private var pipeline: CapturePipeline? = null
    private var watchdog: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var finishing = false

    // Reasons the camera may be off while the session is running. The camera runs only when all
    // of them are false.
    private var userPaused = false
    private var thermalPaused = false
    private var storageStopped = false
    private var stallReported = false
    private var thermalWarned = false
    private var lowBatteryWarned = false
    private var storageWarned = false
    private var screenOffSince = 0L

    override val sessionId: String get() = config?.sessionId ?: ""

    // ---------------------------------------------------------------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_CAPTURE -> handleStart(intent)
            ACTION_USER_PAUSE -> lifecycleScope.launch {
                manager.pause()
                if (pipeline == null) stopSelfResult(startId)
            }
            ACTION_USER_RESUME -> lifecycleScope.launch {
                manager.resume()
                if (pipeline == null) stopSelfResult(startId)
            }
            else -> if (pipeline == null) stopSelfResult(startId)
        }
        // Never let the system restart us silently in the background: Android would not allow
        // camera access then anyway. The session manager restores state and asks the user.
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val cfg = CaptureConfig.from(intent)
        if (cfg == null) {
            stopSelf()
            return
        }
        if (!goForeground(cfg)) return
        // The session may have been finished or discarded while this start request was queued.
        val live = manager.live.value
        if (!cfg.isTest && live?.sessionId != cfg.sessionId) {
            if (pipeline == null) stopEverything()
            return
        }
        val existing = pipeline
        if (existing != null) {
            if (config?.sessionId == cfg.sessionId) {
                userPaused = false
                updateCamera()
                return
            }
            existing.abort()
        }
        config = cfg
        // Paused before the service came up? Then don't open the camera yet.
        userPaused = !cfg.isTest && live?.running == false
        thermalPaused = false
        storageStopped = false
        finishing = false
        val outputDir = if (cfg.isTest) Storage.testDir(this) else Storage.sessionDir(this, cfg.sessionId)
        pipeline = CapturePipeline(this, this, cfg, outputDir, pipelineListener).also { it.start(openCamera = !userPaused) }
        manager.attachHost(this)
        acquireWakeLock()
        registerMonitors()
        startWatchdog()
    }

    private fun goForeground(cfg: CaptureConfig): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            manager.onCaptureStopped(cfg.sessionId, "Camera permission is not granted.")
            stopSelf()
            return false
        }
        val notification = if (cfg.isTest) {
            NotificationCompat.Builder(this, Channels.SESSION)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Testing screen-off recording")
                .setContentText("Lock your phone for about 20 seconds, then come back.")
                .setOngoing(true)
                .setSilent(true)
                .build()
        } else {
            val live = manager.live.value
            notifier.sessionNotification(
                live?.subject ?: "Study session",
                running = live?.running ?: true,
                studyMs = live?.studyMsAt(SystemClock.elapsedRealtime()) ?: 0,
                statusLine = "Starting camera…",
            )
        }
        return try {
            ServiceCompat.startForeground(this, Notifier.ID_SESSION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException / SecurityException: Android refused.
            Log.e(TAG, "startForeground refused", e)
            manager.onCaptureStopped(cfg.sessionId, "Android did not allow the camera to start in the background.")
            stopSelf()
            false
        }
    }

    // ---------------------------------------------------------------------------------------
    // CameraHost (called by SessionManager, possibly off the main thread)
    // ---------------------------------------------------------------------------------------

    override fun pauseCapture() = onMain {
        userPaused = true
        updateCamera()
    }

    override fun resumeCapture() = onMain {
        userPaused = false
        stallReported = false
        updateCamera()
    }

    override fun finishCapture() = onMain {
        val p = pipeline
        val id = sessionId
        finishing = true
        teardownMonitors()
        if (p == null) {
            manager.onCaptureFinished(id)
            stopEverything()
            return@onMain
        }
        p.finish {
            manager.onCaptureFinished(id)
            stopEverything()
        }
    }

    override fun abortCapture() = onMain {
        finishing = true
        teardownMonitors()
        pipeline?.abort()
        stopEverything()
    }

    private fun onMain(block: () -> Unit) {
        lifecycleScope.launch { block() }
    }

    private fun stopEverything() {
        pipeline = null
        manager.detachHost(this)
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        val p = pipeline
        if (p != null && !finishing) {
            // Destroyed without being asked: keep what was recorded and tell the truth.
            val id = sessionId
            p.finish { }
            manager.onCaptureStopped(id, "Android stopped the camera service.")
        }
        pipeline = null
        teardownMonitors()
        releaseWakeLock()
        manager.detachHost(this)
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // Camera on/off policy
    // ---------------------------------------------------------------------------------------

    private fun updateCamera() {
        val p = pipeline ?: return
        val shouldRun = !userPaused && !thermalPaused && !storageStopped && !finishing
        if (shouldRun) {
            p.startCamera()
            acquireWakeLock()
            manager.onCameraStatus(sessionId, CameraStatus.STARTING, null)
        } else {
            p.stopCamera()
            // No camera = nothing to keep the CPU awake for. The timer does not need it.
            releaseWakeLock()
            val status = when {
                userPaused -> CameraStatus.PAUSED
                thermalPaused -> CameraStatus.STOPPED_THERMAL
                storageStopped -> CameraStatus.STOPPED_STORAGE
                else -> CameraStatus.PAUSED
            }
            manager.onCameraStatus(sessionId, status, null)
        }
    }

    private val pipelineListener = object : CapturePipeline.Listener {
        override fun onFrame(screenOff: Boolean) {
            manager.onFrameCaptured(sessionId, screenOff)
        }

        override fun onSegment(segment: TimelapseEncoder.Segment) {
            manager.onSegmentFinished(sessionId, segment)
        }

        override fun onCameraState(open: Boolean, waiting: Boolean, error: String?) {
            if (error != null) {
                manager.onCameraStatus(sessionId, CameraStatus.ERROR, error, gap = !stallReported)
                stallReported = true
            } else if (waiting && pipeline?.isCapturing == true) {
                manager.onCameraStatus(sessionId, CameraStatus.STALLED, "Waiting for the camera — another app may be using it.")
            }
        }

        override fun onFatal(reason: String) {
            val id = sessionId
            manager.onCaptureStopped(id, reason)
            // Keep any footage that was recorded, then release the camera and the service.
            val p = pipeline ?: return
            finishing = true
            teardownMonitors()
            p.finish {
                manager.onCaptureFinished(id)
                stopEverything()
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Watchdog: frame liveness, storage, wake lock renewal
    // ---------------------------------------------------------------------------------------

    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = lifecycleScope.launch {
            var ticks = 0
            while (isActive) {
                delay(WATCHDOG_PERIOD_MS)
                ticks++
                checkFrames()
                if (ticks % 12 == 0) checkStorage() // every minute
                if (ticks % 360 == 0 && pipeline?.isCapturing == true) acquireWakeLock() // renew every 30 min
            }
        }
    }

    private fun checkFrames() {
        val p = pipeline ?: return
        if (!p.isCapturing) return
        val silentMs = SystemClock.elapsedRealtime() - p.lastFrameElapsed
        val limit = maxOf(MIN_STALL_MS, p.currentIntervalSec * 3_000L)
        if (silentMs > limit && !stallReported) {
            stallReported = true
            val screenOff = !getSystemService(PowerManager::class.java).isInteractive
            val message = if (screenOff) {
                "Your phone stopped the camera while the screen was off. Use Dim mode instead of locking the screen."
            } else {
                "The camera stopped delivering frames."
            }
            if (screenOff && !(config?.isTest ?: false)) {
                lifecycleScope.launch { app.container.settings.update { it.copy(screenOffCapability = 0) } }
            }
            manager.onCameraStatus(sessionId, CameraStatus.STALLED, message, gap = true)
            if (config?.isTest != true) notifier.alert("Timelapse interrupted", "$message Your study timer is still running.")
        } else if (silentMs <= limit && stallReported) {
            stallReported = false
            manager.onCameraStatus(sessionId, CameraStatus.RECORDING, null)
            notifier.clearAlert()
        }
    }

    private fun checkStorage() {
        val free = Storage.freeBytes(this)
        when {
            free < Storage.LOW_SPACE_STOP_BYTES && !storageStopped -> {
                storageStopped = true
                updateCamera()
                notifier.alert(
                    "Storage almost full",
                    "The timelapse was stopped to protect your phone. Your study timer is still running, and the footage so far is saved.",
                )
            }
            free < Storage.LOW_SPACE_WARN_BYTES && !storageWarned -> {
                storageWarned = true
                manager.onWarning(sessionId, "Storage is getting low (${Storage.formatBytes(free)} free).")
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Thermal, battery and screen monitors
    // ---------------------------------------------------------------------------------------

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status -> onThermal(status) }

    private fun onThermal(status: Int) {
        val p = pipeline ?: return
        val id = sessionId
        when {
            status >= PowerManager.THERMAL_STATUS_CRITICAL && !thermalPaused -> {
                thermalPaused = true
                updateCamera()
                manager.onCameraStatus(id, CameraStatus.STOPPED_THERMAL, null, gap = true)
                notifier.alert(
                    "Your phone is very hot",
                    "We've paused the timelapse to protect the device. Your study timer keeps running; recording resumes when it cools down.",
                )
            }
            status >= PowerManager.THERMAL_STATUS_SEVERE && !thermalWarned -> {
                thermalWarned = true
                val newInterval = minOf(MAX_THERMAL_INTERVAL_SEC, p.currentIntervalSec * 2)
                p.setIntervalSec(newInterval)
                manager.onIntervalChanged(id, newInterval)
                val msg = "Your phone is getting warm. We've reduced capture frequency to protect the device."
                manager.onWarning(id, msg)
                notifier.alert("Phone getting warm", msg)
            }
            status <= PowerManager.THERMAL_STATUS_MODERATE && thermalPaused -> {
                thermalPaused = false
                updateCamera()
                notifier.clearAlert()
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> screenOffSince = SystemClock.elapsedRealtime()
                Intent.ACTION_SCREEN_ON -> {
                    if (screenOffSince > 0) manager.onScreenOffTime(sessionId, SystemClock.elapsedRealtime() - screenOffSince)
                    screenOffSince = 0
                }
                Intent.ACTION_BATTERY_CHANGED -> onBattery(intent)
            }
        }
    }

    private fun onBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (level < 0 || scale <= 0 || plugged) return
        val pct = level * 100 / scale
        if (pct <= 15 && !lowBatteryWarned) {
            lowBatteryWarned = true
            manager.onWarning(sessionId, "Battery at $pct%. Plug in to keep recording.")
            // Close the current segment so a dead battery can't cost more than a few frames.
            pipeline?.checkpoint()
        }
    }

    private var monitorsRegistered = false

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerMonitors() {
        if (monitorsRegistered) return
        monitorsRegistered = true
        getSystemService(PowerManager::class.java).addThermalStatusListener(ContextCompat.getMainExecutor(this), thermalListener)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        // Protected system broadcasts; NOT_EXPORTED is correct and required on Android 14+.
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun teardownMonitors() {
        watchdog?.cancel()
        watchdog = null
        if (!monitorsRegistered) return
        monitorsRegistered = false
        runCatching { getSystemService(PowerManager::class.java).removeThermalStatusListener(thermalListener) }
        runCatching { unregisterReceiver(receiver) }
    }

    // ---------------------------------------------------------------------------------------

    private fun acquireWakeLock() {
        val wl = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StudyTimelapse:capture")
            .also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
        // Timed so a bug can never drain the battery; the watchdog renews it while capturing.
        wl.acquire(WAKELOCK_TIMEOUT_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }
}
