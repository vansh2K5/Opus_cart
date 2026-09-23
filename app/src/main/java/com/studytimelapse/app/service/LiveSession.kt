package com.studytimelapse.app.service

import android.content.Intent
import com.studytimelapse.app.data.prefs.StudyScreenMode

/** What the camera side of a session is doing right now. Shown to the user verbatim. */
enum class CameraStatus(val label: String) {
    OFF("No timelapse"),
    STARTING("Starting camera…"),
    RECORDING("Timelapse recording"),
    PAUSED("Timelapse paused"),
    /** Frames stopped arriving (e.g. the device blocked the camera with the screen off). */
    STALLED("Camera stopped delivering frames"),
    ERROR("Camera unavailable"),
    STOPPED_THERMAL("Paused — phone too hot"),
    STOPPED_STORAGE("Stopped — storage almost full"),
    /** The app process was restarted; the camera needs to be started again from the app. */
    NOT_RUNNING("Camera not running"),
}

/** Immutable snapshot of the active session for the UI. Timer values tick in the UI itself. */
data class LiveSession(
    val sessionId: String,
    val subject: String,
    val title: String?,
    val targetMs: Long?,
    val startUtc: Long,
    val running: Boolean,
    val studyMsAtSnapshot: Long,
    val snapshotElapsed: Long,
    val pauseCount: Int,
    val timelapse: Boolean,
    val intervalSec: Int,
    val camera: CameraStatus,
    val cameraMessage: String? = null,
    val frames: Int = 0,
    val framesScreenOff: Int = 0,
    val lastFrameElapsed: Long = 0,
    val gaps: Int = 0,
    val warning: String? = null,
    val screenMode: StudyScreenMode = StudyScreenMode.DIM,
    val interrupted: Boolean = false,
) {
    fun studyMsAt(elapsedNow: Long): Long =
        studyMsAtSnapshot + if (running) (elapsedNow - snapshotElapsed).coerceAtLeast(0) else 0
}

/** Result of the on-device "does my phone allow screen-off recording?" check. */
data class ScreenOffTest(
    val running: Boolean,
    val frames: Int = 0,
    val framesScreenOff: Int = 0,
    val screenOffMs: Long = 0,
    val cameraStatus: CameraStatus = CameraStatus.STARTING,
    /** null = not decided yet / screen was not turned off long enough. */
    val works: Boolean? = null,
)

/** Everything the capture service needs; passed through the start Intent. */
data class CaptureConfig(
    val sessionId: String,
    val intervalSec: Int,
    val longEdge: Int,
    val shortEdge: Int,
    val bitrate: Int,
    val backCamera: Boolean,
    /** android.view.Surface.ROTATION_* the video should be upright for. */
    val targetRotation: Int,
    val firstSegmentIndex: Int,
    val isTest: Boolean,
) {
    fun writeTo(intent: Intent): Intent = intent
        .putExtra("sessionId", sessionId)
        .putExtra("intervalSec", intervalSec)
        .putExtra("longEdge", longEdge)
        .putExtra("shortEdge", shortEdge)
        .putExtra("bitrate", bitrate)
        .putExtra("backCamera", backCamera)
        .putExtra("targetRotation", targetRotation)
        .putExtra("firstSegmentIndex", firstSegmentIndex)
        .putExtra("isTest", isTest)

    companion object {
        fun from(intent: Intent): CaptureConfig? {
            val id = intent.getStringExtra("sessionId") ?: return null
            return CaptureConfig(
                sessionId = id,
                intervalSec = intent.getIntExtra("intervalSec", 2),
                longEdge = intent.getIntExtra("longEdge", 1280),
                shortEdge = intent.getIntExtra("shortEdge", 720),
                bitrate = intent.getIntExtra("bitrate", 3_000_000),
                backCamera = intent.getBooleanExtra("backCamera", true),
                targetRotation = intent.getIntExtra("targetRotation", 0),
                firstSegmentIndex = intent.getIntExtra("firstSegmentIndex", 0),
                isTest = intent.getBooleanExtra("isTest", false),
            )
        }
    }
}

/** Implemented by [StudySessionService]; lets the session manager drive the camera. */
interface CameraHost {
    val sessionId: String
    fun pauseCapture()
    fun resumeCapture()
    /** Finalises the video segments, then calls [SessionManager.onCaptureFinished]. */
    fun finishCapture()
    /** Stops immediately and throws the footage away (discarded session / screen-off test). */
    fun abortCapture()
}
