package com.banyawa.sitescanner.core.scene

import com.banyawa.sitescanner.core.floorplan.FloorPlan
import com.banyawa.sitescanner.core.mesh.TriangleMesh

data class SceneParams(
    /** A horizontal surface within this height of the floor is floor. */
    val floorBand: Float = 0.15f,
    /** A horizontal surface within this distance under the ceiling height is ceiling. */
    val ceilingBand: Float = 0.35f,
    /** A vertical surface within this distance of a plan wall line is wall. */
    val wallTolerance: Float = 0.15f,
    /** |normal.y| above this counts as horizontal, below [verticalMaxNy] as vertical. */
    val horizontalMinNy: Float = 0.8f,
    val verticalMaxNy: Float = 0.35f,
    /** Objects smaller than this (triangles, footprint area m², height m) are dropped. */
    val minObjectTriangles: Int = 60,
    val minObjectArea: Float = 0.02f,
    val minObjectHeight: Float = 0.1f,
    /** Object parts whose footprints come within this distance are one object. */
    val mergeDistance: Float = 0.05f,
    /** Cell size of the ceiling height map, and the height above which a down-facing surface counts as overhead. */
    val ceilingCell: Float = 0.25f,
    val overheadMinHeight: Float = 1.9f,
)

/**
 * Sorts a scan's mesh into floor, ceiling, walls and objects using the floor plan's
 * alignment (floor height, plan frame), wall lines and ceiling height. Contract for the
 * app: the implementation lands with core tests; until then everything is an object.
 */
object SceneClassifier {
    fun classify(mesh: TriangleMesh, plan: FloorPlan, params: SceneParams = SceneParams()): SceneLayers {
        if (mesh.isEmpty()) return SceneLayers.EMPTY
        val layer = ByteArray(mesh.triangleCount) { SceneLayer.OBJECT.ordinal.toByte() }
        return SceneLayers(layer, emptyList(), null, FloatArray(SceneLayer.entries.size))
    }
}
