package com.banyawa.sitescanner.core.mesh

import com.banyawa.sitescanner.core.geometry.Bounds3
import com.banyawa.sitescanner.core.geometry.Vec3
import com.banyawa.sitescanner.core.util.IntList
import kotlin.math.sqrt

/**
 * Indexed triangle mesh with a colour per vertex. Coordinates are ARCore world metres
 * (Y up) unless a caller converts them.
 *
 * @param positions x, y, z per vertex
 * @param colors r, g, b per vertex
 * @param indices three vertex indices per triangle, counter-clockwise seen from outside
 */
class TriangleMesh(val positions: FloatArray, val colors: ByteArray, val indices: IntArray) {
    init {
        require(positions.size % 3 == 0 && colors.size == positions.size) { "one colour per vertex" }
        require(indices.size % 3 == 0) { "three indices per triangle" }
    }

    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3

    fun isEmpty() = indices.isEmpty()

    /** Unit vertex normals, the area-weighted average of the surrounding faces. */
    fun normals(): FloatArray {
        val n = FloatArray(positions.size)
        for (t in 0 until triangleCount) {
            val a = indices[t * 3] * 3
            val b = indices[t * 3 + 1] * 3
            val c = indices[t * 3 + 2] * 3
            val e1x = positions[b] - positions[a]
            val e1y = positions[b + 1] - positions[a + 1]
            val e1z = positions[b + 2] - positions[a + 2]
            val e2x = positions[c] - positions[a]
            val e2y = positions[c + 1] - positions[a + 1]
            val e2z = positions[c + 2] - positions[a + 2]
            // The cross product's length is twice the area: bigger faces count more.
            val fx = e1y * e2z - e1z * e2y
            val fy = e1z * e2x - e1x * e2z
            val fz = e1x * e2y - e1y * e2x
            n[a] += fx; n[a + 1] += fy; n[a + 2] += fz
            n[b] += fx; n[b + 1] += fy; n[b + 2] += fz
            n[c] += fx; n[c + 1] += fy; n[c + 2] += fz
        }
        for (v in 0 until vertexCount) {
            val l = sqrt(n[v * 3] * n[v * 3] + n[v * 3 + 1] * n[v * 3 + 1] + n[v * 3 + 2] * n[v * 3 + 2])
            if (l > 0f) {
                n[v * 3] /= l
                n[v * 3 + 1] /= l
                n[v * 3 + 2] /= l
            }
        }
        return n
    }

    fun bounds(): Bounds3? {
        if (vertexCount == 0) return null
        val min = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        val max = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        for (i in positions.indices) {
            val a = i % 3
            if (positions[i] < min[a]) min[a] = positions[i]
            if (positions[i] > max[a]) max[a] = positions[i]
        }
        return Bounds3(Vec3(min[0], min[1], min[2]), Vec3(max[0], max[1], max[2]))
    }

