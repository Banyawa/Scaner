package com.banyawa.sitescanner.core.scene

import com.banyawa.sitescanner.core.floorplan.FloorPlan
import com.banyawa.sitescanner.core.geometry.Bounds2
import com.banyawa.sitescanner.core.geometry.Vec2
import com.banyawa.sitescanner.core.mesh.TriangleMesh
import kotlin.math.abs
import kotlin.math.sqrt

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
    /** A vertical surface on a wall's line but this far past its ends is still that wall (the extractor cuts walls short at clutter). */
    val wallEndExtension: Float = 0.2f,
    /**
     * An object spanning floor to ceiling (reaching within [wallPieceMargin] of both) whose
     * footprint is narrower than [wallPieceMaxWidth] is a wall the plan missed, not an object.
     */
    val wallPieceMaxWidth: Float = 0.3f,
    val wallPieceMargin: Float = 0.3f,
)

/**
 * Sorts a scan's mesh into floor, ceiling, walls and objects using the floor plan's
 * alignment (floor height, plan frame), wall lines and ceiling height.
 *
 * Per triangle: horizontal faces near the floor are floor, near the ceiling are ceiling;
 * vertical faces on a plan wall line are wall; everything else is object (machines,
 * furniture, ducts, wall-mounted cabinets). Objects are then the connected pieces of object
 * surface, merged when their footprints touch; pieces above head height (beams, pipes) are
 * not listed as objects but feed the ceiling map, and thin floor-to-ceiling pieces are walls
 * the plan missed. Without a plan wall list, vertical surfaces spanning floor to ceiling are
 * taken as walls.
 *
 * Face orientation is never trusted (the surface-nets mesh is not consistently wound), so
 * only |normal · up| is used.
 */
object SceneClassifier {
    fun classify(mesh: TriangleMesh, plan: FloorPlan, params: SceneParams = SceneParams()): SceneLayers {
        if (mesh.isEmpty()) return SceneLayers.EMPTY
        return SceneClassification(mesh, plan, params).run()
    }
}

private class SceneClassification(mesh: TriangleMesh, private val plan: FloorPlan, private val params: SceneParams) {
    private val triangleCount = mesh.triangleCount
    private val vertexCount = mesh.vertexCount
    private val indices = mesh.indices

    /** Vertex positions in the site frame: plan x, plan y, height above the floor. */
    private val site = FloatArray(vertexCount * 3)

    // Per triangle: centroid in the site frame, |unit normal · up| (1 flat, 0 vertical), area.
    private val cx = FloatArray(triangleCount)
    private val cy = FloatArray(triangleCount)
    private val ch = FloatArray(triangleCount)
    private val up = FloatArray(triangleCount)
    private val area = FloatArray(triangleCount)
    private val layer = ByteArray(triangleCount)

    // Plan walls unpacked for the distance loop: start, unit direction, length.
    private val walls = plan.walls.filter { it.length > 1e-4f }
    private val wallCount = walls.size
    private val wallX = FloatArray(wallCount) { walls[it].start.x }
    private val wallY = FloatArray(wallCount) { walls[it].start.y }
    private val wallDx = FloatArray(wallCount) { walls[it].direction.x }
    private val wallDy = FloatArray(wallCount) { walls[it].direction.y }
    private val wallLength = FloatArray(wallCount) { walls[it].length }

    /** Floor-to-ceiling height: the plan's, else estimated from the highest flat surfaces; NaN when unknown. */
    private var ceilingHeight = Float.NaN

    init {
        val alignment = plan.alignment
        val p = mesh.positions
        for (v in 0 until vertexCount) alignment.toSite(p[v * 3], p[v * 3 + 1], p[v * 3 + 2], site, v * 3)
        triangleGeometry()
    }

    fun run(): SceneLayers {
        ceilingHeight = plan.roomHeight?.takeIf { it > 1f } ?: estimateCeilingHeight()
        label()
        if (wallCount == 0) wallsWithoutPlan()
        val objects = objects()
        return SceneLayers(layer, objects, ceilingMap(), areas())
    }

