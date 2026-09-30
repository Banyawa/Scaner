package com.banyawa.sitescanner.data

import android.graphics.BitmapFactory
import android.util.Log
import com.banyawa.sitescanner.core.export.MeshPly
import com.banyawa.sitescanner.core.export.Ply
import com.banyawa.sitescanner.core.floorplan.Elevation
import com.banyawa.sitescanner.core.floorplan.ElevationBuilder
import com.banyawa.sitescanner.core.floorplan.FloorPlan
import com.banyawa.sitescanner.core.floorplan.FloorPlanExtractor
import com.banyawa.sitescanner.core.floorplan.FloorPlanResult
import com.banyawa.sitescanner.core.mesh.TexturedMesh
import com.banyawa.sitescanner.core.mesh.TriangleMesh
import com.banyawa.sitescanner.core.pointcloud.PointCloud
import com.banyawa.sitescanner.core.pointcloud.RgbImage
import com.banyawa.sitescanner.core.project.ProjectRepository
import com.banyawa.sitescanner.core.project.ScanInfo
import com.banyawa.sitescanner.core.scene.SceneClassifier
import com.banyawa.sitescanner.core.scene.SceneLayers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A scan's surface model as saved. [uv] and [atlas] are there when it has a photo texture;
 * older models, and builds whose texturing failed, have only their vertex colours.
 */
class LoadedMesh(val mesh: TriangleMesh, val uv: FloatArray?, val atlas: RgbImage?) {
    /** The model with its photo texture; null when it has only vertex colours. */
    val textured: TexturedMesh? =
        if (uv != null && atlas != null && uv.size == mesh.vertexCount * 2) TexturedMesh(mesh, uv, atlas) else null
}

/**
 * Loads scan point clouds and computes floor plans and elevations, caching the most recent
 * results so the viewer, the plan screen and exports of the same scan don't redo the work.
 */
class ScanAnalysis(val repository: ProjectRepository) {
    private val mutex = Mutex()
    private var cachedKey: String? = null
    private var cachedCloud: PointCloud? = null
    private var cachedPlan: FloorPlanResult? = null
    private var cachedElevations: List<Elevation>? = null
    private var cachedMeshKey: String? = null
    private var cachedMesh: LoadedMesh? = null
    private var cachedSceneKey: String? = null
    private var cachedScenePlan: FloorPlan? = null
    private var cachedScene: SceneLayers? = null

    suspend fun cloud(projectId: String, scan: ScanInfo): PointCloud = mutex.withLock {
        val key = key(projectId, scan)
        if (key != cachedKey) {
            cachedCloud = withContext(Dispatchers.IO) {
                val file = repository.scanFile(projectId, scan)
                if (file.isFile) Ply.read(file) else PointCloud.EMPTY
            }
            cachedPlan = null
            cachedElevations = null
            cachedKey = key
        }
        cachedCloud!!
    }

    /** The scan's colour surface model, or null when it has none (older scans, no depth). */
    suspend fun mesh(projectId: String, scan: ScanInfo): LoadedMesh? {
        if (!scan.hasMesh) return null
        val file = repository.meshFile(projectId, scan) ?: return null
        val atlasFile = repository.atlasFile(projectId, scan)
        val key = meshKey(projectId, scan, file, atlasFile)
        return mutex.withLock {
            if (key != cachedMeshKey) {
                // Drop the old model first: two textured models may not fit in memory together.
                cachedMesh = null
                cachedMeshKey = null
                cachedScene = null
                cachedSceneKey = null
                cachedMesh = withContext(Dispatchers.IO) {
                    if (file.isFile) {
                        val data = MeshPly.readTextured(file)
                        val atlas = if (data.uv != null && atlasFile != null) readAtlas(atlasFile) else null
                        LoadedMesh(data.mesh, data.uv, atlas)
                    } else {
                        null
                    }
                }
                cachedMeshKey = key
            }
            cachedMesh
        }
    }

