package com.banyawa.sitescanner.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.banyawa.sitescanner.core.capture.Capture
import com.banyawa.sitescanner.core.capture.ImageDecoder
import com.banyawa.sitescanner.core.capture.ImageEncoder
import com.banyawa.sitescanner.core.capture.ProgressListener
import com.banyawa.sitescanner.core.capture.ReconstructionOptions
import com.banyawa.sitescanner.core.capture.Reconstructor
import com.banyawa.sitescanner.core.export.MeshPly
import com.banyawa.sitescanner.core.export.Ply
import com.banyawa.sitescanner.core.mesh.MeshTexturer
import com.banyawa.sitescanner.core.mesh.TexturedMesh
import com.banyawa.sitescanner.core.mesh.TexturingOptions
import com.banyawa.sitescanner.core.mesh.TriangleMesh
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
import java.io.ByteArrayOutputStream
import java.io.File
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

/** JPEG encoding with Android's codecs, for the model's texture atlas and GLB exports. */
object AndroidImageEncoder : ImageEncoder {
    override fun encodeJpeg(image: RgbImage, quality: Int): ByteArray {
        val w = image.width
        val h = image.height
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            // Row by row, so a 4096² atlas does not need a second full-size copy. The image
            // has no alpha; a Bitmap without it would count every pixel as transparent.
            val row = IntArray(w)
            for (y in 0 until h) {
                for (x in 0 until w) row[x] = image.pixels[y * w + x] or OPAQUE
                bitmap.setPixels(row, 0, w, 0, y, w, 1)
            }
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(0, 100), out)) throw IOException("JPEG encoding failed")
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private const val OPAQUE = 0xFF000000.toInt()
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

    private fun generate(projectId: String, scan: ScanInfo, dir: File): ScanInfo {
        val capture = Capture.open(dir)
        if (capture.frames.none { it.hasDepth }) {
            Log.i(TAG, "Recording has no depth frames: nothing to generate")
            return scan
        }
        val listener = ProgressListener { stage, done, total ->
            // Fusing is most of the work; meshing the last stretch before texturing.
            val f = if (stage == "fuse") 0.85f * done / total.coerceAtLeast(1) else 0.85f + 0.15f * done / total.coerceAtLeast(1)
            report(scan.id, RECONSTRUCTION_SHARE * f)
        }
        val result = Reconstructor(AndroidImageDecoder, ReconstructionOptions(surfaceBlocks = blockBudget())).reconstruct(capture, listener)
        Log.i(TAG, "Generated ${result.cloud.size} points and ${result.mesh.triangleCount} triangles from ${result.depthFrames} frames")
        if (result.cloud.size == 0) return scan

        var updated = scan.copy(pointCount = result.cloud.size, floorY = scan.floorY ?: result.floorY)
        Ply.write(result.cloud, repository.scanFile(projectId, updated))
        // This build's texture replaces an earlier build's, or there is none any more.
        val atlasFile = File(repository.projectDir(projectId), repository.atlasFileName(scan))
        updated = if (result.mesh.isEmpty()) {
            repository.meshFile(projectId, scan)?.delete()
            atlasFile.delete()
            updated.copy(meshFile = null, meshTriangles = 0, meshAtlas = null)
        } else {
            val texture = texture(scan.id, result.mesh, capture)
            // Texturing splits vertices where texture charts meet: save that mesh with its UVs.
            val mesh = texture?.textured?.mesh ?: result.mesh
            if (texture != null) atlasFile.writeBytes(texture.jpeg) else atlasFile.delete()
            val meshed = updated.copy(
                meshFile = repository.meshFileName(scan),
                meshTriangles = mesh.triangleCount,
                meshAtlas = if (texture != null) repository.atlasFileName(scan) else null,
            )
            MeshPly.write(mesh, repository.meshFile(projectId, meshed)!!, uv = texture?.textured?.uv)
            meshed
        }
        // The scan may have been renamed or measured meanwhile: keep those edits.
        val latest = repository.get(projectId)?.scan(scan.id) ?: updated
        val merged = latest.copy(
            pointCount = updated.pointCount,
            floorY = updated.floorY,
            meshFile = updated.meshFile,
            meshTriangles = updated.meshTriangles,
            meshAtlas = updated.meshAtlas,
        )
        repository.upsertScan(projectId, merged)
        return merged
    }

    /** A photo-textured model and its atlas, already JPEG-encoded for saving. */
    private class Texture(val textured: TexturedMesh, val jpeg: ByteArray)

    /**
     * Paints the recorded photos onto [mesh]. Null when that does not work out (no frame
     * sees the surface, or the atlas does not fit in memory): the model then keeps the
     * vertex colours it already has, which is far better than no model.
     */
    private fun texture(scanId: String, mesh: TriangleMesh, capture: Capture): Texture? {
        // A 4096² atlas takes 64 MB as pixels, and as much again while it is encoded.
        val options = TexturingOptions(atlasSize = if (Runtime.getRuntime().maxMemory() < 400L shl 20) 2048 else 4096)
        val listener = ProgressListener { _, done, total ->
            report(scanId, RECONSTRUCTION_SHARE + (1f - RECONSTRUCTION_SHARE) * done / total.coerceAtLeast(1))
        }
        return try {
            val result = MeshTexturer(AndroidImageDecoder, options).textureWithStats(mesh, capture, listener) ?: return null
            val textured = result.textured
            val jpeg = AndroidImageEncoder.encodeJpeg(textured.atlas, options.quality)
            Log.i(TAG, "Textured ${textured.triangleCount} triangles with a ${textured.atlas.width}x${textured.atlas.height} atlas in ${result.stats.millis} ms")
            result.stats.seams?.let { seams ->
                Log.i(TAG, "Levelled ${seams.seamPairs} seam vertices across ${seams.charts} charts: ${seams.seamDifferenceBefore} -> ${seams.seamDifferenceAfter} levels in ${seams.millis} ms")
            }
            Texture(textured, jpeg)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Not enough memory to texture the model; keeping vertex colours", e)
            null
        } catch (e: Throwable) {
            Log.w(TAG, "Texturing failed; keeping vertex colours", e)
            null
        }
    }

    /** Progress of [scanId]; never moves backwards, whatever the stages report. */
    private fun report(scanId: String, f: Float) {
        _progress.update { it + (scanId to maxOf(f, it[scanId] ?: 0f)) }
    }

    private fun blockBudget(): Int = (Runtime.getRuntime().maxMemory() / 3 / 3_600).toInt().coerceIn(4_000, 60_000)

    private companion object {
        const val TAG = "ModelBuilder"

        /** The part of a build's progress bar reconstruction takes; texturing the rest. */
        const val RECONSTRUCTION_SHARE = 0.7f
    }
}
