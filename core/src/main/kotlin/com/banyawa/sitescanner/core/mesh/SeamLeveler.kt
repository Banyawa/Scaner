package com.banyawa.sitescanner.core.mesh

import com.banyawa.sitescanner.core.pointcloud.RgbImage
import com.banyawa.sitescanner.core.util.IntList
import com.banyawa.sitescanner.core.util.LongIntMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class SeamLevelingOptions(
    /** Weight of a vertex's correction staying close to its chart neighbours', against matching its seam (weight 1). */
    val smoothness: Float = 0.05f,
    /** Gauss–Seidel sweeps over the vertices: a seam's local correction spreads about this many rings into its charts. */
    val iterations: Int = 40,
    /** Largest correction per channel (0..255 levels), so a badly matched seam cannot paint a blotch. */
    val maxCorrection: Int = 96,
    /** Pixels the corrections extend beyond the triangles, over the charts' gutters (which the texture filtering reads). */
    val gutter: Int = 4,
    /** A seam difference this far (levels) from its charts' overall difference counts half: misaligned detail, not exposure. */
    val outlierLevels: Float = 20f,
)

/** What a leveling did, for logs and tests. */
class SeamLevelingStats(
    /** Photo charts (connected groups of textured triangles). */
    val charts: Int,
    /** Vertices lying on a seam between two photo charts (counted once per chart). */
    val seamVertices: Int,
    /** Pairs of such vertices at the same place, in different charts. */
    val seamPairs: Int,
    /** Mean absolute colour difference per channel between the two sides of the seams, before and after. */
    val seamDifferenceBefore: Float,
    val seamDifferenceAfter: Float,
    /** Largest correction applied to a vertex, in levels. */
    val maxCorrection: Int,
    val millis: Long,
)

/**
 * Evens out the exposure and colour differences between the photo charts of a
 * [TexturedMesh]'s atlas, so the seams between charts stop showing.
 *
 * Each chart is cut from one photo, so along a seam the same surface has two colours: one
 * per photo's exposure and white balance. The leveler samples the atlas on both sides of
 * every seam vertex, solves for a smooth colour correction per vertex (constant deep inside
 * a chart, varying near its seams) that makes the two sides agree, and adds it to the atlas
 * texel by texel, gutters included. Solid-colour patches (triangles no photo saw) are left
 * alone, and so is every chart without a seam.
 */
class SeamLeveler(private val options: SeamLevelingOptions = SeamLevelingOptions()) {

    /** Levels [textured]'s atlas in place (its pixels change; the mesh and uvs do not). */
    fun level(textured: TexturedMesh): SeamLevelingStats {
        val start = System.nanoTime()
        val mesh = textured.mesh
        val atlas = textured.atlas
        val n = mesh.vertexCount
        val triangles = mesh.triangleCount
        val idx = mesh.indices
        val px = FloatArray(n) { textured.uv[it * 2] * atlas.width }
        val py = FloatArray(n) { textured.uv[it * 2 + 1] * atlas.height }

        // A photo triangle has an area in the atlas; a solid patch maps all three corners to one point.
        val photo = BooleanArray(triangles)
        val photoVertex = BooleanArray(n)
        for (t in 0 until triangles) {
            val a = idx[t * 3]
            val b = idx[t * 3 + 1]
            val c = idx[t * 3 + 2]
            if (px[a] == px[b] && px[a] == px[c] && py[a] == py[b] && py[a] == py[c]) continue
            photo[t] = true
            photoVertex[a] = true; photoVertex[b] = true; photoVertex[c] = true
        }
        val charts = Charts.of(mesh, photo, photoVertex)
        val seams = Seams.find(mesh, photoVertex, charts.chartOf)
        val pairs = seams.count
        if (pairs == 0) return SeamLevelingStats(charts.count, 0, 0, 0f, 0f, 0, (System.nanoTime() - start) / 1_000_000)

        val edges = SeamEdges.find(mesh, photo, seams, charts.chartOf)
        if (edges.count == 0) return SeamLevelingStats(charts.count, 0, 0, 0f, 0f, 0, (System.nanoTime() - start) / 1_000_000)
        // Per pair, g_a − g_b = f_b − f_a: what the corrections must differ by for the sides to agree.
        val d = FloatArray(pairs * 3)
        val weight = FloatArray(pairs)
        measure(edges, seams, px, py, atlas, d, weight)
        val before = meanAbs(d, weight)
        val measured = weight.count { it > 0f }
        val onSeam = BooleanArray(n)
        for (p in 0 until pairs) if (weight[p] > 0f) { onSeam[seams.a[p]] = true; onSeam[seams.b[p]] = true }

        val offset = solveCharts(charts, seams, d, weight)
        val g = solveVertices(mesh, photo, photoVertex, charts, offset, seams, d, weight)
        var largest = 0f
        for (v in 0 until n) if (photoVertex[v]) for (ch in 0 until 3) largest = max(largest, abs(g[v * 3 + ch]))
        apply(mesh, photo, charts, px, py, g, atlas)

        val after = FloatArray(pairs * 3)
        val afterWeight = FloatArray(pairs)
        measure(edges, seams, px, py, atlas, after, afterWeight)
        return SeamLevelingStats(
            charts = charts.count,
            seamVertices = onSeam.count { it },
            seamPairs = measured,
            seamDifferenceBefore = before,
            seamDifferenceAfter = meanAbs(after, afterWeight),
            maxCorrection = largest.roundToInt(),
            millis = (System.nanoTime() - start) / 1_000_000,
        )
    }

