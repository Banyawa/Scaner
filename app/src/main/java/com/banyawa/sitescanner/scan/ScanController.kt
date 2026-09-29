package com.banyawa.sitescanner.scan

import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import com.banyawa.sitescanner.core.pointcloud.CameraIntrinsics
import com.banyawa.sitescanner.core.pointcloud.DepthFilter
import com.banyawa.sitescanner.core.pointcloud.DepthYield
import com.banyawa.sitescanner.core.pointcloud.KeyframeSelector
import com.banyawa.sitescanner.core.pointcloud.PointCloud
import com.banyawa.sitescanner.core.pointcloud.ScanIntegrator
import com.banyawa.sitescanner.core.pointcloud.YuvFrame
import com.banyawa.sitescanner.core.project.Measurement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

enum class TrackingHint { NONE, INITIALIZING, MOVE_SLOWLY, MOVE_SIDEWAYS, MORE_LIGHT, MORE_TEXTURE, CAMERA_UNAVAILABLE }

data class ScreenLabel(val id: String, val x: Float, val y: Float, val text: String, val pending: Boolean = false)

data class ScanUiState(
    val arReady: Boolean = false,
    @StringRes val errorRes: Int? = null,
    val errorDetail: String? = null,
    val canRetry: Boolean = false,
    val depthSupported: Boolean = true,
    val tracking: Boolean = false,
    val hint: TrackingHint = TrackingHint.INITIALIZING,
    val recording: Boolean = false,
    val pointCount: Int = 0,
    val storageFull: Boolean = false,
    val elapsedSec: Int = 0,
    val reticleValid: Boolean = false,
    val pendingStart: Boolean = false,
    val liveDistanceM: Float? = null,
    val measurementCount: Int = 0,
    val labels: List<ScreenLabel> = emptyList(),
    val saving: Boolean = false,
    /** 0..1 while the 3D model is generated from the recording after saving; null before. */
    val buildProgress: Float? = null,
    /** Phone, depth source and frame counts: what a screenshot needs for troubleshooting. */
    val diagnostics: String = "",
)

sealed interface ScanAction {
    data object AddPoint : ScanAction
    data object Undo : ScanAction

    /** Collects the session's measurements and floor height on the GL thread. */
    class Finish(val onResult: (SessionResult) -> Unit) : ScanAction
}

/** [correctedPoses]: keyframe poses (by frame timestamp) read back through anchors at the end of the scan. */
data class SessionResult(val measurements: List<Measurement>, val floorY: Float?, val correctedPoses: Map<Long, FloatArray> = emptyMap())

class PreviewSnapshot(val version: Int, val cloud: PointCloud)

/**
 * State shared between the AR renderer (GL thread), the fusion worker and the Compose UI.
 */
class ScanController {
    // Low-confidence raw depth is let in and has to be seen several times to be saved
    // (see the weight filter on saving) rather than being thrown away up front.
    val integrator = ScanIntegrator(
        voxelSize = VOXEL_SIZE_M,
        maxVoxels = MAX_VOXELS,
        filter = DepthFilter(minConfidence = MIN_RAW_CONFIDENCE, maxEdgeJump = MAX_EDGE_JUMP),
    )

    /** Share of raw depth pixels kept, for switching to smoothed depth when it is too low. */
    val depthYield = DepthYield()

    /** The walk-through on disk, set by the activity once recording starts. */
    @Volatile
    var recorder: CaptureRecorder? = null

    /** Recorded frames are spaced wider than live keyframes: enough overlap, a third of the storage. */
    private val recordKeyframes = KeyframeSelector(minTranslationM = 0.06f, minRotationDeg = 5f, minIntervalMs = 200L, maxIntervalMs = 2000L)

    @Volatile
    var depthFrames = 0
        private set

    @Volatile
    var featureFrames = 0
        private set

