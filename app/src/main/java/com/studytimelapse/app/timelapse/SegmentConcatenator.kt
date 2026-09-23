package com.studytimelapse.app.timelapse

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Joins timelapse segments into one MP4 by remuxing (copying compressed samples). No decoding
 * or re-encoding happens, so even a 4-hour session is joined in a couple of seconds with very
 * little CPU.
 *
 * Corrupt segments (for example the one that was being written when the process died) are
 * skipped instead of failing the whole timelapse.
 */
object SegmentConcatenator {
    private const val TAG = "SegmentConcatenator"

    data class Result(val file: File, val durationUs: Long, val frames: Int, val skippedSegments: Int)

    fun concatenate(segments: List<File>, output: File, rotationDegrees: Int): Result? {
        val readable = segments.filter { it.exists() && it.length() > 0 }
        if (readable.isEmpty()) return null
        val tmp = File(output.parentFile, output.name + ".tmp")
        tmp.delete()

        var muxer: MediaMuxer? = null
        var outTrack = -1
        var offsetUs = 0L
        var frames = 0
        var skipped = segments.size - readable.size
        var firstSize: Pair<Int, Int>? = null
        val buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()

        try {
            for (segment in readable) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(segment.absolutePath)
                    val track = (0 until extractor.trackCount).firstOrNull {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                    }
                    if (track == null) {
                        skipped++
                        continue
                    }
                    extractor.selectTrack(track)
                    val trackFormat = extractor.getTrackFormat(track)
                    val size = trackFormat.getInteger(MediaFormat.KEY_WIDTH) to trackFormat.getInteger(MediaFormat.KEY_HEIGHT)
                    if (firstSize != null && size != firstSize) {
                        // A segment recorded with different settings cannot share one H.264 track.
                        Log.w(TAG, "Skipping ${segment.name}: size $size differs from $firstSize")
                        skipped++
                        continue
                    }
                    firstSize = size
                    if (muxer == null) {
                        val format = extractor.getTrackFormat(track)
                        // Segments carry the camera's real orientation; prefer it over the fallback.
                        val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                            format.getInteger(MediaFormat.KEY_ROTATION)
                        } else rotationDegrees
                        muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also {
                            it.setOrientationHint(rotation)
                            outTrack = it.addTrack(extractor.getTrackFormat(track))
                            it.start()
                        }
                    }
                    var lastPts = 0L
                    var wroteAny = false
                    while (true) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break
                        val pts = extractor.sampleTime
                        val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                            MediaCodec.BUFFER_FLAG_KEY_FRAME
                        } else 0
                        info.set(0, sampleSize, offsetUs + pts, flags)
                        muxer!!.writeSampleData(outTrack, buffer, info)
                        lastPts = pts
                        wroteAny = true
                        frames++
                        extractor.advance()
                    }
                    if (wroteAny) offsetUs += lastPts + 1_000_000L / TimelapseEncoder.OUTPUT_FPS
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping unreadable segment ${segment.name}", e)
                    skipped++
                } finally {
                    extractor.release()
                }
            }
            val m = muxer ?: return null
            m.stop()
            m.release()
            muxer = null
            if (frames == 0) {
                tmp.delete()
                return null
            }
            output.delete()
            if (!tmp.renameTo(output)) {
                tmp.copyTo(output, overwrite = true)
                tmp.delete()
            }
            return Result(output, offsetUs, frames, skipped)
        } catch (e: Exception) {
            Log.e(TAG, "Concatenation failed", e)
            try { muxer?.release() } catch (_: Exception) {}
            tmp.delete()
            return null
        }
    }

    /** Saves a small JPEG poster frame from the middle of [video]. Returns false on failure. */
    fun writeThumbnail(video: File, target: File, maxEdge: Int = 480): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(video.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val frame = retriever.getFrameAtTime(durationMs * 1000 / 2, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return false
            val scale = maxEdge.toFloat() / maxOf(frame.width, frame.height)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true)
                    .also { frame.recycle() }
            } else frame
            FileOutputStream(target).use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            scaled.recycle()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Thumbnail failed", e)
            false
        } finally {
            retriever.release()
        }
    }
}