    private fun triangleGeometry() {
        for (t in 0 until triangleCount) {
            val a = indices[t * 3] * 3
            val b = indices[t * 3 + 1] * 3
            val c = indices[t * 3 + 2] * 3
            val ax = site[a]
            val ay = site[a + 1]
            val ah = site[a + 2]
            val e1x = site[b] - ax
            val e1y = site[b + 1] - ay
            val e1h = site[b + 2] - ah
            val e2x = site[c] - ax
            val e2y = site[c + 1] - ay
            val e2h = site[c + 2] - ah
            cx[t] = (ax + site[b] + site[c]) / 3f
            cy[t] = (ay + site[b + 1] + site[c + 1]) / 3f
            ch[t] = (ah + site[b + 2] + site[c + 2]) / 3f
            val nx = e1y * e2h - e1h * e2y
            val ny = e1h * e2x - e1x * e2h
            val nh = e1x * e2y - e1y * e2x
            val len = sqrt(nx * nx + ny * ny + nh * nh)
            area[t] = len / 2f
            up[t] = if (len > 0f) abs(nh) / len else 0f
        }
    }

    /**
     * Without a measured ceiling: the topmost band of flat surfaces (their 95th percentile
     * height, weighted by area) when it is at least [MIN_CEILING_HEIGHT], else unknown.
     */
    private fun estimateCeilingHeight(): Float {
        val minNy = params.horizontalMinNy
        val floorBand = params.floorBand
        val p95 = heightPercentile(0.95f) { t -> up[t] >= minNy && ch[t] > floorBand }
        return if (!p95.isNaN() && p95 >= MIN_CEILING_HEIGHT) p95 else Float.NaN
    }

    /** Area-weighted [fraction] quantile of the centroid heights of the triangles [include] picks; NaN with none. */
    private inline fun heightPercentile(fraction: Float, include: (Int) -> Boolean): Float {
        var maxH = 0f
        for (t in 0 until triangleCount) if (include(t) && ch[t] > maxH) maxH = ch[t]
        val bin = 0.01f
        val bins = minOf((maxH / bin).toInt() + 1, 100_000)
        val weight = DoubleArray(bins)
        var total = 0.0
        for (t in 0 until triangleCount) {
            if (!include(t)) continue
            val h = ch[t]
            if (h < 0f) continue
            val b = minOf((h / bin).toInt(), bins - 1)
            weight[b] += area[t]
            total += area[t]
        }
        if (total <= 0.0) return Float.NaN
        var acc = 0.0
        for (b in 0 until bins) {
            acc += weight[b]
            if (acc >= total * fraction) return (b + 0.5f) * bin
        }
        return maxH
    }

    private fun label() {
        val floorBand = params.floorBand
        val ceilingLow = if (ceilingHeight.isNaN()) Float.POSITIVE_INFINITY else ceilingHeight - params.ceilingBand
        val minNy = params.horizontalMinNy
        val maxNy = params.verticalMaxNy
        for (t in 0 until triangleCount) {
            val h = ch[t]
            val u = up[t]
            val l = if (u >= minNy) {
                when {
                    abs(h) <= floorBand -> FLOOR
                    h >= ceilingLow -> CEILING
                    else -> OBJECT
                }
            } else if (u <= maxNy) {
                if (nearWall(cx[t], cy[t])) WALL else OBJECT
            } else {
                // Slanted: a ramp, a coved ceiling edge, a wall's rounded corner, or an object.
                when {
                    abs(h) <= floorBand -> FLOOR
                    h >= ceilingLow -> CEILING
                    nearWall(cx[t], cy[t]) -> WALL
                    else -> OBJECT
                }
            }
            layer[t] = l.toByte()
        }
    }

    /** Within [SceneParams.wallTolerance] of a wall segment, or of its line just past the ends. */
    private fun nearWall(x: Float, y: Float): Boolean {
        val tol = params.wallTolerance
        val tol2 = tol * tol
        val ext = params.wallEndExtension
        for (w in 0 until wallCount) {
            val px = x - wallX[w]
            val py = y - wallY[w]
            val dx = wallDx[w]
            val dy = wallDy[w]
            val perp = abs(px * dy - py * dx)
            if (perp > tol) continue
            val t = px * dx + py * dy
            val len = wallLength[w]
            if (t >= -ext && t <= len + ext) return true
            val over = if (t < 0f) -t else t - len
            if (perp * perp + over * over <= tol2) return true
        }
        return false
    }