    // ---------------------------------------------------------------- structure

    /** Connected groups of photo triangles: [chartOf] per vertex (−1 off the photos), [vertices] per chart. */
    private class Charts(val chartOf: IntArray, val count: Int, val vertices: IntArray) {
        companion object {
            fun of(mesh: TriangleMesh, photo: BooleanArray, photoVertex: BooleanArray): Charts {
                val n = mesh.vertexCount
                val idx = mesh.indices
                val parent = IntArray(n) { it }
                fun find(v: Int): Int {
                    var x = v
                    while (parent[x] != x) {
                        parent[x] = parent[parent[x]]
                        x = parent[x]
                    }
                    return x
                }
                fun union(a: Int, b: Int) {
                    val ra = find(a)
                    val rb = find(b)
                    if (ra != rb) parent[ra] = rb
                }
                for (t in 0 until mesh.triangleCount) {
                    if (!photo[t]) continue
                    union(idx[t * 3], idx[t * 3 + 1])
                    union(idx[t * 3], idx[t * 3 + 2])
                }
                val chartOf = IntArray(n) { -1 }
                val id = IntArray(n) { -1 }
                val sizes = IntArray(n)
                var count = 0
                for (v in 0 until n) {
                    if (!photoVertex[v]) continue
                    val r = find(v)
                    if (id[r] < 0) id[r] = count++
                    chartOf[v] = id[r]
                    sizes[id[r]]++
                }
                return Charts(chartOf, count, sizes.copyOf(count))
            }
        }
    }

    /**
     * Vertex pairs ([a], [b]) at the same position in different charts; [place] numbers
     * the positions (vertices at the same one share it; −1 off the photos).
     */
    private class Seams(val a: IntArray, val b: IntArray, val place: IntArray) {
        val count get() = a.size

        private val index = LongIntMap(a.size * 2 + 16).also { map ->
            for (p in a.indices) map[key(a[p], b[p])] = p
        }

        /** The pair joining [u] and [v], or −1. */
        fun pairOf(u: Int, v: Int): Int = index[key(u, v)]

