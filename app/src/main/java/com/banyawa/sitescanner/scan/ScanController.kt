package com.banyawa.sitescanner.scan

import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import com.banyawa.sitescanner.core.pointcloud.DepthFrame
import com.banyawa.sitescanner.core.pointcloud.PointCloud
import com.banyawa.sitescanner.core.pointcloud.ScanIntegrator
import com.banyawa.sitescanner.core.project.Measurement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

enum class TrackingHint { NONE, INITIALIZING, MOVE_SLOWLY, MORE_LIGHT, MORE_TEXTURE, CAMERA_UNAVAILABLE }

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
)

sealed interface ScanAction {
    data object AddPoint : ScanAction
    data object Undo : ScanAction

    /** Collects the session's measurements and floor height on the GL thread. */
    class Finish(val onResult: (SessionResult) -> Unit) : ScanAction
}

data class SessionResult(val measurements: List<Measurement>, val floorY: Float?)

class PreviewSnapshot(val version: Int, val cloud: PointCloud)

/**
 * State shared between the AR renderer (GL thread), the fusion worker and the Compose UI.
 */
class ScanController {
    val integrator = ScanIntegrator(voxelSize = VOXEL_SIZE_M, maxVoxels = MAX_VOXELS)

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "scan-fusion").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val busy = AtomicBoolean(false)
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

    val isBusy: Boolean get() = busy.get()

    fun setRecording(on: Boolean) {
        synchronized(this) {
            if (on == recording) return
            val now = SystemClock.elapsedRealtime()
            if (on) recordingSinceMs = now else recordedMs += now - recordingSinceMs
            recording = on
        }
        _state.update { it.copy(recording = on) }
    }

    fun elapsedSec(): Int = synchronized(this) {
        val live = if (recording) SystemClock.elapsedRealtime() - recordingSinceMs else 0L
        ((recordedMs + live) / 1000).toInt()
    }

    fun submitDepth(frame: DepthFrame) = submit { integrator.integrate(frame) }

    fun submitFeaturePoints(xyzc: FloatArray, count: Int) = submit { integrator.integrateWorldPoints(xyzc, count) }

    private fun submit(work: () -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        try {
            worker.execute {
                try {
                    work()
                    publishPreview(force = false)
                } catch (t: Throwable) {
                    Log.e(TAG, "Fusion failed", t)
                } finally {
                    busy.set(false)
                }
            }
        } catch (e: RejectedExecutionException) {
            busy.set(false)
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
        private const val PREVIEW_INTERVAL_MS = 400L
        private const val PREVIEW_MAX_POINTS = 400_000
    }
}