    /** Distance from ([x], [y]) to the nearest wall segment; infinite without walls. */
    private fun wallDistance(x: Float, y: Float): Float {
        var best = Float.POSITIVE_INFINITY
        for (w in 0 until wallCount) {
            val px = x - wallX[w]
            val py = y - wallY[w]
            val t = (px * wallDx[w] + py * wallDy[w]).coerceIn(0f, wallLength[w])
            val ex = px - t * wallDx[w]
            val ey = py - t * wallDy[w]
            val d2 = ex * ex + ey * ey
            if (d2 < best) best = d2
        }
        return sqrt(best)
    }

    /**
     * No wall lines to test against: connected vertical surfaces reaching from the floor to
     * the ceiling (or, with no ceiling either, to the top of the vertical surfaces) are walls.
     */
    private fun wallsWithoutPlan() {
        val maxNy = params.verticalMaxNy
        val parent = IntArray(vertexCount) { it }
        for (t in 0 until triangleCount) {
            if (layer[t].toInt() != OBJECT || up[t] > maxNy) continue
            union(parent, indices[t * 3], indices[t * 3 + 1])
            union(parent, indices[t * 3], indices[t * 3 + 2])
        }
        val minH = FloatArray(vertexCount) { Float.POSITIVE_INFINITY }
        val maxH = FloatArray(vertexCount) { Float.NEGATIVE_INFINITY }
        for (t in 0 until triangleCount) {
            if (layer[t].toInt() != OBJECT || up[t] > maxNy) continue
            val r = find(parent, indices[t * 3])
            for (j in 0 until 3) {
                val h = site[indices[t * 3 + j] * 3 + 2]
                if (h < minH[r]) minH[r] = h
                if (h > maxH[r]) maxH[r] = h
            }
        }
        val top = if (!ceilingHeight.isNaN()) {
            ceilingHeight
        } else {
            heightPercentile(0.95f) { t -> up[t] <= maxNy }.takeUnless { it.isNaN() } ?: MIN_CEILING_HEIGHT
        }
        val margin = params.wallPieceMargin
        for (t in 0 until triangleCount) {
            if (layer[t].toInt() != OBJECT || up[t] > maxNy) continue
            val r = find(parent, indices[t * 3])
            if (minH[r] <= margin && maxH[r] >= top - margin) layer[t] = WALL.toByte()
        }
    }

