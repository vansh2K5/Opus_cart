package com.studytimelapse.app.timelapse

import android.media.Image
import java.nio.ByteBuffer

/**
 * A YUV 4:2:0 frame described by three planes with arbitrary strides. This decouples the encoder
 * from CameraX (`ImageProxy`) so the same code can be fed by any source.
 */
class YuvFrame(val width: Int, val height: Int, val y: Plane, val u: Plane, val v: Plane) {
    class Plane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)
}

internal object YuvCopier {

    /**
     * Copies a [dstWidth] x [dstHeight] window of [src], starting at ([cropX], [cropY]), into the
     * encoder input [dst]. Handles planar (I420) and semi-planar (NV12/NV21) layouts on both sides
     * through the per-plane pixel strides. Crop offsets must be even.
     */
    fun copy(src: YuvFrame, dst: Image, cropX: Int, cropY: Int) {
        val dstWidth = dst.width
        val dstHeight = dst.height
        val planes = dst.planes
        copyPlane(src.y, planes[0], cropX, cropY, dstWidth, dstHeight)
        copyPlane(src.u, planes[1], cropX / 2, cropY / 2, dstWidth / 2, dstHeight / 2)
        copyPlane(src.v, planes[2], cropX / 2, cropY / 2, dstWidth / 2, dstHeight / 2)
    }

    private fun copyPlane(src: YuvFrame.Plane, dst: Image.Plane, offX: Int, offY: Int, w: Int, h: Int) {
        val s = src.buffer.duplicate()
        val d = dst.buffer.duplicate()
        val sBase = s.position()
        val dBase = d.position()
        val sPix = src.pixelStride
        val dPix = dst.pixelStride
        val sRow = src.rowStride
        val dRow = dst.rowStride

        if (sPix == 1 && dPix == 1) {
            // Fast path: contiguous rows on both sides.
            val row = ByteArray(w)
            for (y in 0 until h) {
                s.position(sBase + (y + offY) * sRow + offX)
                s.get(row, 0, w)
                d.position(dBase + y * dRow)
                d.put(row, 0, w)
            }
            return
        }
        // Generic path: element by element through absolute indexing (no position churn).
        val sLimit = s.limit()
        val dLimit = d.limit()
        for (y in 0 until h) {
            val sRowStart = sBase + (y + offY) * sRow + offX * sPix
            val dRowStart = dBase + y * dRow
            for (x in 0 until w) {
                val si = sRowStart + x * sPix
                val di = dRowStart + x * dPix
                // Semi-planar buffers can end one byte early on the last row; skip out-of-range.
                if (si < sLimit && di < dLimit) d.put(di, s.get(si))
            }
        }
    }
}
