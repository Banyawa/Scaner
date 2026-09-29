package com.banyawa.sitescanner.scan

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.banyawa.sitescanner.core.geometry.Mat4
import com.banyawa.sitescanner.core.geometry.Vec3
import com.banyawa.sitescanner.core.pointcloud.KeyframeSelector
import com.banyawa.sitescanner.core.project.Measurement
import com.banyawa.sitescanner.core.units.LengthFormat
import com.banyawa.sitescanner.gl.BackgroundRenderer
import com.banyawa.sitescanner.gl.LineRenderer
import com.banyawa.sitescanner.gl.PointRenderer
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import java.util.UUID
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

/**
 * GL-thread side of scanning: updates the ARCore session, draws the camera feed, the
 * fused point cloud and measurements, feeds depth frames to the fusion worker and
 * publishes UI state.
 */
class ScanRenderer(
    private val controller: ScanController,
    private val rotationHelper: DisplayRotationHelper,
) : GLSurfaceView.Renderer {

    @Volatile
    var session: Session? = null

    @Volatile
    var depthEnabled = false

    private val background = BackgroundRenderer()
    private val points = PointRenderer()
    private val lines = LineRenderer()
    private val capture = FrameCapture()
    private val keyframes = KeyframeSelector()

    private var textureBoundTo: Session? = null
    private var uploadedVersion = -1
    private var viewportWidth = 1
    private var viewportHeight = 1
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val pose = FloatArray(16)

    private class LiveMeasurement(val id: String, val a: Anchor, val b: Anchor, val createdAt: Long)

    private val measurements = ArrayList<LiveMeasurement>()
    private var pendingStart: Anchor? = null
    private var lastFeatureTimestamp = 0L
    private var lastUiMs = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        background.createOnGlThread()
        points.createOnGlThread()
        lines.createOnGlThread()
        // A new GL context needs the camera texture and point buffer set up again.
        textureBoundTo = null
        uploadedVersion = -1
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        rotationHelper.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = session ?: return
        if (textureBoundTo !== session) {
            session.setCameraTextureName(background.textureId)
            textureBoundTo = session
        }
        rotationHelper.updateSessionIfNeeded(session)

        val frame = try {
            session.update()
        } catch (e: CameraNotAvailableException) {
            controller.updateState { it.copy(tracking = false, hint = TrackingHint.CAMERA_UNAVAILABLE) }
            drainActions(session, null)
            return
        } catch (e: SessionPausedException) {
            return
        }

        val camera = frame.camera
        background.draw(frame)

        val now = SystemClock.elapsedRealtime()
        val tracking = camera.trackingState == TrackingState.TRACKING
        val reticle = if (tracking) centerHit(frame) else null
        drainActions(session, reticle)

        if (!tracking) {
            publishState(now, camera, null, force = false)
            return
        }

        camera.getProjectionMatrix(proj, 0, NEAR_M, FAR_M)
        camera.getViewMatrix(view, 0)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)

        if (controller.recording) integrate(frame, camera, now)

        controller.preview?.let {
            if (it.version != uploadedVersion) {
                points.upload(it.cloud)
                uploadedVersion = it.version
            }
        }
        points.draw(viewProj, POINT_SIZE_PX, SCAN_TINT)
        drawMeasurements(reticle)
        publishState(now, camera, reticle, force = false)
    }

    // --- depth fusion -------------------------------------------------------------------

    /**
     * Raw depth first; smoothed depth once raw depth proves too sparse; ARCore's feature
     * points when depth is unsupported or never delivers a frame.
     */
    private fun integrate(frame: Frame, camera: Camera, now: Long) {
        if (controller.isBusy || controller.integrator.isFull) return
        camera.pose.toMatrix(pose, 0)
        if (!keyframes.shouldCapture(pose, now)) return
        if (depthEnabled) {
            if (!capture.preferSmoothed && controller.depthYield.tooSparse()) {
                capture.preferSmoothed = true
                Log.i(TAG, "Raw depth keeps ${controller.depthYield.fraction()} of pixels: switching to smoothed depth")
            }
            val depth = try {
                capture.capture(frame, camera, pose, withColor = true, withSurface = true)
            } catch (e: Exception) {
                Log.w(TAG, "Depth capture failed", e)
                null
            }
            if (depth != null) {
                controller.submitDepth(depth)
                return
            }
            if (controller.depthFrames > 0 || controller.recordingMs() < NO_DEPTH_FALLBACK_MS) return
        }
        integrateFeaturePoints(frame)
    }

    private fun integrateFeaturePoints(frame: Frame) {
        val cloud = frame.acquirePointCloud()
        try {
            if (cloud.timestamp != lastFeatureTimestamp) {
                lastFeatureTimestamp = cloud.timestamp
                val buffer = cloud.points.duplicate()
                val n = buffer.remaining() / 4
                val xyzc = FloatArray(n * 4)
                buffer.get(xyzc)
                controller.submitFeaturePoints(xyzc, n)
            }
        } finally {
            cloud.close()
        }
    }

    /** One line for troubleshooting from a screenshot. */
    private fun diagnostics(camera: Camera): String {
        val source = when {
            !depthEnabled -> "no depth API: feature points"
            controller.depthFrames == 0 && controller.featureFrames > 0 -> "no depth frames: feature points"
            capture.usingSmoothedDepth -> "smoothed depth"
            else -> "raw depth"
        }
        val raw = controller.depthYield.fraction()?.let { " · raw kept ${(it * 100).roundToInt()}%" }.orEmpty()
        val tracking = if (camera.trackingState == TrackingState.TRACKING) "" else " · ${camera.trackingState}/${camera.trackingFailureReason}"
        val surface = if (controller.surfaceBlocks > 0) " · model ${controller.surfaceBlocks} blocks" else ""
        return "${Build.MANUFACTURER} ${Build.MODEL} · $source · ${controller.depthFrames + controller.featureFrames} frames · " +
            "last +${controller.lastAccepted}$raw$surface$tracking"
    }

    // --- measuring ----------------------------------------------------------------------

    private fun centerHit(frame: Frame): HitResult? =
        frame.hitTest(viewportWidth / 2f, viewportHeight / 2f).firstOrNull { hit ->
            when (val trackable = hit.trackable) {
                is Plane -> trackable.isPoseInPolygon(hit.hitPose)
                is DepthPoint -> true
                is Point -> trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                else -> false
            }
        }

    private fun drainActions(session: Session, reticle: HitResult?) {
        while (true) {
            when (val action = controller.pollAction() ?: return) {
                ScanAction.AddPoint -> addPoint(reticle)
                ScanAction.Undo -> undo()
                is ScanAction.Finish -> action.onResult(collectResult(session))
            }
        }
    }

    private fun addPoint(hit: HitResult?) {
        if (hit == null) return
        val anchor = try {
            hit.createAnchor()
        } catch (e: Exception) {
            Log.w(TAG, "Could not create anchor", e)
            return
        }
        val start = pendingStart
        if (start == null) {
            pendingStart = anchor
        } else {
            measurements += LiveMeasurement(UUID.randomUUID().toString(), start, anchor, System.currentTimeMillis())
            pendingStart = null
        }
    }

    private fun undo() {
        val start = pendingStart
        if (start != null) {
            start.detach()
            pendingStart = null
            return
        }
        val last = measurements.removeLastOrNull() ?: return
        last.a.detach()
        last.b.detach()
    }

    private fun collectResult(session: Session): SessionResult {
        val list = measurements.map {
            Measurement(
                id = it.id,
                start = it.a.pose.toVec3(),
                end = it.b.pose.toVec3(),
                createdAt = it.createdAt,
            )
        }
        return SessionResult(list, detectFloorY(session))
    }

    /** Height of the lowest large upward-facing plane: the floor. */
    private fun detectFloorY(session: Session): Float? =
        session.getAllTrackables(Plane::class.java)
            .filter {
                it.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                    it.trackingState == TrackingState.TRACKING &&
                    it.subsumedBy == null &&
                    it.extentX * it.extentZ >= MIN_FLOOR_AREA_M2
            }
            .minByOrNull { it.centerPose.ty() }
            ?.centerPose?.ty()

    private fun drawMeasurements(reticle: HitResult?) {
        if (measurements.isNotEmpty()) {
            val segs = FloatArray(measurements.size * 6)
            for ((i, m) in measurements.withIndex()) {
                m.a.pose.writeTranslation(segs, i * 6)
                m.b.pose.writeTranslation(segs, i * 6 + 3)
            }
            lines.draw(viewProj, segs, MEASURE_COLOR, GLES20.GL_LINES)
            lines.draw(viewProj, segs, ENDPOINT_COLOR, GLES20.GL_POINTS, pointSize = 16f)
        }
        val start = pendingStart
        if (start != null) {
            val seg = FloatArray(6)
            start.pose.writeTranslation(seg, 0)
            if (reticle != null) {
                reticle.hitPose.writeTranslation(seg, 3)
                lines.draw(viewProj, seg, PENDING_COLOR, GLES20.GL_LINES)
            }
            lines.draw(viewProj, seg.copyOf(3), ENDPOINT_COLOR, GLES20.GL_POINTS, pointSize = 16f)
        }
        if (reticle != null) {
            val p = FloatArray(3)
            reticle.hitPose.writeTranslation(p, 0)
            lines.draw(viewProj, p, RETICLE_COLOR, GLES20.GL_POINTS, pointSize = 22f)
        }
    }

    // --- UI state -----------------------------------------------------------------------

    private fun publishState(now: Long, camera: Camera, reticle: HitResult?, force: Boolean) {
        if (!force && now - lastUiMs < UI_INTERVAL_MS) return
        lastUiMs = now
        val tracking = camera.trackingState == TrackingState.TRACKING
        val hint = when {
            tracking -> TrackingHint.NONE
            camera.trackingState == TrackingState.PAUSED -> when (camera.trackingFailureReason) {
                TrackingFailureReason.INSUFFICIENT_LIGHT -> TrackingHint.MORE_LIGHT
                TrackingFailureReason.EXCESSIVE_MOTION -> TrackingHint.MOVE_SLOWLY
                TrackingFailureReason.INSUFFICIENT_FEATURES -> TrackingHint.MORE_TEXTURE
                TrackingFailureReason.CAMERA_UNAVAILABLE -> TrackingHint.CAMERA_UNAVAILABLE
                else -> TrackingHint.INITIALIZING
            }
            else -> TrackingHint.INITIALIZING
        }

        val labels = ArrayList<ScreenLabel>()
        if (tracking) {
            for (m in measurements) {
                val a = m.a.pose.toVec3()
                val b = m.b.pose.toVec3()
                label(m.id, Vec3.lerp(a, b, 0.5f), LengthFormat.format(a.distanceTo(b)), pending = false)?.let(labels::add)
            }
        }
        var live: Float? = null
        val start = pendingStart
        if (start != null && reticle != null) {
            val a = start.pose.toVec3()
            val b = reticle.hitPose.toVec3()
            live = a.distanceTo(b)
            if (tracking) label("pending", Vec3.lerp(a, b, 0.5f), LengthFormat.format(live), pending = true)?.let(labels::add)
        }

        controller.updateState {
            it.copy(
                tracking = tracking,
                hint = hint,
                pointCount = controller.integrator.voxelCount,
                storageFull = controller.integrator.voxelCount >= ScanController.MAX_VOXELS,
                elapsedSec = controller.elapsedSec(),
                reticleValid = reticle != null,
                pendingStart = start != null,
                liveDistanceM = live,
                measurementCount = measurements.size,
                labels = labels,
                diagnostics = diagnostics(camera),
            )
        }
    }

    private fun label(id: String, world: Vec3, text: String, pending: Boolean): ScreenLabel? {
        val screen = Mat4.projectToScreen(viewProj, world, viewportWidth, viewportHeight) ?: return null
        return ScreenLabel(id, screen.x, screen.y, text, pending)
    }

    companion object {
        private const val TAG = "ScanRenderer"
        private const val NEAR_M = 0.05f
        private const val FAR_M = 100f
        private const val POINT_SIZE_PX = 6f

        /** Recording this long without a single depth frame falls back to feature points. */
        private const val NO_DEPTH_FALLBACK_MS = 4_000L
        private const val UI_INTERVAL_MS = 80L
        private const val MIN_FLOOR_AREA_M2 = 0.5f

        // A light tint: scanned surfaces keep their real colours but stand out from the camera image.
        private val SCAN_TINT = floatArrayOf(0.0f, 0.85f, 1.0f, 0.3f)
        private val MEASURE_COLOR = floatArrayOf(1.0f, 0.6f, 0.0f, 1f)
        private val PENDING_COLOR = floatArrayOf(1.0f, 0.9f, 0.2f, 1f)
        private val ENDPOINT_COLOR = floatArrayOf(1f, 1f, 1f, 1f)
        private val RETICLE_COLOR = floatArrayOf(0.2f, 1f, 0.4f, 1f)
    }
}

private fun Pose.toVec3() = Vec3(tx(), ty(), tz())

private fun Pose.writeTranslation(dest: FloatArray, offset: Int) {
    dest[offset] = tx()
    dest[offset + 1] = ty()
    dest[offset + 2] = tz()
}
