package com.banyawa.sitescanner.core.project

import com.banyawa.sitescanner.core.floorplan.OpeningEdits
import com.banyawa.sitescanner.core.geometry.Vec3
import kotlinx.serialization.Serializable

/** A point-to-point distance taken in AR. Coordinates are in the scan's ARCore world frame. */
@Serializable
data class Measurement(
    val id: String,
    val label: String = "",
    val start: Vec3,
    val end: Vec3,
    val createdAt: Long = 0L,
) {
    val lengthM: Float get() = start.distanceTo(end)
}

enum class CaptureMode {
    /** Dense ARCore Depth API (raw depth) fusion. */
    RAW_DEPTH,

    /** Sparse ARCore feature points; used on devices without Depth API support. */
    FEATURE_POINTS,
}

/**
 * One AR session. All geometry of a scan (points, measurements, floor) shares that
 * session's coordinate frame, so they line up with each other in every export.
 */
@Serializable
data class ScanInfo(
    val id: String,
    val name: String,
    val createdAt: Long,
    val pointCount: Int = 0,
    val pointFile: String,
    /** Height of the floor plane ARCore detected, if any. */
    val floorY: Float? = null,
    val captureMode: CaptureMode = CaptureMode.RAW_DEPTH,
    val durationSec: Int = 0,
    val measurements: List<Measurement> = emptyList(),
    /** Doors / windows as reviewed by the user; null = use automatic detection. */
    val openingEdits: OpeningEdits? = null,
    /** Colour surface mesh fused from depth while scanning (PLY in the project folder), if any. */
    val meshFile: String? = null,
    val meshTriangles: Int = 0,
) {
    val hasMesh: Boolean get() = meshFile != null && meshTriangles > 0
}

/** A site's position on the map (WGS84 degrees), from GPS or picked by hand. */
@Serializable
data class GeoPin(
    val latitude: Double,
    val longitude: Double,
    /** Horizontal accuracy in metres for a GPS fix; null when picked on the map. */
    val accuracyM: Float? = null,
) {
    /** "13.756331, 100.501765": what map apps and search boxes accept. */
    val coordinates: String get() = String.format(java.util.Locale.US, "%.6f, %.6f", latitude, longitude)
}

@Serializable
data class Project(
    val id: String,
    val name: String,
    /** Address or description of the site, as typed or looked up from [pin]. */
    val location: String = "",
    val notes: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val scans: List<ScanInfo> = emptyList(),
    val pin: GeoPin? = null,
) {
    fun scan(id: String): ScanInfo? = scans.firstOrNull { it.id == id }
}