    /** A rebuild rewrites both files; either changing means a cached model is stale. */
    private fun meshKey(projectId: String, scan: ScanInfo, file: File, atlasFile: File?): String =
        "$projectId/${scan.id}/${file.lastModified()}/${atlasFile?.lastModified()}"

    /**
     * The surface model sorted into floor, ceiling, walls and objects against the floor plan
     * (with the user's door / window edits), or null when the scan has no model. Worked out
     * again when the model's files, the point cloud or the plan change, like the other caches.
     */
    suspend fun sceneLayers(projectId: String, scan: ScanInfo): SceneLayers? {
        val loaded = mesh(projectId, scan) ?: return null
        val file = repository.meshFile(projectId, scan) ?: return null
        val plan = floorPlan(projectId, scan).plan
        val key = "${meshKey(projectId, scan, file, repository.atlasFile(projectId, scan))}/${key(projectId, scan)}"
        mutex.withLock {
            if (cachedSceneKey == key && cachedScenePlan == plan) cachedScene?.let { return it }
        }
        val layers = withContext(Dispatchers.Default) { SceneClassifier.classify(loaded.mesh, plan) }
        mutex.withLock {
            cachedSceneKey = key
            cachedScenePlan = plan
            cachedScene = layers
        }
        return layers
    }

    /**
     * The photo texture as 0xRRGGBB pixels, top row first like the texture coordinates.
     * Null when it is missing or does not fit in memory: the model then shows its vertex colours.
     */
    private fun readAtlas(file: File): RgbImage? {
        if (!file.isFile) return null
        return try {
            val bitmap = BitmapFactory.decodeFile(file.path) ?: return null
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            bitmap.recycle()
            for (i in pixels.indices) pixels[i] = pixels[i] and 0xFFFFFF
            RgbImage(w, h, pixels)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Not enough memory for the model's texture", e)
            null
        }
    }

    /** Floor plan with the user's door / window edits applied. */
    suspend fun floorPlan(projectId: String, scan: ScanInfo): FloorPlanResult {
        val raw = detectedFloorPlan(projectId, scan)
        val edits = scan.openingEdits ?: return raw
        return FloorPlanResult(edits.applyTo(raw.plan), raw.slice)
    }

    /** Floor plan exactly as detected from the point cloud. */
    suspend fun detectedFloorPlan(projectId: String, scan: ScanInfo): FloorPlanResult {
        val cloud = cloud(projectId, scan)
        val key = key(projectId, scan)
        mutex.withLock {
            if (cachedKey == key) cachedPlan?.let { return it }
        }
        val result = withContext(Dispatchers.Default) { FloorPlanExtractor().extract(cloud, scan.floorY) }
        mutex.withLock {
            if (cachedKey == key) cachedPlan = result
        }
        return result
    }

    /** Wall elevations showing the user's door / window edits and the scan's measurements. */
    suspend fun elevations(projectId: String, scan: ScanInfo): List<Elevation> {
        val cloud = cloud(projectId, scan)
        val key = key(projectId, scan)
        // The walls and their scan depend only on the point cloud; openings are put on after.
        val base = mutex.withLock { if (cachedKey == key) cachedElevations else null }
            ?: run {
                val detected = detectedFloorPlan(projectId, scan).plan
                val built = withContext(Dispatchers.Default) { ElevationBuilder().build(cloud, detected) }
                mutex.withLock { if (cachedKey == key) cachedElevations = built }
                built
            }
        val plan = floorPlan(projectId, scan).plan
        return ElevationBuilder.attach(base, plan, scan.measurements)
    }

    private fun key(projectId: String, scan: ScanInfo): String {
        val file = repository.scanFile(projectId, scan)
        return "$projectId/${scan.id}/${file.lastModified()}"
    }

    private companion object {
        const val TAG = "ScanAnalysis"
    }
}
