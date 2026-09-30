package com.banyawa.sitescanner.core.scene

import com.banyawa.sitescanner.core.floorplan.SiteAlignment
import com.banyawa.sitescanner.core.geometry.Vec2
import com.banyawa.sitescanner.core.geometry.Vec3
import com.banyawa.sitescanner.core.mesh.TriangleMesh
import com.banyawa.sitescanner.core.util.FloatList
import com.banyawa.sitescanner.core.util.IntList

/**
 * Builds test scenes out of rectangular quad-grid patches given in the site frame (plan x,
 * plan y, height above the floor), stored in ARCore world coordinates through [alignment],
 * and remembers what each triangle really is (a [tag] per triangle) to grade the classifier.
 * Patches alternate their winding: the classifier must not depend on it.
 */
class SceneMeshBuilder(private val alignment: SiteAlignment, private val step: Float = 0.1f) {
    private val positions = FloatList()
    private val indices = IntList()
    private val tags = ArrayList<String>()
    private var patches = 0

    /** A rectangle from [origin] spanned by [u] and [v] (site frame metres), in quads of about [step]. */
    fun patch(origin: Vec3, u: Vec3, v: Vec3, tag: String) {
        val n = maxOf(1, Math.round(u.length() / step))
        val m = maxOf(1, Math.round(v.length() / step))
        val base = positions.size / 3
        for (j in 0..m) for (i in 0..n) {
            val p = origin + u * (i.toFloat() / n) + v * (j.toFloat() / m)
            val w = alignment.toWorld(Vec2(p.x, p.y), p.z)
            positions.add(w.x)
            positions.add(w.y)
            positions.add(w.z)
        }
        val flip = patches++ % 2 == 1
        for (j in 0 until m) for (i in 0 until n) {
            val a = base + j * (n + 1) + i
            val b = a + 1
            val c = a + n + 2
            val d = a + n + 1
            if (flip) {
                triangle(a, c, b)
                triangle(a, d, c)
            } else {
                triangle(a, b, c)
                triangle(a, c, d)
            }
            tags += tag
            tags += tag
        }
    }

    /** An axis-aligned box from [min] to [max] (site frame): four sides, plus its [top] and [bottom] faces. */
    fun box(min: Vec3, max: Vec3, tag: String, top: Boolean = true, bottom: Boolean = false) {
        val sx = max.x - min.x
        val sy = max.y - min.y
        val sz = max.z - min.z
        patch(Vec3(min.x, min.y, min.z), Vec3(sx, 0f, 0f), Vec3(0f, 0f, sz), tag)
        patch(Vec3(max.x, min.y, min.z), Vec3(0f, sy, 0f), Vec3(0f, 0f, sz), tag)
        patch(Vec3(max.x, max.y, min.z), Vec3(-sx, 0f, 0f), Vec3(0f, 0f, sz), tag)
        patch(Vec3(min.x, max.y, min.z), Vec3(0f, -sy, 0f), Vec3(0f, 0f, sz), tag)
        if (top) patch(Vec3(min.x, min.y, max.z), Vec3(sx, 0f, 0f), Vec3(0f, sy, 0f), tag)
        if (bottom) patch(Vec3(min.x, min.y, min.z), Vec3(sx, 0f, 0f), Vec3(0f, sy, 0f), tag)
    }

    /** A [width] × [depth] × [height] room with its floor at the origin: floor, ceiling and four walls. */
    fun room(width: Float, depth: Float, height: Float) {
        patch(Vec3(0f, 0f, 0f), Vec3(width, 0f, 0f), Vec3(0f, depth, 0f), FLOOR)
        patch(Vec3(0f, 0f, height), Vec3(width, 0f, 0f), Vec3(0f, depth, 0f), CEILING)
        patch(Vec3(0f, 0f, 0f), Vec3(width, 0f, 0f), Vec3(0f, 0f, height), WALL)
        patch(Vec3(width, 0f, 0f), Vec3(0f, depth, 0f), Vec3(0f, 0f, height), WALL)
        patch(Vec3(width, depth, 0f), Vec3(-width, 0f, 0f), Vec3(0f, 0f, height), WALL)
        patch(Vec3(0f, depth, 0f), Vec3(0f, -depth, 0f), Vec3(0f, 0f, height), WALL)
    }

    private fun triangle(a: Int, b: Int, c: Int) {
        indices.add(a)
        indices.add(b)
        indices.add(c)
    }

    fun build(): TriangleMesh {
        val p = positions.toArray()
        return TriangleMesh(p, ByteArray(p.size) { 0x60 }, indices.toArray())
    }

    /** What each triangle really is, by index. */
    val truth: List<String> get() = tags

    fun triangles(tag: String): List<Int> = tags.indices.filter { tags[it] == tag }

    companion object {
        const val FLOOR = "floor"
        const val CEILING = "ceiling"
        const val WALL = "wall"
    }
}