    /** Points added by the latest frame. */
    @Volatile
    var lastAccepted = 0
        private set

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "scan-fusion").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    /** Fusion jobs queued or running: at most [MAX_IN_FLIGHT], so work never piles up. */
    private val inFlight = AtomicInteger(0)
    private val actions = ConcurrentLinkedQueue<ScanAction>()

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    @Volatile
    var preview: PreviewSnapshot? = null
        private set
    private var previewVersion = 0
    private var lastPreviewMs = 0L

    @Volatile
    var recording = false
        private set
    private var recordingSinceMs = 0L
    private var recordedMs = 0L

    val isBusy: Boolean get() = inFlight.get() > 0

    fun setRecording(on: Boolean) {
        synchronized(this) {
            if (on == recording) return
            val now = SystemClock.elapsedRealtime()
            if (on) recordingSinceMs = now else recordedMs += now - recordingSinceMs
            recording = on
        }
        _state.update { it.copy(recording = on) }
    }

    fun elapsedSec(): Int = (recordingMs() / 1000).toInt()

    /** Time spent recording so far, pauses excluded. */
    fun recordingMs(): Long = synchronized(this) {
        val live = if (recording) SystemClock.elapsedRealtime() - recordingSinceMs else 0L
        recordedMs + live
    }

    /**
     * Fuses a keyframe's depth into the live points (the on-screen coverage) and records
     * the frame. Raw depth frames also report their yield, which decides whether to keep
     * using raw depth.
     */
    fun submitDepth(depth: CapturedDepth) {
        val frame = depth.points ?: depth.surface
        val color = frame?.color as? YuvFrame
        val k = frame?.colorIntrinsics
        if (frame != null && color != null && k != null && recordKeyframes.shouldCapture(frame.cameraToWorld, SystemClock.elapsedRealtime())) {
            recorder?.add(depth.timestampNs, frame.cameraToWorld, color to k, depth.points, depth.surface, depth.raw)
        }
        submit {
            val before = integrator.voxelCount
            depth.points?.let { points ->
                val kept = integrator.integrate(points)
                if (depth.raw) depthYield.add(kept, points.width * points.height)
            }
            lastAccepted = integrator.voxelCount - before
            depthFrames++
        }
    }

    /** Records a keyframe of a phone without depth: its image and pose still make the model on a PC. */
    fun recordColor(timestampNs: Long, cameraToWorld: FloatArray, color: Pair<YuvFrame, CameraIntrinsics>) {
        if (recordKeyframes.shouldCapture(cameraToWorld, SystemClock.elapsedRealtime())) {
            recorder?.add(timestampNs, cameraToWorld, color, null, null, raw = false)
        }
    }

    val recordedFrames: Int get() = recorder?.frameCount ?: 0

    fun submitFeaturePoints(xyzc: FloatArray, count: Int) = submit {
        val before = integrator.voxelCount
        integrator.integrateWorldPoints(xyzc, count)
        lastAccepted = integrator.voxelCount - before
        featureFrames++
    }

    private fun submit(work: () -> Unit) {
        if (inFlight.incrementAndGet() > MAX_IN_FLIGHT) {
            inFlight.decrementAndGet()
            return
        }
        try {
            worker.execute {
                try {
                    work()
                    publishPreview(force = false)
                } catch (t: Throwable) {
                    Log.e(TAG, "Fusion failed", t)
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        } catch (e: RejectedExecutionException) {
            inFlight.decrementAndGet()
        }
    }

    private fun publishPreview(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPreviewMs < PREVIEW_INTERVAL_MS) return
        lastPreviewMs = now
        preview = PreviewSnapshot(++previewVersion, integrator.snapshot(maxPoints = PREVIEW_MAX_POINTS))
    }

    fun post(action: ScanAction) {
        actions.add(action)
    }

    fun pollAction(): ScanAction? = actions.poll()

    fun updateState(transform: (ScanUiState) -> ScanUiState) = _state.update(transform)

    /** Blocks until queued fusion work has finished. Call off the main thread. */
    fun awaitIdle() {
        try {
            worker.submit {}.get()
        } catch (e: RejectedExecutionException) {
            // Already shut down: nothing pending.
        }
    }

    fun shutdown() {
        worker.shutdown()
    }

    companion object {
        private const val TAG = "ScanController"

        /** 1 cm voxels: fine enough for shop-drawing detail, coarse enough for phone memory. */
        const val VOXEL_SIZE_M = 0.01f
        const val MAX_VOXELS = 3_000_000

        /** Raw depth confidence (0..255) below which a pixel is not used at all. */
        private const val MIN_RAW_CONFIDENCE = 100

        /** Depth jumping by more than this share to a neighbouring pixel marks an edge's flying pixels. */
        private const val MAX_EDGE_JUMP = 0.05f

        private const val PREVIEW_INTERVAL_MS = 400L

        /** A running job plus one waiting: a keyframe's depth that arrives late still gets in. */
        private const val MAX_IN_FLIGHT = 2
        private const val PREVIEW_MAX_POINTS = 400_000
    }
}
