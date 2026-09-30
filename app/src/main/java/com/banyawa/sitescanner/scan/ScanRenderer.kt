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

    /**
     * Anchors dropped along the walk. ARCore refines its map as the scan goes on (most of
     * all when an area is seen again) and moves anchors with it, so keyframe poses kept
     * relative to the nearest anchor come out drift-corrected when read back at the end.
     */
    private class KeyAnchor(val anchor: Anchor, val poseThen: Pose)

    private val anchors = ArrayList<KeyAnchor>()
    private val keyframeAnchors = HashMap<Long, Pair<Int, Pose>>()

    // Rotating on the spot gives depth-from-motion nothing to work with: nudge to step sideways.
    private var moveCheckMs = 0L
    private val moveCheckPose = FloatArray(16)
    private val hintPose = FloatArray(16)
    private var sidewaysUntilMs = 0L
    private var lastFeatureTimestamp = 0L
    private var retryKeyframe = false
    private var lastUiMs = 0L

    // What the walk has covered so far, for the on-screen coach.
    private var floorFound = false
    private var levelCheckMs = 0L
    private val lookedBins = BooleanArray(LOOK_BINS)
    private var walkedM = 0f
    private var walkStarted = false
    private val walkPos = FloatArray(3)
    private var coverageMs = 0L
    private var floorSweepMs = 0L
    private var ceilingSweepMs = 0L
    private var orbited = false
    // Recent camera positions and headings (x, z, fx, fz per sample), a ring of ORBIT_SAMPLES.
    private val orbitRing = FloatArray(ORBIT_SAMPLES * 4)
    private var orbitCount = 0
    private var orbitNext = 0
    private var orbitSampleMs = 0L

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

        if (controller.recording) {
            integrate(session, frame, camera, now)
            checkSideways(camera, now)
            trackCoverage(camera, now)
        }
        if (now - levelCheckMs >= LEVEL_CHECK_MS) {
            levelCheckMs = now
            if (!floorFound) floorFound = detectLevels(session).first != null
        }

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
    private fun integrate(session: Session, frame: Frame, camera: Camera, now: Long) {
        // A keyframe's depth can come a few frames late: collect it before taking another.
        if (depthEnabled && capture.isArmed) {
            collectDepth(frame, camera)
            return
        }
        camera.pose.toMatrix(pose, 0)
        // A keyframe that got no depth is retried on the next frame rather than after moving on.
        if (!retryKeyframe && !keyframes.shouldCapture(pose, now)) return
        retryKeyframe = false
        if (depthEnabled) {
            if (!capture.preferSmoothed && controller.depthYield.tooSparse()) {
                capture.preferSmoothed = true
                Log.i(TAG, "Raw depth keeps ${controller.depthYield.fraction()} of pixels: switching to smoothed depth")
            }
            noteKeyframe(session, frame.timestamp, camera.pose)
            capture.arm(frame, camera, pose, withColor = true)
            collectDepth(frame, camera)
            if (controller.depthFrames > 0 || controller.recordingMs() < NO_DEPTH_FALLBACK_MS) return
        }
        if (!depthEnabled) {
            capture.colorOf(frame, camera)?.let {
                noteKeyframe(session, frame.timestamp, camera.pose)
                controller.recordColor(frame.timestamp, pose, it)
            }
        }
        integrateFeaturePoints(frame)
    }

    /** Remembers the keyframe's pose relative to an anchor near it, dropping a new anchor every [ANCHOR_SPACING_M]. */
    private fun noteKeyframe(session: Session, timestampNs: Long, cameraPose: Pose) {
        var index = -1
        var nearest = Float.MAX_VALUE
        for ((i, a) in anchors.withIndex()) {
            val d = distance(a.poseThen, cameraPose)
            if (d < nearest) {
                nearest = d
                index = i
            }
        }
        if ((index < 0 || nearest > ANCHOR_SPACING_M) && anchors.size < MAX_ANCHORS) {
            val anchor = try {
                session.createAnchor(cameraPose)
            } catch (e: Exception) {
                Log.w(TAG, "Anchor not created", e)
                null
            }
            if (anchor != null) {
                anchors += KeyAnchor(anchor, cameraPose)
                index = anchors.size - 1
            }
        }
        if (index >= 0) keyframeAnchors[timestampNs] = index to anchors[index].poseThen.inverse().compose(cameraPose)
    }

    /** Keyframe poses as the anchors place them now; anchors that lost tracking leave their frames as recorded. */
    private fun correctedPoses(): Map<Long, FloatArray> {
        val out = HashMap<Long, FloatArray>(keyframeAnchors.size)
        for ((timestamp, ref) in keyframeAnchors) {
            val a = anchors[ref.first]
            if (a.anchor.trackingState != TrackingState.TRACKING) continue
            out[timestamp] = FloatArray(16).also { a.anchor.pose.compose(ref.second).toMatrix(it, 0) }
        }
        for (a in anchors) a.anchor.detach()
        anchors.clear()
        keyframeAnchors.clear()
        return out
    }

    private fun distance(a: Pose, b: Pose): Float {
        val dx = a.tx() - b.tx()
        val dy = a.ty() - b.ty()
        val dz = a.tz() - b.tz()
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun checkSideways(camera: Camera, now: Long) {
        if (!depthEnabled) return
        camera.pose.toMatrix(hintPose, 0)
        if (moveCheckMs == 0L) {
            hintPose.copyInto(moveCheckPose)
            moveCheckMs = now
            return
        }
        if (now - moveCheckMs < MOVE_CHECK_MS) return
        val moved = KeyframeSelector.translationBetween(moveCheckPose, hintPose)
        val turned = KeyframeSelector.rotationDegBetween(moveCheckPose, hintPose)
        if (moved < MOVE_CHECK_MIN_M && turned > MOVE_CHECK_TURN_DEG) sidewaysUntilMs = now + HINT_MS
        hintPose.copyInto(moveCheckPose)
        moveCheckMs = now
    }

    /** Adds the camera's heading to the turn covered and its movement to the distance walked. */
    private fun trackCoverage(camera: Camera, now: Long) {
        val p = camera.pose
        val dt = if (coverageMs == 0L) 0L else (now - coverageMs).coerceAtMost(200L)
        coverageMs = now
        if (!walkStarted) {
            walkPos[0] = p.tx(); walkPos[1] = p.ty(); walkPos[2] = p.tz()
            walkStarted = true
        } else {
            val dx = p.tx() - walkPos[0]
            val dy = p.ty() - walkPos[1]
            val dz = p.tz() - walkPos[2]
            val d = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            // Counted in steps, so standing still with a shaky hand adds nothing.
            if (d >= WALK_STEP_M) {
                walkedM += d
                walkPos[0] = p.tx(); walkPos[1] = p.ty(); walkPos[2] = p.tz()
            }
        }
        // The camera looks down its −Z axis; a heading only counts when it is not aimed at the floor or ceiling,
        // and time aimed down or up counts as sweeping the floor or the ceiling.
        val z = p.zAxis
        val fx = -z[0]
        val fy = -z[1]
        val fz = -z[2]
        if (fy < -SWEEP_MIN_PITCH) floorSweepMs += dt
        if (fy > SWEEP_MIN_PITCH) ceilingSweepMs += dt
        if (fx * fx + fz * fz < MIN_LEVEL_LOOK * MIN_LEVEL_LOOK) return
        val yaw = kotlin.math.atan2(fx, fz)
        val bin = ((yaw + Math.PI) / (2 * Math.PI) * LOOK_BINS).toInt().coerceIn(0, LOOK_BINS - 1)
        lookedBins[bin] = true
        if (!orbited && now - orbitSampleMs >= ORBIT_SAMPLE_MS) {
            orbitSampleMs = now
            trackOrbit(p.tx(), p.tz(), fx, fz)
        }
    }

    /**
     * Notices the camera being walked around something: over the recent samples the
     * positions circle their own centre through most of a turn while the headings point
     * in at it (a machine, a column, a table given a full walk-around).
     */
    private fun trackOrbit(x: Float, z: Float, fx: Float, fz: Float) {
        val len = kotlin.math.sqrt(fx * fx + fz * fz)
        orbitRing[orbitNext * 4] = x
        orbitRing[orbitNext * 4 + 1] = z
        orbitRing[orbitNext * 4 + 2] = fx / len
        orbitRing[orbitNext * 4 + 3] = fz / len
        orbitNext = (orbitNext + 1) % ORBIT_SAMPLES
        if (orbitCount < ORBIT_SAMPLES) orbitCount++
        if (orbitCount < ORBIT_MIN_SAMPLES) return
        // The circle's centre, then how much of the turn around it the positions cover, looking in.
        var cx = 0f
        var cz = 0f
        for (i in 0 until orbitCount) {
            cx += orbitRing[i * 4]
            cz += orbitRing[i * 4 + 1]
        }
        cx /= orbitCount
        cz /= orbitCount
        val bins = BooleanArray(LOOK_BINS)
        var inward = 0
        var farEnough = 0
        for (i in 0 until orbitCount) {
            val dx = cx - orbitRing[i * 4]
            val dz = cz - orbitRing[i * 4 + 1]
            val r = kotlin.math.sqrt(dx * dx + dz * dz)
            if (r < ORBIT_MIN_RADIUS_M) continue
            farEnough++
            val toward = (dx * orbitRing[i * 4 + 2] + dz * orbitRing[i * 4 + 3]) / r
            if (toward < ORBIT_MIN_INWARD) continue
            inward++
            val a = kotlin.math.atan2(-dx, -dz)
            bins[((a + Math.PI) / (2 * Math.PI) * LOOK_BINS).toInt().coerceIn(0, LOOK_BINS - 1)] = true
        }
        if (farEnough >= ORBIT_MIN_SAMPLES && inward >= farEnough * 2 / 3 && bins.count { b -> b } * (360 / LOOK_BINS) >= ORBIT_MIN_DEG) orbited = true
    }

    private fun collectDepth(frame: Frame, camera: Camera) {
        val depth = try {
            capture.poll(frame, camera, withSurface = true)
        } catch (e: Exception) {
            Log.w(TAG, "Depth capture failed", e)
            capture.disarm()
            null
        }
        when {
            depth != null -> controller.submitDepth(depth)
            !capture.isArmed -> retryKeyframe = true
        }
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
        val surface = if (controller.recordedFrames > 0) " · rec ${controller.recordedFrames}" else ""
        val lag = if (depthEnabled) " · lag ${capture.lastLagMs.roundToInt()}ms" + (if (capture.matchesTimestamps) "" else " (unmatched)") else ""
        return "${Build.MANUFACTURER} ${Build.MODEL} · $source · ${controller.depthFrames + controller.featureFrames} frames · " +
            "last +${controller.lastAccepted}$raw$surface$lag$tracking"
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
        val (floorY, ceilingY) = detectLevels(session)
        return SessionResult(list, floorY, correctedPoses(), ceilingY)
    }

    /** Heights of the floor (lowest large plane facing up) and ceiling (highest large plane facing down). */
    private fun detectLevels(session: Session): Pair<Float?, Float?> {
        val planes = session.getAllTrackables(Plane::class.java).filter {
            it.trackingState == TrackingState.TRACKING && it.subsumedBy == null && it.extentX * it.extentZ >= MIN_FLOOR_AREA_M2
        }
        val floor = planes.filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }.minByOrNull { it.centerPose.ty() }?.centerPose?.ty()
        val ceiling = planes.filter { it.type == Plane.Type.HORIZONTAL_DOWNWARD_FACING }.maxByOrNull { it.centerPose.ty() }?.centerPose?.ty()
            // The underside of a table also faces down: a ceiling is well above the floor.
            ?.takeIf { c -> floor == null || c > floor + MIN_ROOM_HEIGHT_M }
        return floor to ceiling
    }

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
            tracking && now < sidewaysUntilMs -> TrackingHint.MOVE_SIDEWAYS
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
                floorFound = floorFound,
                turnedDeg = lookedBins.count { b -> b } * (360 / LOOK_BINS),
                walkedM = walkedM,
                floorSweepSec = (floorSweepMs / 1000).toInt(),
                ceilingSweepSec = (ceilingSweepMs / 1000).toInt(),
                orbited = orbited,
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
        private const val POINT_SIZE_PX = 4f

        /** Recording this long without a single depth frame falls back to feature points. */
        private const val NO_DEPTH_FALLBACK_MS = 4_000L

        private const val ANCHOR_SPACING_M = 0.5f
        private const val MAX_ANCHORS = 150

        /** Coach progress: headings in 30° bins, steps of walking counted, and how often the floor is looked for. */
        private const val LOOK_BINS = 12
        private const val MIN_LEVEL_LOOK = 0.5f
        private const val WALK_STEP_M = 0.1f
        private const val LEVEL_CHECK_MS = 1_000L

        /** Aimed more than ~37° down or up counts as sweeping the floor or ceiling. */
        private const val SWEEP_MIN_PITCH = 0.6f

        /** Walking around something: positions sampled twice a second over the last 40 s. */
        private const val ORBIT_SAMPLE_MS = 500L
        private const val ORBIT_SAMPLES = 80
        private const val ORBIT_MIN_SAMPLES = 16
        private const val ORBIT_MIN_RADIUS_M = 0.4f
        private const val ORBIT_MIN_INWARD = 0.5f
        private const val ORBIT_MIN_DEG = 270

        private const val MOVE_CHECK_MS = 2_500L
        private const val MOVE_CHECK_MIN_M = 0.06f
        private const val MOVE_CHECK_TURN_DEG = 15f
        private const val HINT_MS = 3_000L
        private const val UI_INTERVAL_MS = 80L
        private const val MIN_FLOOR_AREA_M2 = 0.5f
        private const val MIN_ROOM_HEIGHT_M = 1.8f

        // Scanned surfaces in a highlight colour, not their own: points in the camera's colours
        // over the camera image read as a confusing double exposure.
        private val SCAN_TINT = floatArrayOf(0.0f, 0.85f, 1.0f, 0.8f)
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
