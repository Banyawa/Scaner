package com.banyawa.sitescanner.core.pointcloud

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Copies strided image planes (android.media.Image style) into tightly packed arrays. */
object ImagePacking {
    /** 16-bit little-endian plane (e.g. ARCore DEPTH16 millimetres) to a width*height array. */
    fun packShortPlane(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): ShortArray {
        val src = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val base = src.position()
        val out = ShortArray(width * height)
        for (row in 0 until height) {
            val rowStart = base + row * rowStride
            val o = row * width
            for (col in 0 until width) {
                out[o + col] = src.getShort(rowStart + col * pixelStride)
            }
        }
        return out
    }

    /** 8-bit plane (e.g. ARCore depth confidence) to a width*height array. */
    fun packBytePlane(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): ByteArray {
        val src = buffer.duplicate()
        val base = src.position()
        val out = ByteArray(width * height)
        for (row in 0 until height) {
            val rowStart = base + row * rowStride
            val o = row * width
            if (pixelStride == 1) {
                src.position(rowStart)
                src.get(out, o, width)
            } else {
                for (col in 0 until width) out[o + col] = src.get(rowStart + col * pixelStride)
            }
        }
        return out
    }

    /** Copies the remaining bytes of [buffer] without disturbing its position. */
    fun copyRemaining(buffer: ByteBuffer): ByteArray {
        val src = buffer.duplicate()
        val out = ByteArray(src.remaining())
        src.get(out)
        return out
    }
}
