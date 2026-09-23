package com.studytimelapse.app.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.studytimelapse.app.service.CaptureConfig
import com.studytimelapse.app.timelapse.TimelapseEncoder
import com.studytimelapse.app.timelapse.YuvFrame
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Camera → encoder pipeline for one session.
 *
 * Uses a single CameraX [ImageAnalysis] use case (no preview surface, so it keeps working when
 * the UI is gone or the screen is off, where the device allows it). The camera is asked for its
 * lowest supported frame rate to save power, and only one frame per capture interval is
 * converted and handed to the hardware encoder; every other frame is released immediately.
 *
 * Threading: [start]/[stopCamera]/[startCamera]/[finish]/[abort] on the main thread; frame work
 * on a dedicated single background thread.
 */
class CapturePipeline(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val config: CaptureConfig,
    private val outputDir: File,
    private val listener: Listener,
) {
    interface Listener {
        fun onFrame(screenOff: Boolean)
        fun onSegment(segment: TimelapseEncoder.Segment)
        fun onCameraState(open: Boolean, waiting: Boolean, error: String?)
        /** Unrecoverable for this session's video (no camera, encoder failed). */
        fun onFatal(reason: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "timelapse-capture").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val power = context.getSystemService(PowerManager::class.java)

    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var camera: Camera? = null

    @Volatile private var capturing = false
    @Volatile private var intervalMs = config.intervalSec * 1000L
    @Volatile var lastFrameElapsed: Long = SystemClock.elapsedRealtime()
        private set
    @Volatile private var fatal = false

    // Capture-thread state.
    private var nextDueElapsed = 0L
    private var warmupUntil = 0L
    private var encoder: TimelapseEncoder? = null
    private var sourceSize: Size? = null

    val isCapturing: Boolean get() = capturing
    val currentIntervalSec: Int get() = (intervalMs / 1000).toInt()

    fun start(openCamera: Boolean = true) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                provider = future.get()
                if (openCamera) startCamera()
            } catch (e: Exception) {
                Log.e(TAG, "Camera provider unavailable", e)
                reportFatal("The camera could not be opened on this device.")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Opens the camera and starts delivering frames. Safe to call repeatedly. */
    fun startCamera() {
        val p = provider ?: return
        if (fatal || analysis != null) return
        val selector = pickSelector(p) ?: run {
            reportFatal("No camera is available.")
            return
        }
        val builder = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(config.longEdge, config.shortEdge),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        ),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setTargetRotation(config.targetRotation)
        applyLowestFrameRate(builder, p, selector)
        val a = builder.build()
        a.setAnalyzer(executor, ::analyze)
        try {
            camera = p.bindToLifecycle(owner, selector, a)
            analysis = a
        } catch (e: Exception) {
            Log.e(TAG, "bindToLifecycle failed", e)
            reportFatal("The camera is not available right now (${e.message ?: "bind failed"}).")
            return
        }
        camera?.cameraInfo?.cameraState?.observe(owner) { state -> onCameraState(state) }
        val now = SystemClock.elapsedRealtime()
        warmupUntil = now + WARMUP_MS // let auto-exposure settle; the first frames are often dark
        nextDueElapsed = 0L
        lastFrameElapsed = now
        capturing = true
    }

    /** Closes the camera (pause, overheating, storage). The encoder stays ready to continue. */
    fun stopCamera() {
        capturing = false
        val a = analysis ?: return
        camera?.cameraInfo?.cameraState?.removeObservers(owner)
        a.clearAnalyzer()
        provider?.unbind(a)
        analysis = null
        camera = null
        // Close the current segment so the footage recorded so far is safely playable on disk.
        onCaptureThread { runCatching { encoder?.checkpoint() } }
    }

    private fun onCaptureThread(block: () -> Unit) {
        if (!executor.isShutdown) executor.execute(block)
    }

    fun setIntervalSec(seconds: Int) {
        intervalMs = seconds * 1000L
    }

    /** Makes sure everything recorded so far is in a finished file (e.g. battery about to die). */
    fun checkpoint() {
        onCaptureThread { runCatching { encoder?.checkpoint() } }
    }

    /** Stops the camera, finalises the video segments, then calls [done] on the main thread. */
    fun finish(done: () -> Unit) {
        stopCamera()
        if (executor.isShutdown) {
            main.post(done)
            return
        }
        executor.execute {
            try {
                encoder?.finish()
            } catch (e: Exception) {
                Log.e(TAG, "Encoder finish failed", e)
            } finally {
                encoder = null
                main.post(done)
            }
        }
        executor.shutdown()
    }

    /** Stops immediately and discards the encoder without keeping the open segment. */
    fun abort() {
        stopCamera()
        onCaptureThread {
            runCatching { encoder?.release() }
            encoder = null
        }
        executor.shutdown()
    }

    private fun analyze(image: ImageProxy) {
        try {
            if (!capturing || fatal) return
            val now = SystemClock.elapsedRealtime()
            if (now < warmupUntil) return
            val interval = intervalMs
            if (nextDueElapsed == 0L || now - nextDueElapsed > interval) nextDueElapsed = now
            // Tolerance of half a camera frame so we don't systematically skip to the next frame.
            if (now < nextDueElapsed - DUE_TOLERANCE_MS) return
            nextDueElapsed += interval

            val enc = encoder ?: createEncoder(image) ?: return
            val size = sourceSize
            if (size == null || image.width != size.width || image.height != size.height) return
            val planes = image.planes
            val frame = YuvFrame(
                image.width,
                image.height,
                YuvFrame.Plane(planes[0].buffer, planes[0].rowStride, planes[0].pixelStride),
                YuvFrame.Plane(planes[1].buffer, planes[1].rowStride, planes[1].pixelStride),
                YuvFrame.Plane(planes[2].buffer, planes[2].rowStride, planes[2].pixelStride),
            )
            if (enc.encode(frame)) {
                lastFrameElapsed = now
                listener.onFrame(screenOff = !power.isInteractive)
            }
        } catch (e: TimelapseEncoder.EncoderException) {
            Log.e(TAG, "Encoder error", e)
            reportFatal("The video encoder stopped working (${e.message}).")
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing failed", e)
        } finally {
            image.close()
        }
    }

    private fun createEncoder(image: ImageProxy): TimelapseEncoder? = try {
        val framesPerSegment = maxOf(10, (SEGMENT_SECONDS / maxOf(1, config.intervalSec)).toInt())
        TimelapseEncoder(
            outputDir = outputDir,
            sourceWidth = image.width,
            sourceHeight = image.height,
            bitrate = config.bitrate,
            rotationDegrees = image.imageInfo.rotationDegrees,
            framesPerSegment = framesPerSegment,
            firstSegmentIndex = config.firstSegmentIndex,
            onSegmentFinished = listener::onSegment,
        ).also {
            encoder = it
            sourceSize = Size(image.width, image.height)
        }
    } catch (e: TimelapseEncoder.EncoderException) {
        Log.e(TAG, "Encoder creation failed", e)
        reportFatal(e.message ?: "The video encoder could not start.")
        null
    }

    private fun onCameraState(state: CameraState) {
        val error = state.error
        val message = error?.let {
            when (it.code) {
                CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE -> "Another app is using the camera."
                CameraState.ERROR_CAMERA_DISABLED -> "The camera is disabled on this device."
                CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> "Do Not Disturb is blocking the camera."
                CameraState.ERROR_CAMERA_FATAL_ERROR -> "The camera stopped with an error."
                CameraState.ERROR_STREAM_CONFIG -> "The camera could not be configured."
                else -> "The camera was interrupted."
            }
        }
        listener.onCameraState(
            open = state.type == CameraState.Type.OPEN && error == null,
            waiting = state.type == CameraState.Type.PENDING_OPEN,
            error = message,
        )
    }

    private fun pickSelector(p: ProcessCameraProvider): CameraSelector? {
        val preferred = if (config.backCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        val other = if (config.backCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        return listOf(preferred, other).firstOrNull { runCatching { p.hasCamera(it) }.getOrDefault(false) }
    }

    /** Asks the camera for its lowest AE frame-rate range: fewer frames = less power and heat. */
    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun applyLowestFrameRate(builder: ImageAnalysis.Builder, p: ProcessCameraProvider, selector: CameraSelector) {
        try {
            val info = selector.filter(p.availableCameraInfos).firstOrNull() ?: return
            val ranges = Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return
            val lowest: Range<Int> = ranges.minWithOrNull(compareBy<Range<Int>>({ it.upper }, { it.lower })) ?: return
            Camera2Interop.Extender(builder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, lowest)
        } catch (e: Exception) {
            Log.w(TAG, "Could not lower the camera frame rate", e)
        }
    }

    private fun reportFatal(reason: String) {
        if (fatal) return
        fatal = true
        capturing = false
        main.post {
            stopCamera()
            listener.onFatal(reason)
        }
    }

    companion object {
        private const val TAG = "CapturePipeline"
        private const val WARMUP_MS = 1_500L
        private const val DUE_TOLERANCE_MS = 40L
        /** Wall-clock study time per segment file: bounds footage lost to a crash. */
        private const val SEGMENT_SECONDS = 600L
    }
}