        companion object {
            private fun key(u: Int, v: Int): Long = if (u < v) (u.toLong() shl 32) or v.toLong() else (v.toLong() shl 32) or u.toLong()

            fun find(mesh: TriangleMesh, photoVertex: BooleanArray, chartOf: IntArray): Seams {
                val n = mesh.vertexCount
                val pos = mesh.positions
                // Vertices chained by the hash of their position; a chain is checked for equality.
                val heads = LongIntMap(n + n / 4 + 16)
                val next = IntArray(n) { -1 }
                val isHead = BooleanArray(n)
                for (v in 0 until n) {
                    if (!photoVertex[v]) continue
                    val key = key(pos, v)
                    val first = heads[key]
                    if (first >= 0) {
                        next[v] = first
                        isHead[first] = false
                    }
                    heads[key] = v
                    isHead[v] = true
                }
                val a = IntList()
                val b = IntList()
                val place = IntArray(n) { -1 }
                var places = 0
                for (v in 0 until n) {
                    if (!isHead[v]) continue
                    var i = v
                    while (i >= 0) {
                        if (place[i] < 0) {
                            place[i] = places++
                            var j = next[i]
                            while (j >= 0) {
                                if (place[j] < 0 && samePlace(pos, i, j)) {
                                    place[j] = place[i]
                                    if (chartOf[i] != chartOf[j]) {
                                        a.add(i)
                                        b.add(j)
                                    }
                                }
                                j = next[j]
                            }
                        }
                        i = next[i]
                    }
                }
                return Seams(a.toArray(), b.toArray(), place)
            }

            private fun key(pos: FloatArray, v: Int): Long {
                var k = pos[v * 3].toRawBits().toLong() * -0x61c8864680b583ebL
                k = (k xor pos[v * 3 + 1].toRawBits().toLong()) * -0x4b47d5c8a52d2e13L
                k = (k xor pos[v * 3 + 2].toRawBits().toLong()) * -0x61c8864680b583ebL
                return k xor (k ushr 29)
            }

            private fun samePlace(pos: FloatArray, i: Int, j: Int): Boolean =
                pos[i * 3] == pos[j * 3] && pos[i * 3 + 1] == pos[j * 3 + 1] && pos[i * 3 + 2] == pos[j * 3 + 2]
        }
    }

    // ---------------------------------------------------------------- colours

    /**
     * Edges along the seams, matched across them: edge ([aV], [aW]) of one chart lies on
     * the same two positions as ([bV], [bW]) of another, so the two show the same strip
     * of surface, once from each photo. [pairV] joins aV and bV, [pairW] aW and bW.
     */
    private class SeamEdges(val aV: IntArray, val aW: IntArray, val bV: IntArray, val bW: IntArray, val pairV: IntArray, val pairW: IntArray) {
        val count get() = aV.size

        companion object {
            fun find(mesh: TriangleMesh, photo: BooleanArray, seams: Seams, chartOf: IntArray): SeamEdges {
                val idx = mesh.indices
                val place = seams.place
                val paired = BooleanArray(mesh.vertexCount)
                for (p in 0 until seams.count) { paired[seams.a[p]] = true; paired[seams.b[p]] = true }
                // Every photo edge between two paired vertices, chained by its pair of places.
                val heads = LongIntMap(seams.count * 2 + 16)
                val edgeV = IntList()
                val edgeW = IntList()
                val next = IntList()
                val isHead = IntList()
                for (t in 0 until mesh.triangleCount) {
                    if (!photo[t]) continue
                    for (j in 0 until 3) {
                        val v = idx[t * 3 + j]
                        val w = idx[t * 3 + (j + 1) % 3]
                        if (!paired[v] || !paired[w] || place[v] == place[w]) continue
                        val lo = min(place[v], place[w])
                        val hi = max(place[v], place[w])
                        val key = (lo.toLong() shl 32) or hi.toLong()
                        val e = edgeV.size
                        edgeV.add(v)
                        edgeW.add(w)
                        val first = heads[key]
                        next.add(first)
                        isHead.add(1)
                        if (first >= 0) isHead[first] = 0
                        heads[key] = e
                    }
                }
                val aV = IntList(); val aW = IntList(); val bV = IntList(); val bW = IntList()
                val pairV = IntList(); val pairW = IntList()
                for (e in 0 until edgeV.size) {
                    if (isHead[e] == 0) continue
                    var i = e
                    while (i >= 0) {
                        var j = next[i]
                        while (j >= 0) {
                            if (chartOf[edgeV[i]] != chartOf[edgeV[j]]) {
                                // Orient j's edge like i's.
                                val v = edgeV[i]
                                val w = edgeW[i]
                                val v2 = if (place[edgeV[j]] == place[v]) edgeV[j] else edgeW[j]
                                val w2 = if (v2 == edgeV[j]) edgeW[j] else edgeV[j]
                                val pv = seams.pairOf(v, v2)
                                val pw = seams.pairOf(w, w2)
                                if (pv >= 0 && pw >= 0) {
                                    aV.add(v); aW.add(w); bV.add(v2); bW.add(w2); pairV.add(pv); pairW.add(pw)
                                }
                            }
                            j = next[j]
                        }
                        i = next[i]
                    }
                }
                return SeamEdges(aV.toArray(), aW.toArray(), bV.toArray(), bW.toArray(), pairV.toArray(), pairW.toArray())
            }
        }
    }