    /**
     * Drops loose pieces (specks of noise floating in the room) with fewer than
     * [minTriangles] triangles or under [minFraction] of the largest piece, plus any
     * vertex no triangle uses.
     */
    fun withoutSmallParts(minTriangles: Int = 200, minFraction: Float = 0.01f): TriangleMesh {
        if (isEmpty()) return this
        val parent = IntArray(vertexCount) { it }
        fun find(v: Int): Int {
            var r = v
            while (parent[r] != r) r = parent[r]
            var x = v
            while (parent[x] != r) {
                val next = parent[x]
                parent[x] = r
                x = next
            }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
        for (t in 0 until triangleCount) {
            union(indices[t * 3], indices[t * 3 + 1])
            union(indices[t * 3], indices[t * 3 + 2])
        }
        val trianglesInPart = IntArray(vertexCount)
        for (t in 0 until triangleCount) trianglesInPart[find(indices[t * 3])]++
        val largest = trianglesInPart.max()
        val keepAt = maxOf(minOf(minTriangles, largest), (largest * minFraction).toInt())
        val kept = IntList(triangleCount)
        for (t in 0 until triangleCount) if (trianglesInPart[find(indices[t * 3])] >= keepAt) kept.add(t)
        return subset(kept.toArray())
    }

    /** The triangles [triangles] (by index) and only the vertices they use. */
    private fun subset(triangles: IntArray): TriangleMesh {
        val remap = IntArray(vertexCount) { -1 }
        var next = 0
        val newIndices = IntArray(triangles.size * 3)
        for ((k, t) in triangles.withIndex()) {
            for (j in 0 until 3) {
                val v = indices[t * 3 + j]
                if (remap[v] < 0) remap[v] = next++
                newIndices[k * 3 + j] = remap[v]
            }
        }
        val newPositions = FloatArray(next * 3)
        val newColors = ByteArray(next * 3)
        for (v in 0 until vertexCount) {
            val w = remap[v]
            if (w < 0) continue
            positions.copyInto(newPositions, w * 3, v * 3, v * 3 + 3)
            colors.copyInto(newColors, w * 3, v * 3, v * 3 + 3)
        }
        return TriangleMesh(newPositions, newColors, newIndices)
    }

    /**
     * Taubin smoothing: [passes] rounds of a shrinking Laplacian step ([lambda]) each followed
     * by an inflating one ([mu]), which irons out depth noise without shrinking the shape the
     * way plain Laplacian smoothing does. Triangles, colours and lone vertices stay as they are.
     */
    fun smoothed(passes: Int = 6, lambda: Float = 0.5f, mu: Float = -0.53f): TriangleMesh {
        if (passes <= 0 || isEmpty()) return this
        // Neighbour lists in CSR form: every triangle edge, both ways, duplicates removed.
        val count = IntArray(vertexCount)
        for (t in 0 until triangleCount) for (j in 0 until 3) {
            count[indices[t * 3 + j]] += 2
        }
        val start = IntArray(vertexCount + 1)
        for (v in 0 until vertexCount) start[v + 1] = start[v] + count[v]
        val fill = start.copyOf()
        val neighbours = IntArray(start[vertexCount])
        for (t in 0 until triangleCount) {
            val a = indices[t * 3]
            val b = indices[t * 3 + 1]
            val c = indices[t * 3 + 2]
            neighbours[fill[a]++] = b; neighbours[fill[a]++] = c
            neighbours[fill[b]++] = a; neighbours[fill[b]++] = c
            neighbours[fill[c]++] = a; neighbours[fill[c]++] = b
        }
        val degree = IntArray(vertexCount)
        for (v in 0 until vertexCount) {
            java.util.Arrays.sort(neighbours, start[v], start[v + 1])
            var n = 0
            var last = -1
            for (k in start[v] until start[v + 1]) {
                if (neighbours[k] == last) continue
                last = neighbours[k]
                neighbours[start[v] + n++] = last
            }
            degree[v] = n
        }

        var src = positions.copyOf()
        var dst = FloatArray(positions.size)
        fun step(factor: Float) {
            for (v in 0 until vertexCount) {
                val n = degree[v]
                if (n == 0) {
                    dst[v * 3] = src[v * 3]; dst[v * 3 + 1] = src[v * 3 + 1]; dst[v * 3 + 2] = src[v * 3 + 2]
                    continue
                }
                var sx = 0f
                var sy = 0f
                var sz = 0f
                for (k in start[v] until start[v] + n) {
                    val u = neighbours[k] * 3
                    sx += src[u]; sy += src[u + 1]; sz += src[u + 2]
                }
                dst[v * 3] = src[v * 3] + factor * (sx / n - src[v * 3])
                dst[v * 3 + 1] = src[v * 3 + 1] + factor * (sy / n - src[v * 3 + 1])
                dst[v * 3 + 2] = src[v * 3 + 2] + factor * (sz / n - src[v * 3 + 2])
            }
            val swap = src; src = dst; dst = swap
        }
        repeat(passes) {
            step(lambda)
            step(mu)
        }
        return TriangleMesh(src, colors, indices)
    }

    /** The same mesh with every vertex moved by [transform], which writes x, y, z into `out`. */
    fun mapPositions(transform: (x: Float, y: Float, z: Float, out: FloatArray, offset: Int) -> Unit): TriangleMesh {
        val out = FloatArray(positions.size)
        for (v in 0 until vertexCount) transform(positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2], out, v * 3)
        return TriangleMesh(out, colors, indices)
    }

    companion object {
        val EMPTY = TriangleMesh(FloatArray(0), ByteArray(0), IntArray(0))
    }
}
