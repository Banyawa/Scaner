package com.banyawa.sitescanner.data

import com.banyawa.sitescanner.core.export.Ply
import com.banyawa.sitescanner.core.floorplan.FloorPlanExtractor
import com.banyawa.sitescanner.core.floorplan.FloorPlanResult
import com.banyawa.sitescanner.core.pointcloud.PointCloud
import com.banyawa.sitescanner.core.project.ProjectRepository
import com.banyawa.sitescanner.core.project.ScanInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Loads scan point clouds and computes floor plans, caching the most recent result so
 * the viewer, the plan screen and exports of the same scan don't redo the work.
 */
class ScanAnalysis(private val repository: ProjectRepository) {
    private val mutex = Mutex()
    private var cachedKey: String? = null
    private var cachedCloud: PointCloud? = null
    private var cachedPlan: FloorPlanResult? = null

    suspend fun cloud(projectId: String, scan: ScanInfo): PointCloud = mutex.withLock {
        val key = key(projectId, scan)
        if (key != cachedKey) {
            cachedCloud = withContext(Dispatchers.IO) {
                val file = repository.scanFile(projectId, scan)
                if (file.isFile) Ply.read(file) else PointCloud.EMPTY
            }
            cachedPlan = null
            cachedKey = key
        }
        cachedCloud!!
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

    private fun key(projectId: String, scan: ScanInfo): String {
        val file = repository.scanFile(projectId, scan)
        return "$projectId/${scan.id}/${file.lastModified()}"
    }
}