    /**
     * Samples the atlas along both sides of every seam edge, at the same points of the
     * surface, and averages the differences (side b minus side a of each pair) into the
     * pairs at the edge's ends, each end weighted by its nearness: [d] per pair and
     * channel, [weight] 1 for a pair a seam edge reaches, else 0.
     */
    private fun measure(edges: SeamEdges, seams: Seams, px: FloatArray, py: FloatArray, atlas: RgbImage, d: FloatArray, weight: FloatArray) {
        val pairs = seams.count
        val sum = FloatArray(pairs * 3)
        val total = FloatArray(pairs)
        val fa = FloatArray(3)
        val fb = FloatArray(3)
        for (e in 0 until edges.count) {
            val av = edges.aV[e]; val aw = edges.aW[e]
            val bv = edges.bV[e]; val bw = edges.bW[e]
            val lenA = sqrt((px[aw] - px[av]) * (px[aw] - px[av]) + (py[aw] - py[av]) * (py[aw] - py[av]))
            val lenB = sqrt((px[bw] - px[bv]) * (px[bw] - px[bv]) + (py[bw] - py[bv]) * (py[bw] - py[bv]))
            val samples = (ceil(max(lenA, lenB) / SAMPLE_SPACING).toInt()).coerceIn(MIN_SAMPLES, MAX_SAMPLES)
            val pv = edges.pairV[e]
            val pw = edges.pairW[e]
            // Which side of each pair the a edge is on.
            val signV = if (seams.a[pv] == av) 1f else -1f
            val signW = if (seams.a[pw] == aw) 1f else -1f
            for (i in 0 until samples) {
                val t = (i + 0.5f) / samples
                sample(atlas, px[av] + (px[aw] - px[av]) * t, py[av] + (py[aw] - py[av]) * t, fa)
                sample(atlas, px[bv] + (px[bw] - px[bv]) * t, py[bv] + (py[bw] - py[bv]) * t, fb)
                for (ch in 0 until 3) {
                    val diff = fb[ch] - fa[ch]
                    sum[pv * 3 + ch] += (1f - t) * signV * diff
                    sum[pw * 3 + ch] += t * signW * diff
                }
                total[pv] += 1f - t
                total[pw] += t
            }
        }
        for (p in 0 until pairs) {
            if (total[p] <= 0f) {
                weight[p] = 0f
                continue
            }
            weight[p] = 1f
            for (ch in 0 until 3) d[p * 3 + ch] = sum[p * 3 + ch] / total[p]
        }
    }

    /** Bilinear sample at the continuous atlas coordinate ([x], [y]), pixel centres at half integers. */
    private fun sample(atlas: RgbImage, x: Float, y: Float, out: FloatArray) {
        val w = atlas.width
        val h = atlas.height
        val fx = x - 0.5f
        val fy = y - 0.5f
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        val xa = x0.coerceIn(0, w - 1)
        val xb = (x0 + 1).coerceIn(0, w - 1)
        val ya = y0.coerceIn(0, h - 1)
        val yb = (y0 + 1).coerceIn(0, h - 1)
        val p00 = atlas.pixels[ya * w + xa]
        val p10 = atlas.pixels[ya * w + xb]
        val p01 = atlas.pixels[yb * w + xa]
        val p11 = atlas.pixels[yb * w + xb]
        var shift = 16
        for (ch in 0 until 3) {
            val top = (p00 shr shift and 0xFF) + ((p10 shr shift and 0xFF) - (p00 shr shift and 0xFF)) * tx
            val bottom = (p01 shr shift and 0xFF) + ((p11 shr shift and 0xFF) - (p01 shr shift and 0xFF)) * tx
            out[ch] = top + (bottom - top) * ty
            shift -= 8
        }
    }

