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
 * One keyframe's depth, placed with the pose of the camera frame it was measured in.
 * [points] feeds the point cloud (raw depth unless it proved too sparse); [surface] is
 * ARCore's smoothed depth, every pixel filled, for the surface model. Either may be missing.
 */
class CapturedDepth(val points: DepthFrame?, val raw: Boolean, val surface: DepthFrame?)

/**
 * Copies ARCore's raw depth, depth confidence and CPU camera image out of the current
 * frame so they can be processed off the GL thread (ARCore images must be closed quickly).
 *
 * Depth images often arrive a frame or more after the camera frame they were measured in.
 * Placing them with the pose of the frame at hand smears and doubles every surface while
 * the phone moves, so a keyframe is [arm]ed with its own pose and colour and [poll] waits
 * for the depth carrying that frame's timestamp. A phone whose timestamps never match
 * falls back to pairing depth with the frame at hand.
 *
 * A device whose raw depth keeps failing, or gives too few confident pixels
 * ([preferSmoothed]), falls back to ARCore's smoothed depth, so a scan never ends up empty
 * just because one image type is unsupported or sparse.
 */
class FrameCapture {
    private class Pending(val timestamp: Long, val pose: FloatArray, val color: Pair<YuvFrame, CameraIntrinsics>?, val raw: Boolean) {
        var points: DepthFrame? = null
        var pointsDone = false
        var surface: DepthFrame? = null
        var surfaceDone = false
    }

    private var pending: Pending? = null
    private var lastDepthTimestamp = -1L
    private var lastSurfaceTimestamp = -1L
    private var rawDepthFailures = 0

    // Keyframes in a row that got no depth; past MAX_MISSES timestamps are no longer required.
    private var pointMisses = 0
    private var surfaceMisses = 0
    private var ignorePointTimestamps = false
    private var ignoreSurfaceTimestamps = false

    /** Use smoothed depth (every pixel filled) instead of raw depth from now on. */
    @Volatile
    var preferSmoothed = false

    /** True when the next depth frame comes from smoothed rather than raw depth. */
    val usingSmoothedDepth: Boolean get() = preferSmoothed || rawDepthFailures >= MAX_RAW_FAILURES

    /** False once depth timestamps have repeatedly failed to match any armed frame. */
    val matchesTimestamps: Boolean get() = !ignorePointTimestamps

    /** How far the latest depth image lagged behind the camera frame, ms. */
    @Volatile
    var lastLagMs = 0f
        private set

    val isArmed: Boolean get() = pending != null

    /** Takes this frame as a keyframe: keeps its pose and colour until its depth turns up. */
    fun arm(frame: Frame, camera: Camera, cameraToWorld: FloatArray, withColor: Boolean) {
        pending = Pending(frame.timestamp, cameraToWorld.copyOf(), if (withColor) captureColor(frame, camera) else null, !usingSmoothedDepth)
    }

    fun disarm() {
        pending = null
    }

    /**
     * The armed keyframe's depth once it is available, looked for in [frame] (the armed one
     * or a later one). Null while still waiting, and when the keyframe gets no depth at all
     * (then it is disarmed). With [withSurface] smoothed depth for the surface is collected too.
     */
    fun poll(frame: Frame, camera: Camera, withSurface: Boolean): CapturedDepth? {
        val p = pending ?: return null
        val waitedNs = frame.timestamp - p.timestamp
        if (!p.pointsDone) {
            acquireDepth(frame, p.raw)?.let { image ->
                try {
                    lastLagMs = (frame.timestamp - image.timestamp) / 1e6f
                    when {
                        isFor(p, image.timestamp, lastDepthTimestamp, ignorePointTimestamps) -> {
                            val confidence = if (p.raw) captureConfidence(frame, image.width, image.height) else null
                            p.points = toDepthFrame(image, confidence, camera, p.pose, p.color)
                            p.pointsDone = true
                            lastDepthTimestamp = image.timestamp
                        }
                        // Depth moved past the armed frame: that frame got none.
                        image.timestamp > p.timestamp -> p.pointsDone = true
                    }
                } finally {
                    image.close()
                }
            }
        }
        if (!withSurface) {
            p.surfaceDone = true
        } else if (!p.surfaceDone) {
            if (!p.raw) {
                // Points already come from smoothed depth: the same frame serves the surface.
                if (p.pointsDone) {
                    p.surface = p.points
                    p.surfaceDone = true
                }
            } else {
                smoothedDepth(frame)?.let { image ->
                    try {
                        when {
                            isFor(p, image.timestamp, lastSurfaceTimestamp, ignoreSurfaceTimestamps) -> {
                                p.surface = toDepthFrame(image, null, camera, p.pose, p.color)
                                p.surfaceDone = true
                                lastSurfaceTimestamp = image.timestamp
                            }
                            image.timestamp > p.timestamp -> p.surfaceDone = true
                        }
                    } finally {
                        image.close()
                    }
                }
                // Phones without smoothed depth: don't hold the points back for it.
                if (p.pointsDone && waitedNs > SURFACE_WAIT_NS) p.surfaceDone = true
            }
        }
        if (!(p.pointsDone && p.surfaceDone) && waitedNs < MAX_WAIT_NS) return null

        pending = null
        pointMisses = if (p.points == null) pointMisses + 1 else 0
        if (pointMisses >= MAX_MISSES && !ignorePointTimestamps) {
            ignorePointTimestamps = true
            Log.w(TAG, "Depth timestamps never match camera frames: using depth as it comes")
        }
        if (withSurface && p.raw) {
            surfaceMisses = if (p.surface == null) surfaceMisses + 1 else 0
            if (surfaceMisses >= MAX_MISSES && !ignoreSurfaceTimestamps) {
                ignoreSurfaceTimestamps = true
                Log.w(TAG, "Smoothed depth timestamps never match camera frames: using it as it comes")
            }
        }
        return if (p.points == null && p.surface == null) null else CapturedDepth(p.points, p.raw, p.surface)
    }

    /** Depth taken at [timestamp] belongs to [p] (or, when timestamps are ignored, is simply new). */
    private fun isFor(p: Pending, timestamp: Long, lastUsed: Long, ignoreTimestamps: Boolean): Boolean =
        if (ignoreTimestamps) timestamp != lastUsed else timestamp == p.timestamp

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
            cameraToWorld = cameraToWorld,
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

    /** The camera image of [frame] with its intrinsics, for recording frames that get no depth. */
    fun colorOf(frame: Frame, camera: Camera): Pair<YuvFrame, CameraIntrinsics>? = captureColor(frame, camera)

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
        pending = null
        lastDepthTimestamp = -1L
        lastSurfaceTimestamp = -1L
    }

    private companion object {
        const val TAG = "FrameCapture"
        const val MAX_RAW_FAILURES = 5

        /** Keyframes in a row whose depth never turned up before timestamps are given up on. */
        const val MAX_MISSES = 12

        /** A keyframe waits this long for its depth at most. */
        const val MAX_WAIT_NS = 500_000_000L
        const val SURFACE_WAIT_NS = 200_000_000L
    }
}

fun ArIntrinsics.toCore(): CameraIntrinsics {
    val f = focalLength
    val c = principalPoint
    val d = imageDimensions
    return CameraIntrinsics(f[0], f[1], c[0], c[1], d[0], d[1])
}
