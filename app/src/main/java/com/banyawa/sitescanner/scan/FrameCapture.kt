package com.banyawa.sitescanner.scan

import android.media.Image
import android.util.Log
import com.banyawa.sitescanner.core.pointcloud.CameraIntrinsics
import com.banyawa.sitescanner.core.pointcloud.DepthFrame
import com.banyawa.sitescanner.core.pointcloud.ImagePacking
import com.banyawa.sitescanner.core.pointcloud.YuvFrame
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.CameraIntrinsics as ArIntrinsics

/**
 * Copies ARCore's raw depth, depth confidence and CPU camera image out of the current
 * frame so they can be processed off the GL thread (ARCore images must be closed quickly).
 * A device whose raw depth keeps failing falls back to ARCore's smoothed depth, so a scan
 * never ends up empty just because one image type is unsupported.
 */
class FrameCapture {
    private var lastDepthTimestamp = -1L
    private var rawDepthFailures = 0

    /** True once raw depth has failed repeatedly and smoothed depth is used instead. */
    val usingSmoothedDepth: Boolean get() = rawDepthFailures >= MAX_RAW_FAILURES

    /** Returns null when no new depth image is available for this frame. */
    fun capture(frame: Frame, camera: Camera, cameraToWorld: FloatArray, withColor: Boolean): DepthFrame? {
        val depthImage = acquireDepth(frame) ?: return null
        try {
            if (depthImage.timestamp == lastDepthTimestamp) return null
            lastDepthTimestamp = depthImage.timestamp
            val w = depthImage.width
            val h = depthImage.height
            val depthPlane = depthImage.planes[0]
            val depth = ImagePacking.packShortPlane(depthPlane.buffer, w, h, depthPlane.rowStride, depthPlane.pixelStride)
            // Confidence only exists for raw depth; smoothed depth counts as fully confident.
            val confidence = if (usingSmoothedDepth) null else captureConfidence(frame, w, h)
            val intrinsics = camera.textureIntrinsics.toCore().scaledTo(w, h)
            val color = if (withColor) captureColor(frame, camera) else null
            return DepthFrame(
                width = w,
                height = h,
                depthMm = depth,
                confidence = confidence,
                intrinsics = intrinsics,
                cameraToWorld = cameraToWorld.copyOf(),
                color = color?.first,
                colorIntrinsics = color?.second,
                timestampNs = depthImage.timestamp,
            )
        } finally {
            depthImage.close()
        }
    }

    private fun acquireDepth(frame: Frame): Image? {
        if (!usingSmoothedDepth) {
            try {
                return frame.acquireRawDepthImage16Bits()
            } catch (e: NotYetAvailableException) {
                return null
            } catch (e: Exception) {
                rawDepthFailures++
                Log.w(TAG, "Raw depth unavailable ($rawDepthFailures)", e)
                if (!usingSmoothedDepth) return null
            }
        }
        return try {
            frame.acquireDepthImage16Bits()
        } catch (e: NotYetAvailableException) {
            null
        }
    }

    private fun captureConfidence(frame: Frame, w: Int, h: Int): ByteArray? {
        val image = try {
            frame.acquireRawDepthConfidenceImage()
        } catch (e: Exception) {
            // Not ready yet, or not supported on this device: use the depth without it.
            return null
        }
        try {
            if (image.width != w || image.height != h) return null
            val plane = image.planes[0]
            return ImagePacking.packBytePlane(plane.buffer, w, h, plane.rowStride, plane.pixelStride)
        } finally {
            image.close()
        }
    }

    private fun captureColor(frame: Frame, camera: Camera): Pair<YuvFrame, CameraIntrinsics>? {
        val image: Image = try {
            frame.acquireCameraImage()
        } catch (e: Exception) {
            // NotYetAvailable / ResourceExhausted / DeadlineExceeded: just skip colour.
            return null
        }
        try {
            val planes = image.planes
            if (planes.size < 3) return null
            val yuv = YuvFrame(
                width = image.width,
                height = image.height,
                y = ImagePacking.copyRemaining(planes[0].buffer),
                yRowStride = planes[0].rowStride,
                yPixelStride = planes[0].pixelStride,
                u = ImagePacking.copyRemaining(planes[1].buffer),
                v = ImagePacking.copyRemaining(planes[2].buffer),
                uvRowStride = planes[1].rowStride,
                uvPixelStride = planes[1].pixelStride,
            )
            val intrinsics = camera.imageIntrinsics.toCore().scaledTo(image.width, image.height)
            return yuv to intrinsics
        } finally {
            image.close()
        }
    }

    fun reset() {
        lastDepthTimestamp = -1L
    }

    private companion object {
        const val TAG = "FrameCapture"
        const val MAX_RAW_FAILURES = 5
    }
}

fun ArIntrinsics.toCore(): CameraIntrinsics {
    val f = focalLength
    val c = principalPoint
    val d = imageDimensions
    return CameraIntrinsics(f[0], f[1], c[0], c[1], d[0], d[1])
}