    private fun meanAbs(d: FloatArray, weight: FloatArray): Float {
        var sum = 0.0
        var count = 0
        for (p in weight.indices) {
            if (weight[p] <= 0f) continue
            for (ch in 0 until 3) sum += abs(d[p * 3 + ch])
            count += 3
        }
        return if (count == 0) 0f else (sum / count).toFloat()
    }

    // ---------------------------------------------------------------- solving

    /**
     * One constant correction per chart, the bulk of the fix: least squares over the seam
     * pairs' differences (each pair asks g_a − g_b = d), weakly anchored to zero in
     * proportion to the chart's size, so large charts keep their exposure and small ones
     * adapt. Solved twice: the second time with the pairs far off their charts' overall
     * difference down-weighted, as [weight] then records.
     */
    private fun solveCharts(charts: Charts, seams: Seams, d: FloatArray, weight: FloatArray): FloatArray {
        val pairs = seams.count
        val chartOf = charts.chartOf
        // Pairs grouped by the two charts they join (lo < hi); differences oriented as g_lo − g_hi.
        val classMap = LongIntMap(pairs + 16)
        val lo = IntList()
        val hi = IntList()
        val classOf = IntArray(pairs)
        for (p in 0 until pairs) {
            val ca = chartOf[seams.a[p]]
            val cb = chartOf[seams.b[p]]
            val l = min(ca, cb)
            val h = max(ca, cb)
            val key = (l.toLong() shl 32) or h.toLong()
            var c = classMap[key]
            if (c < 0) {
                c = lo.size
                classMap[key] = c
                lo.add(l)
                hi.add(h)
            }
            classOf[p] = c
        }
        val classes = lo.size
        val sumW = FloatArray(classes)
        val sumD = FloatArray(classes * 3)
        fun accumulate() {
            sumW.fill(0f)
            sumD.fill(0f)
            for (p in 0 until pairs) {
                val c = classOf[p]
                val w = weight[p]
                val sign = if (chartOf[seams.a[p]] == lo[c]) 1f else -1f
                sumW[c] += w
                for (ch in 0 until 3) sumD[c * 3 + ch] += w * sign * d[p * 3 + ch]
            }
        }
        // Classes per chart, CSR.
        val count = charts.count
        val degree = IntArray(count)
        for (c in 0 until classes) { degree[lo[c]]++; degree[hi[c]]++ }
        val start = IntArray(count + 1)
        for (k in 0 until count) start[k + 1] = start[k] + degree[k]
        val fill = start.copyOf()
        val classAt = IntArray(start[count])
        for (c in 0 until classes) { classAt[fill[lo[c]]++] = c; classAt[fill[hi[c]]++] = c }
        val anchor = FloatArray(count) { max(CHART_ANCHOR * charts.vertices[it], MIN_ANCHOR) }

        // Charts joined by seams form components; a shift common to a component costs the
        // seams nothing, so Gauss–Seidel drifts along it: it is set afterwards to where the
        // anchors want it (their weighted mean at zero).
        val component = IntArray(count) { it }
        fun root(k: Int): Int {
            var x = k
            while (component[x] != x) {
                component[x] = component[component[x]]
                x = component[x]
            }
            return x
        }
        for (c in 0 until classes) {
            val a = root(lo[c])
            val b = root(hi[c])
            if (a != b) component[a] = b
        }
        val anchorSum = FloatArray(count)
        for (k in 0 until count) anchorSum[root(k)] += anchor[k]
        val shift = FloatArray(count * 3)

        val offset = FloatArray(count * 3)
        fun centre() {
            shift.fill(0f)
            for (k in 0 until count) for (ch in 0 until 3) shift[root(k) * 3 + ch] += anchor[k] * offset[k * 3 + ch]
            for (k in 0 until count) {
                val r = root(k)
                for (ch in 0 until 3) offset[k * 3 + ch] -= shift[r * 3 + ch] / anchorSum[r]
            }
        }
        fun sweep(): Float {
            var largest = 0f
            for (k in 0 until count) {
                if (degree[k] == 0) continue
                for (ch in 0 until 3) {
                    var num = 0f
                    var den = anchor[k]
                    for (i in start[k] until start[k + 1]) {
                        val c = classAt[i]
                        val other = if (lo[c] == k) hi[c] else lo[c]
                        val sign = if (lo[c] == k) 1f else -1f
                        num += sumW[c] * offset[other * 3 + ch] + sign * sumD[c * 3 + ch]
                        den += sumW[c]
                    }
                    val next = num / den
                    largest = max(largest, abs(next - offset[k * 3 + ch]))
                    offset[k * 3 + ch] = next
                }
            }
            return largest
        }
        fun solve() {
            accumulate()
            for (i in 0 until MAX_CHART_SWEEPS) if (sweep() < CHART_TOLERANCE) break
            centre()
        }
        solve()
        val sigma = options.outlierLevels
        for (p in 0 until pairs) {
            val ca = chartOf[seams.a[p]]
            val cb = chartOf[seams.b[p]]
            var residual = 0f
            for (ch in 0 until 3) residual = max(residual, abs(d[p * 3 + ch] - (offset[ca * 3 + ch] - offset[cb * 3 + ch])))
            val r = residual / sigma
            if (weight[p] > 0f) weight[p] = if (r > OUTLIER_CUTOFF) 0f else 1f / (1f + r * r)
        }
        solve()
        return offset
    }

