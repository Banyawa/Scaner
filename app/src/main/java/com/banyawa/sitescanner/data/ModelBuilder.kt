package com.banyawa.sitescanner.data

import android.graphics.BitmapFactory
import android.util.Log
import com.banyawa.sitescanner.core.capture.Capture
import com.banyawa.sitescanner.core.capture.ImageDecoder
import com.banyawa.sitescanner.core.capture.ProgressListener
import com.banyawa.sitescanner.core.capture.ReconstructionOptions
import com.banyawa.sitescanner.core.capture.Reconstructor
import com.banyawa.sitescanner.core.export.MeshPly
import com.banyawa.sitescanner.core.export.Ply
import com.banyawa.sitescanner.core.pointcloud.ColorImage
import com.banyawa.sitescanner.core.pointcloud.RgbImage
import com.banyawa.sitescanner.core.project.ProjectRepository
import com.banyawa.sitescanner.core.project.ScanInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** JPEG decoding with Android's codecs. */
object AndroidImageDecoder : ImageDecoder {
    override fun decode(bytes: ByteArray): ColorImage {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("Not an image")
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()
        return RgbImage(w, h, pixels)
    }
}

/**
 * Generates a scan's point cloud and colour 3D model from its recorded walk-through, in
 * the background, one scan at a time. Lives as long as the app, so leaving the screen
 * does not stop a build; [progress] (0..1 per scan id) drives the screens' indicators.
 */
class ModelBuilder(private val repository: ProjectRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val oneAtATime = Mutex()

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    /**
     * Builds [scan]'s model and saves it; returns the updated scan (or [scan] itself when
     * the recording has no usable depth). Waits for a build already running.
     */
    suspend fun build(projectId: String, scan: ScanInfo): ScanInfo {
        val dir = repository.captureDir(projectId, scan) ?: return scan
        return scope.async {
            oneAtATime.withLock {
                _progress.update { it + (scan.id to 0f) }
                try {
                    generate(projectId, scan, dir)
                } finally {
                    _progress.update { it - scan.id }
                }
            }
        }.await()
    }

    private fun generate(projectId: String, scan: ScanInfo, dir: java.io.File): ScanInfo {
        val capture = Capture.open(dir)
        if (capture.frames.none { it.hasDepth }) {
            Log.i(TAG, "Recording has no depth frames: nothing to generate")
            return scan
        }
        val listener = ProgressListener { stage, done, total ->
            // Fusing is most of the work; meshing the last stretch.
            val f = if (stage == "fuse") 0.85f * done / total.coerceAtLeast(1) else 0.85f + 0.15f * done / total.coerceAtLeast(1)
            _progress.update { it + (scan.id to f) }
        }
        val result = Reconstructor(AndroidImageDecoder, ReconstructionOptions(surfaceBlocks = blockBudget())).reconstruct(capture, listener)
        Log.i(TAG, "Generated ${result.cloud.size} points and ${result.mesh.triangleCount} triangles from ${result.depthFrames} frames")
        if (result.cloud.size == 0) return scan

        var updated = scan.copy(pointCount = result.cloud.size, floorY = scan.floorY ?: result.floorY)
        Ply.write(result.cloud, repository.scanFile(projectId, updated))
        updated = if (result.mesh.isEmpty()) {
            repository.meshFile(projectId, scan)?.delete()
            updated.copy(meshFile = null, meshTriangles = 0)
        } else {
            val meshed = updated.copy(meshFile = repository.meshFileName(scan), meshTriangles = result.mesh.triangleCount)
            MeshPly.write(result.mesh, repository.meshFile(projectId, meshed)!!)
            meshed
        }
        // The scan may have been renamed or measured meanwhile: keep those edits.
        val latest = repository.get(projectId)?.scan(scan.id) ?: updated
        val merged = latest.copy(pointCount = updated.pointCount, floorY = updated.floorY, meshFile = updated.meshFile, meshTriangles = updated.meshTriangles)
        repository.upsertScan(projectId, merged)
        return merged
    }

    private fun blockBudget(): Int = (Runtime.getRuntime().maxMemory() / 3 / 3_600).toInt().coerceIn(4_000, 60_000)

    private companion object {
        const val TAG = "ModelBuilder"
    }
}
