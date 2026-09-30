package com.banyawa.sitescanner.core.scene

import com.banyawa.sitescanner.core.geometry.Vec2
import kotlinx.serialization.Serializable
import kotlin.math.cos
import kotlin.math.sin

/** What a piece of the scanned surface is. Ordinals are stored per triangle in [SceneLayers.triangleLayer]. */
enum class SceneLayer { FLOOR, CEILING, WALL, OBJECT }

/**
 * A free-standing thing in the room (a machine, a cabinet, a pallet): the triangles the
 * classifier grouped together, seen from above as [footprint] and boxed by [box]. Lengths
 * in metres; plan coordinates are the site frame of the floor plan's alignment.
 */
@Serializable
data class SceneObject(
    /** 1-based, in order of footprint area (largest first); shown as the tag "M<id>". */
    val id: Int,
    /** Convex outline of the object seen from above, counter-clockwise, plan coordinates. */
    val footprint: List<Vec2>,
    /** Tightest rectangle around the footprint, with the object's height. */
    val box: OrientedBox,
    /** Height of the lowest and highest surface above the floor. */
    val bottom: Float,
    val top: Float,
    val triangleCount: Int,
    /** Shortest distance from the footprint to a wall of the plan, or null without walls. */
    val wallClearance: Float? = null,
) {
    val height: Float get() = top - bottom
    val footprintArea: Float get() = polygonArea(footprint)

    companion object {
        fun polygonArea(polygon: List<Vec2>): Float {
            var sum = 0f
            for (i in polygon.indices) {
                val a = polygon[i]
                val b = polygon[(i + 1) % polygon.size]
                sum += a.x * b.y - b.x * a.y
            }
            return kotlin.math.abs(sum) / 2f
        }
    }
}

/** A rectangle in the plan turned by [yawRad] about its [centre]: [length] along the turned x axis, [width] across. */
@Serializable
data class OrientedBox(val centre: Vec2, val length: Float, val width: Float, val yawRad: Float) {
    /** The four corners, counter-clockwise from the (−length/2, −width/2) one. */
    fun corners(): List<Vec2> {
        val c = cos(yawRad)
        val s = sin(yawRad)
        val hl = length / 2
        val hw = width / 2
        return listOf(Vec2(-hl, -hw), Vec2(hl, -hw), Vec2(hl, hw), Vec2(-hl, hw)).map { p ->
            Vec2(centre.x + p.x * c - p.y * s, centre.y + p.x * s + p.y * c)
        }
    }
}

/**
 * Heights of what is overhead, on a grid of [cell] metres over the plan: [heights] row by
 * row from ([originX], [originY]), the lowest overhead surface above head height in each
 * cell (the ceiling, or a beam or duct under it) as metres above the floor, NaN where
 * nothing overhead was seen.
 */
class CeilingMap(val originX: Float, val originY: Float, val cell: Float, val cols: Int, val rows: Int, val heights: FloatArray) {
    init {
        require(heights.size == cols * rows) { "one height per cell" }
    }

    fun heightAt(col: Int, row: Int): Float = heights[row * cols + col]

    /** The height above [p], or NaN outside the map or where nothing was seen. */
    fun heightAt(p: Vec2): Float {
        val col = ((p.x - originX) / cell).toInt()
        val row = ((p.y - originY) / cell).toInt()
        if (col < 0 || row < 0 || col >= cols || row >= rows) return Float.NaN
        return heights[row * cols + col]
    }

    /** Centre of the cell ([col], [row]) in plan coordinates. */
    fun cellCentre(col: Int, row: Int): Vec2 = Vec2(originX + (col + 0.5f) * cell, originY + (row + 0.5f) * cell)

    /** The lowest overhead height seen anywhere, and where. */
    val minHeight: Float
    val minAt: Vec2?

    /** The typical (median) height of the seen cells: the ceiling height proper, beams aside. */
    val typicalHeight: Float

    init {
        var best = Float.NaN
        var bestCell = -1
        val seen = ArrayList<Float>()
        for (i in heights.indices) {
            val h = heights[i]
            if (h.isNaN()) continue
            seen += h
            if (best.isNaN() || h < best) {
                best = h
                bestCell = i
            }
        }
        minHeight = best
        minAt = if (bestCell < 0) null else cellCentre(bestCell % cols, bestCell / cols)
        typicalHeight = if (seen.isEmpty()) Float.NaN else seen.sorted()[seen.size / 2]
    }
}

/**
 * The scanned surface sorted into floor, ceiling, walls and objects: [triangleLayer] holds
 * a [SceneLayer] ordinal per triangle of the mesh it was made from, [objects] the things
 * found standing in the room, [ceiling] what is overhead; [areas] square metres per layer.
 */
class SceneLayers(
    val triangleLayer: ByteArray,
    val objects: List<SceneObject>,
    val ceiling: CeilingMap?,
    val areas: FloatArray,
) {
    val triangleCount: Int get() = triangleLayer.size

    fun layerOf(triangle: Int): SceneLayer = SceneLayer.entries[triangleLayer[triangle].toInt()]

    fun area(layer: SceneLayer): Float = areas[layer.ordinal]

    fun count(layer: SceneLayer): Int = triangleLayer.count { it.toInt() == layer.ordinal }

    companion object {
        val EMPTY = SceneLayers(ByteArray(0), emptyList(), null, FloatArray(SceneLayer.entries.size))
    }
}