    private fun objects(): List<SceneObject> {
        // Connected pieces of object surface over shared vertices.
        val parent = IntArray(vertexCount) { it }
        var objectTriangles = 0
        for (t in 0 until triangleCount) {
            if (layer[t].toInt() != OBJECT) continue
            objectTriangles++
            union(parent, indices[t * 3], indices[t * 3 + 1])
            union(parent, indices[t * 3], indices[t * 3 + 2])
        }
        if (objectTriangles == 0) return emptyList()
        val componentOfRoot = IntArray(vertexCount) { -1 }
        val component = IntArray(triangleCount) { -1 }
        var componentCount = 0
        for (t in 0 until triangleCount) {
            if (layer[t].toInt() != OBJECT) continue
            val r = find(parent, indices[t * 3])
            if (componentOfRoot[r] < 0) componentOfRoot[r] = componentCount++
            component[t] = componentOfRoot[r]
        }

        // Footprint boxes per piece, then pieces whose boxes come within mergeDistance are one object.
        val minX = FloatArray(componentCount) { Float.POSITIVE_INFINITY }
        val maxX = FloatArray(componentCount) { Float.NEGATIVE_INFINITY }
        val minY = FloatArray(componentCount) { Float.POSITIVE_INFINITY }
        val maxY = FloatArray(componentCount) { Float.NEGATIVE_INFINITY }
        for (t in 0 until triangleCount) {
            val c = component[t]
            if (c < 0) continue
            for (j in 0 until 3) {
                val v = indices[t * 3 + j] * 3
                val x = site[v]
                val y = site[v + 1]
                if (x < minX[c]) minX[c] = x
                if (x > maxX[c]) maxX[c] = x
                if (y < minY[c]) minY[c] = y
                if (y > maxY[c]) maxY[c] = y
            }
        }
        val group = IntArray(componentCount) { it }
        mergeNearby(group, minX, maxX, minY, maxY)
        val groupOfRoot = IntArray(componentCount) { -1 }
        var groupCount = 0
        val groupOf = IntArray(componentCount)
        for (c in 0 until componentCount) {
            val r = find(group, c)
            if (groupOfRoot[r] < 0) groupOfRoot[r] = groupCount++
            groupOf[c] = groupOfRoot[r]
        }

        // Triangles per object in CSR form, with each object's triangle count and height range.
        val count = IntArray(groupCount)
        val bottom = FloatArray(groupCount) { Float.POSITIVE_INFINITY }
        val top = FloatArray(groupCount) { Float.NEGATIVE_INFINITY }
        for (t in 0 until triangleCount) {
            val c = component[t]
            if (c < 0) continue
            val g = groupOf[c]
            count[g]++
            for (j in 0 until 3) {
                val h = site[indices[t * 3 + j] * 3 + 2]
                if (h < bottom[g]) bottom[g] = h
                if (h > top[g]) top[g] = h
            }
        }
        val start = IntArray(groupCount + 1)
        for (g in 0 until groupCount) start[g + 1] = start[g] + count[g]
        val fill = start.copyOf()
        val triangles = IntArray(objectTriangles)
        for (t in 0 until triangleCount) {
            val c = component[t]
            if (c < 0) continue
            triangles[fill[groupOf[c]]++] = t
        }

        val stamp = IntArray(vertexCount) { -1 }
        val margin = params.wallPieceMargin
        val result = ArrayList<SceneObject>()
        for (g in 0 until groupCount) {
            val height = top[g] - bottom[g]
            // Beams, ducts, pipes and mezzanine undersides: overhead, not standing in the room.
            if (bottom[g] >= params.overheadMinHeight) continue
            val spansRoom = !ceilingHeight.isNaN() && bottom[g] <= margin && top[g] >= ceilingHeight - margin
            val tooSmall = count[g] < params.minObjectTriangles || height < params.minObjectHeight
            if (tooSmall && !spansRoom) continue
            val hull = footprint(triangles, start[g], start[g + 1], stamp, g)
            val box = Footprints.box(hull)
            if (spansRoom && box.width < params.wallPieceMaxWidth) {
                for (k in start[g] until start[g + 1]) layer[triangles[k]] = WALL.toByte()
                continue
            }
            if (tooSmall || hull.size < 3 || SceneObject.polygonArea(hull) < params.minObjectArea) continue
            val clearance = if (wallCount == 0) null else hull.minOf { wallDistance(it.x, it.y) }
            result += SceneObject(0, hull, box, bottom[g], top[g], count[g], clearance)
        }
        result.sortByDescending { it.footprintArea }
        return result.mapIndexed { i, o -> o.copy(id = i + 1) }
    }

    /**
     * Unions pieces whose footprint boxes come within [SceneParams.mergeDistance], sweeping
     * along x; merged boxes grow, so it repeats until nothing more joins.
     */
    private fun mergeNearby(group: IntArray, minX: FloatArray, maxX: FloatArray, minY: FloatArray, maxY: FloatArray) {
        val d = params.mergeDistance
        val n = group.size
        while (true) {
            var changed = false
            val roots = (0 until n).filter { find(group, it) == it }.sortedBy { minX[it] }
            for (i in roots.indices) {
                val a = roots[i]
                for (j in i + 1 until roots.size) {
                    val b = roots[j]
                    if (minX[b] > maxX[a] + d) break
                    if (minY[b] > maxY[a] + d || maxY[b] < minY[a] - d) continue
                    val ra = find(group, a)
                    val rb = find(group, b)
                    if (ra == rb) continue
                    group[ra] = rb
                    changed = true
                }
            }
            if (!changed) return
            for (c in 0 until n) {
                val r = find(group, c)
                if (r == c) continue
                if (minX[c] < minX[r]) minX[r] = minX[c]
                if (maxX[c] > maxX[r]) maxX[r] = maxX[c]
                if (minY[c] < minY[r]) minY[r] = minY[c]
                if (maxY[c] > maxY[r]) maxY[r] = maxY[c]
            }
        }
    }

