package com.banyawa.sitescanner.scan

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import com.banyawa.sitescanner.core.capture.CaptureWriter
import com.banyawa.sitescanner.core.capture.Manifest
import com.banyawa.sitescanner.core.pointcloud.CameraIntrinsics
import com.banyawa.sitescanner.core.pointcloud.DepthFrame
import com.banyawa.sitescanner.core.pointcloud.YuvFrame
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Writes the walk-through to disk while scanning: each keyframe's camera image (as JPEG),
 * pose and depth, on its own thread so the AR loop never waits for storage. The 3D model
 * is generated from this recording afterwards, with all frames and as much time as it
 * needs, and the recording can be sent to a PC for photogrammetry.
 */
class CaptureRecorder(val dir: File) {
    private val writer = CaptureWriter(dir)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "capture-recorder").apply { priority = Thread.MIN_PRIORITY + 1 } }
    private val pending = AtomicInteger(0)
    private val jpeg = ByteArrayOutputStream(256 * 1024)

    @Volatile
    var frameCount = 0
        private set

    @Volatile
    var closed = false
        private set

    /**
     * Records one keyframe. [color] is the camera image with its intrinsics; [points] the
     * depth for the point cloud (raw or smoothed) and [surface] the smoothed depth, either
     * may be null. Frames are dropped when writing falls more than a few frames behind.
     */
    fun add(timestampNs: Long, cameraToWorld: FloatArray, color: Pair<YuvFrame, CameraIntrinsics>, points: DepthFrame?, surface: DepthFrame?, raw: Boolean) {
        if (closed || pending.get() >= MAX_PENDING) return
        pending.incrementAndGet()
        val pose = cameraToWorld.copyOf()
        try {
            executor.execute {
                try {
                    val (yuv, k) = color
                    val bytes = encodeJpeg(yuv)
                    val depth = points ?: surface
                    writer.add(
                        timestampNs = timestampNs,
                        cameraToWorld = pose,
                        jpeg = bytes,
                        imageIntrinsics = k,
                        depthMm = depth?.depthMm,
                        depthIntrinsics = depth?.intrinsics,
                        confidence = if (raw) points?.confidence else null,
                        rawDepth = raw && points != null,
                        smoothDepthMm = if (raw && points != null) surface?.depthMm else null,
                    )
                    frameCount = writer.frameCount
                } catch (t: Throwable) {
                    Log.e(TAG, "Frame not recorded", t)
                } finally {
                    pending.decrementAndGet()
                }
            }
        } catch (e: RejectedExecutionException) {
            pending.decrementAndGet()
        }
    }

    /** Finishes the recording after every queued frame is written. Blocks; call off the main thread. */
    fun close(manifest: Manifest) {
        if (closed) return
        closed = true
        try {
            executor.submit { writer.close(manifest) }.get()
        } finally {
            executor.shutdown()
        }
    }

    /** ARCore's YUV_420_888 planes as NV21, which Android's JPEG encoder takes directly. */
    private fun encodeJpeg(yuv: YuvFrame): ByteArray {
        val w = yuv.width
        val h = yuv.height
        val nv21 = ByteArray(w * h + 2 * ((w + 1) / 2) * ((h + 1) / 2))
        for (row in 0 until h) {
            if (yuv.yPixelStride == 1) {
                System.arraycopy(yuv.y, row * yuv.yRowStride, nv21, row * w, w)
            } else {
                for (col in 0 until w) nv21[row * w + col] = yuv.y[row * yuv.yRowStride + col * yuv.yPixelStride]
            }
        }
        var o = w * h
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        for (row in 0 until ch) {
            val base = row * yuv.uvRowStride
            for (col in 0 until cw) {
                val i = base + col * yuv.uvPixelStride
                nv21[o++] = yuv.v[i]
                nv21[o++] = yuv.u[i]
            }
        }
        jpeg.reset()
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), JPEG_QUALITY, jpeg)
        return jpeg.toByteArray()
    }

    private companion object {
        const val TAG = "CaptureRecorder"
        const val JPEG_QUALITY = 88
        const val MAX_PENDING = 4
    }
}
