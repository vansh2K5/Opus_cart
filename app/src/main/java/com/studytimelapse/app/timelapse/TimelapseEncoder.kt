package com.studytimelapse.app.timelapse

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.util.Log
import java.io.File

/**
 * Progressive H.264 timelapse encoder.
 *
 * Every captured frame goes straight into a hardware MediaCodec encoder and the compressed
 * output is written to disk immediately, so memory use is constant (a handful of codec buffers)
 * no matter how long the session is. Nothing is ever accumulated in RAM.
 *
 * **Crash safety.** An MP4 written by [MediaMuxer] is only playable after `stop()` writes its
 * index. To make sure a crash, a kill by the OS or a dead battery costs at most a few minutes of
 * footage, the output rolls over to a new segment file every [framesPerSegment] frames. A single
 * encoder instance feeds all segments (a sync frame is requested at each boundary), so every
 * segment shares identical codec config data and they can later be joined losslessly by
 * [SegmentConcatenator] without re-encoding.
 *
 * Threading: all methods must be called from the same single thread.
 */
class TimelapseEncoder(
    private val outputDir: File,
    sourceWidth: Int,
    sourceHeight: Int,
    private val bitrate: Int,
    private val rotationDegrees: Int,
    private val framesPerSegment: Int,
    /** First segment index; > 0 when continuing a session restored after a crash. */
    firstSegmentIndex: Int = 0,
    /** Presentation time to continue from (keeps timestamps monotonic across restarts). */
    private val firstFrameIndex: Long = 0,
    private val onSegmentFinished: (Segment) -> Unit,
) {
    data class Segment(val index: Int, val file: File, val frames: Int, val durationUs: Long)

    class EncoderException(message: String, cause: Throwable? = null) : Exception(message, cause)

    val width: Int
    val height: Int
    private val cropX: Int
    private val cropY: Int

    private val codec: MediaCodec
    private val bufferInfo = MediaCodec.BufferInfo()
    private var outputFormat: MediaFormat? = null

    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var segmentIndex = firstSegmentIndex
    private var segmentFile: File? = null
    private var segmentFrames = 0
    private var segmentBasePtsUs = -1L
    private var segmentLastPtsUs = 0L

    private var framesQueued = 0L
    /** Frames queued since the current segment was opened. */
    private var framesSinceRollRequest = 0
    private var rollPending = false
    private var released = false

    /** Total frames that reached the encoder in this instance. */
    val frameCount: Long get() = framesQueued

    init {
        val codecName = findEncoder()
            ?: throw EncoderException("This device has no H.264 encoder")
        val caps = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .first { it.name == codecName }
            .getCapabilitiesForType(MIME).videoCapabilities
            ?: throw EncoderException("The H.264 encoder reports no video capabilities")
        val (w, h) = chooseSize(caps, sourceWidth, sourceHeight)
        width = w
        height = h
        // Centre crop when the encoder cannot take the camera's exact size. Offsets stay even.
        cropX = ((sourceWidth - w) / 2) and 1.inv()
        cropY = ((sourceHeight - h) / 2) and 1.inv()

        val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, caps.bitrateRange.clamp(bitrate))
            setInteger(MediaFormat.KEY_FRAME_RATE, OUTPUT_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec = MediaCodec.createByCodecName(codecName)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw EncoderException("Could not start the video encoder at ${w}x$h", e)
        }
    }

    /** Encodes one frame. Returns false if the encoder had no free input buffer (frame dropped). */
    fun encode(frame: YuvFrame): Boolean {
        check(!released) { "encoder released" }
        val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (inputIndex < 0) {
            drain(endOfStream = false)
            return false
        }
        val image = codec.getInputImage(inputIndex)
            ?: throw EncoderException("Encoder did not provide an input image")
        YuvCopier.copy(frame, image, cropX, cropY)
        val ptsUs = (firstFrameIndex + framesQueued) * 1_000_000L / OUTPUT_FPS
        codec.queueInputBuffer(inputIndex, 0, width * height * 3 / 2, ptsUs, 0)
        framesQueued++

        framesSinceRollRequest++
        if (!rollPending && framesSinceRollRequest >= framesPerSegment && muxer != null) {
            rollPending = true
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
        drain(endOfStream = false)
        return true
    }

    /**
     * Closes the current segment right now (on pause, low battery, overheating) so the footage
     * recorded so far is a finished, playable file even if the phone dies. Encoding can continue
     * afterwards: the next frame is requested as a sync frame and opens a fresh segment.
     */
    fun checkpoint() {
        if (released || muxer == null) return
        // Collect outputs the encoder is still holding before closing the file.
        repeat(CHECKPOINT_DRAIN_ROUNDS) { drain(endOfStream = false, timeoutUs = OUTPUT_TIMEOUT_US) }
        closeSegment()
        rollPending = false
        framesSinceRollRequest = 0
        codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
    }

    /** Flushes remaining frames, finalises the last segment and releases the codec. */
    fun finish() {
        if (released) return
        try {
            val idx = codec.dequeueInputBuffer(FINISH_TIMEOUT_US)
            if (idx >= 0) {
                codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(endOfStream = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error while flushing encoder", e)
        } finally {
            closeSegment()
            release()
        }
    }

    fun release() {
        if (released) return
        released = true
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
        try { muxer?.release() } catch (_: Exception) {}
        muxer = null
    }

    private fun drain(endOfStream: Boolean, timeoutUs: Long = if (endOfStream) OUTPUT_TIMEOUT_US else 0) {
        var idleSpins = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++idleSpins > MAX_EOS_SPINS) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                index >= 0 -> {
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val isEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val isKey = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    if (!isConfig && bufferInfo.size > 0) {
                        if (muxer == null || (rollPending && isKey)) {
                            if (isKey) {
                                closeSegment()
                                openSegment()
                                rollPending = false
                                framesSinceRollRequest = 0
                            }
                        }
                        val m = muxer
                        if (m != null) {
                            val buffer = codec.getOutputBuffer(index)!!
                            buffer.position(bufferInfo.offset)
                            buffer.limit(bufferInfo.offset + bufferInfo.size)
                            if (segmentBasePtsUs < 0) segmentBasePtsUs = bufferInfo.presentationTimeUs
                            val info = MediaCodec.BufferInfo().apply {
                                set(0, bufferInfo.size, bufferInfo.presentationTimeUs - segmentBasePtsUs, bufferInfo.flags)
                            }
                            m.writeSampleData(trackIndex, buffer.slice(), info)
                            segmentLastPtsUs = info.presentationTimeUs
                            segmentFrames++
                        }
                        // A non-key frame before the first segment exists is dropped: a segment
                        // must start with a sync frame to be playable on its own.
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (isEos) return
                }
            }
        }
    }

    private fun openSegment() {
        val format = outputFormat ?: codec.outputFormat
        val file = File(outputDir, "seg_%04d.mp4".format(segmentIndex))
        val m = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        m.setOrientationHint(rotationDegrees)
        trackIndex = m.addTrack(format)
        m.start()
        muxer = m
        segmentFile = file
        segmentFrames = 0
        segmentBasePtsUs = -1
        segmentLastPtsUs = 0
    }

    private fun closeSegment() {
        val m = muxer ?: return
        val file = segmentFile
        muxer = null
        val frames = segmentFrames
        try {
            m.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Muxer stop failed for segment $segmentIndex", e)
        } finally {
            try { m.release() } catch (_: Exception) {}
        }
        if (file != null && frames > 0) {
            val durationUs = segmentLastPtsUs + 1_000_000L / OUTPUT_FPS
            onSegmentFinished(Segment(segmentIndex, file, frames, durationUs))
            segmentIndex++
        } else {
            file?.delete()
        }
    }

    private fun findEncoder(): String? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        // Prefer hardware encoders: far lower power than software (c2.android.*/OMX.google.*).
        val candidates = list.codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MIME, ignoreCase = true) } &&
                info.getCapabilitiesForType(MIME).colorFormats
                    .contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        }
        return (candidates.firstOrNull { it.isHardwareAccelerated } ?: candidates.firstOrNull())?.name
    }

    private fun chooseSize(caps: MediaCodecInfo.VideoCapabilities, w: Int, h: Int): Pair<Int, Int> {
        val evenW = w and 1.inv()
        val evenH = h and 1.inv()
        if (caps.isSizeSupported(evenW, evenH)) return evenW to evenH
        val alignW = (evenW / caps.widthAlignment) * caps.widthAlignment
        val alignH = (evenH / caps.heightAlignment) * caps.heightAlignment
        if (caps.isSizeSupported(alignW, alignH)) return alignW to alignH
        val w16 = evenW and 15.inv()
        val h16 = evenH and 15.inv()
        if (caps.isSizeSupported(w16, h16)) return w16 to h16
        throw EncoderException("Encoder does not support ${w}x$h")
    }

    companion object {
        private const val TAG = "TimelapseEncoder"
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val OUTPUT_FPS = 30
        private const val INPUT_TIMEOUT_US = 50_000L
        private const val OUTPUT_TIMEOUT_US = 20_000L
        private const val FINISH_TIMEOUT_US = 500_000L
        private const val MAX_EOS_SPINS = 100 // ~2 s worst case while flushing
        private const val CHECKPOINT_DRAIN_ROUNDS = 5
    }
}
