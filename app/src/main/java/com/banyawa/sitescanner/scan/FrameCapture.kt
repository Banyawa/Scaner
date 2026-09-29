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
 * One keyframe's depth. [points] feeds the point cloud (raw depth unless it proved too
 * sparse); [surface] is ARCore's smoothed depth, every pixel filled, for the surface model.
 */
class CapturedDepth(val points: DepthFrame, val raw: Boolean, val surface: DepthFrame?)

/**
 * Copies ARCore's raw depth, depth confidence and CPU camera image out of the current
 * frame so they can be processed off the GL thread (ARCore images must be closed quickly).
 * A device whose raw depth keeps failing, or gives too few confident pixels
 * ([preferSmoothed]), falls back to ARCore's smoothed depth, so a scan never ends up empty
 * just because one image type is unsupported or sparse.
 */
class FrameCapture {
    private var lastDepthTimestamp = -1L
    private var rawDepthFailures = 0

    /** Use smoothed depth (every pixel filled) instead of raw depth from now on. */
    @Volatile
    var preferSmoothed = false

    /** True when the next depth frame comes from smoothed rather than raw depth. */
    val usingSmoothedDepth: Boolean get() = preferSmoothed || rawDepthFailures >= MAX_RAW_FAILURES

    /**
     * Returns null when no new depth image is available for this frame. With
     * [withSurface] the smoothed depth for the surface model is copied as well.
     */
    fun capture(frame: Frame, camera: Camera, cameraToWorld: FloatArray, withColor: Boolean, withSurface: Boolean): CapturedDepth? {
        val raw = !usingSmoothedDepth
        val depthImage = acquireDepth(frame, raw) ?: return null
        val color: Pair<YuvFrame, CameraIntrinsics>?
        val points: DepthFrame
        try {
            if (depthImage.timestamp == lastDepthTimestamp) return null
            lastDepthTimestamp = depthImage.timestamp
            color = if (withColor) captureColor(frame, camera) else null
            // Confidence only exists for raw depth; smoothed depth counts as fully confident.
            val confidence = if (raw) captureConfidence(frame, depthImage.width, depthImage.height) else null
            points = toDepthFrame(depthImage, confidence, camera, cameraToWorld, color)
        } finally {
            depthImage.close()
        }
        val surface = when {
            !withSurface -> null
            !raw -> points
            else -> smoothedDepth(frame)?.let { image ->
                try {
                    toDepthFrame(image, null, camera, cameraToWorld, color)
                } finally {
                    image.close()
                }
            }
        }
        return CapturedDepth(points, raw, surface)
    }

    private fun toDepthFrame(
        image: Image,
        confidence: ByteArray?,
        camera: Camera,
        cameraToWorld: FloatArray,
        color: Pair<YuvFrame, CameraIntrinsics>?,
    ): DepthFrame {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        return DepthFrame(
            width = w,
            height = h,
            depthMm = ImagePacking.packShortPlane(plane.buffer, w, h, plane.rowStride, plane.pixelStride),
            confidence = confidence,
            intrinsics = camera.textureIntrinsics.toCore().scaledTo(w, h),
            cameraToWorld = cameraToWorld.copyOf(),
            color = color?.first,
            colorIntrinsics = color?.second,
            timestampNs = image.timestamp,
        )
    }

    /** Raw depth when [raw] (counting failures towards the switch to smoothed), else smoothed. */
    private fun acquireDepth(frame: Frame, raw: Boolean): Image? {
        if (!raw) return smoothedDepth(frame)
        return try {
            frame.acquireRawDepthImage16Bits()
        } catch (e: NotYetAvailableException) {
            null
        } catch (e: Exception) {
            rawDepthFailures++
            Log.w(TAG, "Raw depth unavailable ($rawDepthFailures)", e)
            null
        }
    }

    private fun smoothedDepth(frame: Frame): Image? =
        try {
            frame.acquireDepthImage16Bits()
        } catch (e: Exception) {
            // NotYetAvailable, or smoothed depth unsupported: no surface for this frame.
            null
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