    /**
     * The correction per vertex: its chart's [offset] far from any seam, bending near the
     * seams to match the other side exactly there. Gauss–Seidel from the chart offsets;
     * each sweep carries a seam's influence one ring further.
     */
    private fun solveVertices(
        mesh: TriangleMesh,
        photo: BooleanArray,
        photoVertex: BooleanArray,
        charts: Charts,
        offset: FloatArray,
        seams: Seams,
        d: FloatArray,
        weight: FloatArray,
    ): FloatArray {
        val n = mesh.vertexCount
        val idx = mesh.indices
        val chartOf = charts.chartOf
        val pairs = seams.count
        val active = BooleanArray(charts.count)
        for (p in 0 until pairs) { active[chartOf[seams.a[p]]] = true; active[chartOf[seams.b[p]]] = true }

        // Neighbours within the chart, CSR, duplicates removed (as TriangleMesh.smoothed).
        val degree = IntArray(n)
        for (t in 0 until mesh.triangleCount) if (photo[t]) for (j in 0 until 3) degree[idx[t * 3 + j]] += 2
        val start = IntArray(n + 1)
        for (v in 0 until n) start[v + 1] = start[v] + degree[v]
        val fill = start.copyOf()
        val neighbours = IntArray(start[n])
        for (t in 0 until mesh.triangleCount) {
            if (!photo[t]) continue
            val a = idx[t * 3]
            val b = idx[t * 3 + 1]
            val c = idx[t * 3 + 2]
            neighbours[fill[a]++] = b; neighbours[fill[a]++] = c
            neighbours[fill[b]++] = a; neighbours[fill[b]++] = c
            neighbours[fill[c]++] = a; neighbours[fill[c]++] = b
        }
        for (v in 0 until n) {
            if (degree[v] == 0) continue
            java.util.Arrays.sort(neighbours, start[v], start[v + 1])
            var m = 0
            var last = -1
            for (k in start[v] until start[v + 1]) {
                if (neighbours[k] == last) continue
                last = neighbours[k]
                neighbours[start[v] + m++] = last
            }
            degree[v] = m
        }
        // Seam partners per vertex, CSR: the pair and which side of it the vertex is.
        val pairDegree = IntArray(n)
        for (p in 0 until pairs) { pairDegree[seams.a[p]]++; pairDegree[seams.b[p]]++ }
        val pairStart = IntArray(n + 1)
        for (v in 0 until n) pairStart[v + 1] = pairStart[v] + pairDegree[v]
        val pairFill = pairStart.copyOf()
        val pairAt = IntArray(pairStart[n])
        for (p in 0 until pairs) { pairAt[pairFill[seams.a[p]]++] = p; pairAt[pairFill[seams.b[p]]++] = p }

        val g = FloatArray(n * 3)
        for (v in 0 until n) {
            val k = chartOf[v]
            if (k < 0) continue
            for (ch in 0 until 3) g[v * 3 + ch] = offset[k * 3 + ch]
        }
        val lambda = options.smoothness
        repeat(options.iterations) {
            for (v in 0 until n) {
                val k = chartOf[v]
                if (k < 0 || !active[k]) continue
                for (ch in 0 until 3) {
                    var num = VERTEX_ANCHOR * offset[k * 3 + ch]
                    var den = VERTEX_ANCHOR
                    for (i in start[v] until start[v] + degree[v]) num += lambda * g[neighbours[i] * 3 + ch]
                    den += lambda * degree[v]
                    for (i in pairStart[v] until pairStart[v + 1]) {
                        val p = pairAt[i]
                        val w = weight[p]
                        if (seams.a[p] == v) {
                            num += w * (g[seams.b[p] * 3 + ch] + d[p * 3 + ch])
                        } else {
                            num += w * (g[seams.a[p] * 3 + ch] - d[p * 3 + ch])
                        }
                        den += w
                    }
                    g[v * 3 + ch] = num / den
                }
            }
        }
        // A vertex strays from its chart's offset only so far: a seam that disagrees locally
        // because the photos do not line up must not paint a blotch.
        val limit = options.maxCorrection.toFloat()
        for (v in 0 until n) {
            val k = chartOf[v]
            if (k < 0) continue
            for (ch in 0 until 3) {
                val c = offset[k * 3 + ch]
                g[v * 3 + ch] = g[v * 3 + ch].coerceIn(c - LOCAL_LIMIT, c + LOCAL_LIMIT).coerceIn(-limit, limit)
            }
        }
        return g
    }