    /** Convex hull of the plan positions of the vertices of triangles [from, to) of [triangles]. */
    private fun footprint(triangles: IntArray, from: Int, to: Int, stamp: IntArray, mark: Int): List<Vec2> {
        var n = 0
        for (k in from until to) {
            val t = triangles[k]
            for (j in 0 until 3) if (stamp[indices[t * 3 + j]] != mark) {
                stamp[indices[t * 3 + j]] = mark
                n++
            }
        }
        val xy = FloatArray(n * 2)
        var i = 0
        for (k in from until to) {
            val t = triangles[k]
            for (j in 0 until 3) {
                val v = indices[t * 3 + j]
                if (stamp[v] != mark) continue
                stamp[v] = -1
                xy[i * 2] = site[v * 3]
                xy[i * 2 + 1] = site[v * 3 + 1]
                i++
            }
        }
        return Footprints.hull(xy, n)
    }

    /**
     * Lowest overhead surface per cell: ceiling triangles, and flat object surface above
     * head height (the underside of beams, ducts, pipes, mezzanines).
     */
    private fun ceilingMap(): CeilingMap? {
        val bounds = plan.bounds() ?: siteBounds() ?: return null
        var cell = params.ceilingCell.coerceAtLeast(0.01f)
        var cols = (bounds.width / cell).toInt() + 1
        var rows = (bounds.height / cell).toInt() + 1
        while (cols.toLong() * rows > MAX_CEILING_CELLS) {
            cell *= 2f
            cols = (bounds.width / cell).toInt() + 1
            rows = (bounds.height / cell).toInt() + 1
        }
        val heights = FloatArray(cols * rows) { Float.NaN }
        val minNy = params.horizontalMinNy
        val overhead = params.overheadMinHeight
        var any = false
        for (t in 0 until triangleCount) {
            val l = layer[t].toInt()
            val counts = l == CEILING || (l == OBJECT && up[t] >= minNy && ch[t] >= overhead)
            if (!counts) continue
            val fx = (cx[t] - bounds.min.x) / cell
            val fy = (cy[t] - bounds.min.y) / cell
            if (fx < 0f || fy < 0f) continue
            val col = fx.toInt()
            val row = fy.toInt()
            if (col >= cols || row >= rows) continue
            val i = row * cols + col
            val h = ch[t]
            if (heights[i].isNaN() || h < heights[i]) {
                heights[i] = h
                any = true
            }
        }
        return if (any) CeilingMap(bounds.min.x, bounds.min.y, cell, cols, rows, heights) else null
    }

    private fun siteBounds(): Bounds2? {
        if (vertexCount == 0) return null
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (v in 0 until vertexCount) {
            val x = site[v * 3]
            val y = site[v * 3 + 1]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        return Bounds2(Vec2(minX, minY), Vec2(maxX, maxY))
    }

    private fun areas(): FloatArray {
        val sums = DoubleArray(SceneLayer.entries.size)
        for (t in 0 until triangleCount) sums[layer[t].toInt()] += area[t]
        return FloatArray(sums.size) { sums[it].toFloat() }
    }

    private fun find(parent: IntArray, v: Int): Int {
        var x = v
        while (parent[x] != x) {
            parent[x] = parent[parent[x]]
            x = parent[x]
        }
        return x
    }

    private fun union(parent: IntArray, a: Int, b: Int) {
        val ra = find(parent, a)
        val rb = find(parent, b)
        if (ra != rb) parent[ra] = rb
    }

    private companion object {
        val FLOOR = SceneLayer.FLOOR.ordinal
        val CEILING = SceneLayer.CEILING.ordinal
        val WALL = SceneLayer.WALL.ordinal
        val OBJECT = SceneLayer.OBJECT.ordinal

        /** Flat surfaces lower than this are furniture tops, not a ceiling. */
        const val MIN_CEILING_HEIGHT = 2.2f

        /** Cap on the ceiling map's size; the cell grows for wider sites. */
        const val MAX_CEILING_CELLS = 4_000_000L
    }
}