    // ---------------------------------------------------------------- painting

    /**
     * Adds the corrections to the atlas: every texel under a photo triangle gets its
     * corners' interpolated, once (a done mask keeps shared edges from being counted
     * twice), then the texels within [SeamLevelingOptions.gutter] of a triangle get its
     * nearest edge's.
     */
    private fun apply(mesh: TriangleMesh, photo: BooleanArray, charts: Charts, px: FloatArray, py: FloatArray, g: FloatArray, atlas: RgbImage) {
        val w = atlas.width
        val h = atlas.height
        val idx = mesh.indices
        val chartOf = charts.chartOf
        val moved = BooleanArray(charts.count)
        for (v in 0 until mesh.vertexCount) {
            val k = chartOf[v]
            if (k < 0 || moved[k]) continue
            if (g[v * 3] != 0f || g[v * 3 + 1] != 0f || g[v * 3 + 2] != 0f) moved[k] = true
        }
        val done = LongArray(((w.toLong() * h + 63) shr 6).toInt())
        val pixels = atlas.pixels
        val corner = FloatArray(3)
        for (pass in 0..1) {
            val expand = if (pass == 0) 0f else options.gutter.toFloat()
            for (t in 0 until mesh.triangleCount) {
                if (!photo[t]) continue
                val a = idx[t * 3]
                val b = idx[t * 3 + 1]
                val c = idx[t * 3 + 2]
                if (!moved[chartOf[a]]) continue
                val x0 = px[a]; val y0 = py[a]
                val x1 = px[b]; val y1 = py[b]
                val x2 = px[c]; val y2 = py[c]
                val minX = max(0, floor(min(x0, min(x1, x2)) - expand - 0.5f).toInt())
                val maxX = min(w - 1, ceil(max(x0, max(x1, x2)) + expand - 0.5f).toInt())
                val minY = max(0, floor(min(y0, min(y1, y2)) - expand - 0.5f).toInt())
                val maxY = min(h - 1, ceil(max(y0, max(y1, y2)) + expand - 0.5f).toInt())
                if (minX > maxX || minY > maxY) continue
                val area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
                val flat = abs(area) < 1e-6f
                val reach2 = expand * expand
                for (j in minY..maxY) {
                    val cy = j + 0.5f
                    for (i in minX..maxX) {
                        val p = j * w + i
                        if (done[p shr 6] and (1L shl (p and 63)) != 0L) continue
                        val cx = i + 0.5f
                        var w0 = 0f
                        var w1 = 0f
                        var w2 = 0f
                        var hit = false
                        if (!flat) {
                            val l0 = ((x1 - cx) * (y2 - cy) - (x2 - cx) * (y1 - cy)) / area
                            val l1 = ((x2 - cx) * (y0 - cy) - (x0 - cx) * (y2 - cy)) / area
                            val l2 = 1f - l0 - l1
                            if (l0 >= -INSIDE_EPS && l1 >= -INSIDE_EPS && l2 >= -INSIDE_EPS) {
                                hit = true
                                w0 = l0.coerceIn(0f, 1f); w1 = l1.coerceIn(0f, 1f); w2 = l2.coerceIn(0f, 1f)
                            }
                        }
                        if (!hit && expand > 0f) {
                            // The nearest point on the triangle's edges, if within reach.
                            var best = reach2
                            for (e in 0 until 3) {
                                val sx: Float; val sy: Float; val ex: Float; val ey: Float
                                when (e) {
                                    0 -> { sx = x0; sy = y0; ex = x1; ey = y1 }
                                    1 -> { sx = x1; sy = y1; ex = x2; ey = y2 }
                                    else -> { sx = x2; sy = y2; ex = x0; ey = y0 }
                                }
                                val dx = ex - sx
                                val dy = ey - sy
                                val len2 = dx * dx + dy * dy
                                val t1 = if (len2 < 1e-12f) 0f else (((cx - sx) * dx + (cy - sy) * dy) / len2).coerceIn(0f, 1f)
                                val qx = sx + dx * t1 - cx
                                val qy = sy + dy * t1 - cy
                                val dist2 = qx * qx + qy * qy
                                if (dist2 <= best) {
                                    best = dist2
                                    hit = true
                                    w0 = 0f; w1 = 0f; w2 = 0f
                                    when (e) {
                                        0 -> { w0 = 1f - t1; w1 = t1 }
                                        1 -> { w1 = 1f - t1; w2 = t1 }
                                        else -> { w2 = 1f - t1; w0 = t1 }
                                    }
                                }
                            }
                        }
                        if (!hit) continue
                        for (ch in 0 until 3) corner[ch] = w0 * g[a * 3 + ch] + w1 * g[b * 3 + ch] + w2 * g[c * 3 + ch]
                        val pix = pixels[p]
                        val r = ((pix shr 16 and 0xFF) + corner[0]).roundToInt().coerceIn(0, 255)
                        val gr = ((pix shr 8 and 0xFF) + corner[1]).roundToInt().coerceIn(0, 255)
                        val bl = ((pix and 0xFF) + corner[2]).roundToInt().coerceIn(0, 255)
                        pixels[p] = (pix and 0xFF000000.toInt()) or (r shl 16) or (gr shl 8) or bl
                        done[p shr 6] = done[p shr 6] or (1L shl (p and 63))
                    }
                }
            }
        }
    }

    private companion object {
        /** Texels between the samples along a seam edge, and samples per edge at least and at most. */
        const val SAMPLE_SPACING = 2f
        const val MIN_SAMPLES = 2
        const val MAX_SAMPLES = 16

        /** A chart's pull toward no correction, per vertex, against a seam pair's weight of 1. */
        const val CHART_ANCHOR = 1e-4f
        const val MIN_ANCHOR = 1e-6f
        const val MAX_CHART_SWEEPS = 2000
        const val CHART_TOLERANCE = 0.01f

        /** A vertex's pull toward its chart's offset: a seam's local correction fades over ~sqrt(6 · smoothness / this) rings. */
        const val VERTEX_ANCHOR = 0.003f

        /** Levels a vertex's correction may differ from its chart's offset. */
        const val LOCAL_LIMIT = 24f

        /** A pair whose difference is this many [SeamLevelingOptions.outlierLevels] off its charts' is ignored. */
        const val OUTLIER_CUTOFF = 3f

        const val INSIDE_EPS = 1e-4f
    }
}
