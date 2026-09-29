package com.banyawa.sitescanner.core.mesh

import com.banyawa.sitescanner.core.capture.Capture
import com.banyawa.sitescanner.core.capture.CaptureFrame
import com.banyawa.sitescanner.core.capture.ImageDecoder
import com.banyawa.sitescanner.core.capture.ProgressListener
import com.banyawa.sitescanner.core.pointcloud.CameraIntrinsics
import com.banyawa.sitescanner.core.pointcloud.ColorImage
import com.banyawa.sitescanner.core.pointcloud.KeyframeSelector
import com.banyawa.sitescanner.core.pointcloud.RgbImage
import com.banyawa.sitescanner.core.util.IntList
import com.banyawa.sitescanner.core.util.LongIntMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class TexturingOptions(
    /**
     * Side of the square texture atlas in pixels, at most: a smaller power of two is used
     * when everything fits at full resolution. 2048 on low-memory phones (a 4096² atlas
     * is 64 MB of pixels).
     */
    val atlasSize: Int = 4096,
    /** Frames considered, spread over the walk by position and view direction. */
    val maxFrames: Int = 80,
    /** Width of the per-frame visibility depth buffer in pixels; the height follows the image's aspect. */
    val depthTestSize: Int = 160,
    /**
     * Views meeting a face more obliquely than this (cosine of the angle between the face
     * normal and the direction to the camera) are not used: grazing views smear the photo.
     */
    val minViewCos: Float = 0.35f,
    /** Pixels of gutter around each chart in the atlas, so filtering and mipmaps do not bleed across charts. */
    val padding: Int = 4,
    /** JPEG quality for callers encoding the atlas (the GLB export). */
    val quality: Int = 85,
)

/** What a texturing run did, for logs and tests. */
class TexturingStats(
    val framesConsidered: Int,
    /** Frames whose image went into the atlas. */
    val framesUsed: Int,
    val charts: Int,
    /** Triangles textured from a photo. */
    val texturedTriangles: Int,
    /** Triangles no frame saw, given a solid patch of their vertex colour. */
    val fallbackTriangles: Int,
    val atlasSize: Int,
    /** Resolution of the photo crops in the atlas relative to the camera images (1 = full). */
    val scale: Float,
    /** Per output triangle, the atlas rectangle (index into [atlasRects] / 4) its texture comes from; -1 for the solid patches. */
    val triangleRect: IntArray,
    /** Atlas rectangles copied from the photos: x, y, width, height in atlas pixels each, gutter included. */
    val atlasRects: IntArray,
    val millis: Long,
)

class TexturingResult(val textured: TexturedMesh, val stats: TexturingStats)

/**
 * Textures a reconstructed mesh with the capture's photos: each patch of surface gets the
 * one photo that sees it best, cropped into a texture atlas. The mesh's vertex colours are
 * a blur at the voxel size (2 cm); the photos carry what they miss (edges, writing,
 * wood grain, sockets) at the camera's resolution.
 *
 * The standard "one best view per chart" approach:
 * 1. a spread of keyframes is picked over the walk, preferring sharp ones;
 * 2. per keyframe, a small depth buffer of the mesh tells which triangles it sees unoccluded,
 *    and each (triangle, frame) pair is scored by its projected size and obliqueness;
 * 3. every triangle takes its best frame, then neighbours are nudged onto the same frame
 *    where that costs little quality, so the surface falls into large single-photo charts
 *    with few seams;
 * 4. each chart's bounding box in its photo is copied into the atlas (shelf-packed, scaled
 *    down as a whole if it does not fit), and its vertices take their photo position in the
 *    atlas as texture coordinates.
 *
 * The output mesh has the input's triangles in the same order; vertices on chart borders
 * are duplicated so each has one texture coordinate, keeping their colour. Triangles no
 * frame sees keep their vertex colour through small solid patches in the atlas.
 */
class MeshTexturer(private val decoder: ImageDecoder, private val options: TexturingOptions = TexturingOptions()) {

    /**
     * Null when no frame can texture anything (no images, nothing visible) or when cancelled.
     * [progress] hears stage "texture": the frames' visibility, then filling the atlas.
     */
    fun texture(
        mesh: TriangleMesh,
        capture: Capture,
        progress: ProgressListener? = null,
        isCancelled: () -> Boolean = { false },
    ): TexturedMesh? = textureWithStats(mesh, capture, progress, isCancelled)?.textured

    /** As [texture], with what the run did. */
    fun textureWithStats(
        mesh: TriangleMesh,
        capture: Capture,
        progress: ProgressListener? = null,
        isCancelled: () -> Boolean = { false },
    ): TexturingResult? {
        val start = System.nanoTime()
        if (mesh.isEmpty()) return null
        val views = selectViews(capture.frames, mesh)
        if (views.isEmpty()) return null
        val triangles = mesh.triangleCount
        val total = views.size * 2

        // Best few views per triangle.
        val candFrame = IntArray(triangles * CANDIDATES) { -1 }
        val candScore = FloatArray(triangles * CANDIDATES)
        val visibility = Visibility(mesh, options)
        for ((i, view) in views.withIndex()) {
            if (isCancelled()) return null
            progress?.onProgress(STAGE, i, total)
            visibility.score(view, i, candFrame, candScore)
        }
        if ((0 until triangles).none { candFrame[it * CANDIDATES] >= 0 }) return null

        // One frame per triangle, in few large charts.
        val adjacency = adjacency(mesh)
        val label = smoothLabels(candFrame, candScore, adjacency, triangles)
        var charts = Charts.build(label, adjacency)
        if (absorbTinyCharts(charts, label, candFrame, candScore, adjacency)) charts = Charts.build(label, adjacency)

        // Photo rectangles and their place in the atlas.
        val rects = Rects.of(mesh, charts, views, options.padding)
        val droppedRect = BooleanArray(rects.count)
        val layout = layOut(mesh, charts, rects, droppedRect, isCancelled) ?: return null

        val atlas = fillAtlas(capture, views, rects, droppedRect, layout, progress, total, isCancelled) ?: return null
        val result = buildMesh(mesh, charts, views, rects, droppedRect, layout, atlas, start)
        progress?.onProgress(STAGE, total, total)
        return result
    }

    // ---------------------------------------------------------------- keyframes

    /**
     * Up to [TexturingOptions.maxFrames] frames that between them see as much of [mesh] as
     * closely as possible ([Coverage]), each weighted by how still the camera was, since a
     * fast turn smears the photo.
     */
    private fun selectViews(frames: List<CaptureFrame>, mesh: TriangleMesh): List<View> {
        val usable = frames.filter { f ->
            val k = f.imageIntrinsics
            f.cameraToWorld.size == 16 && f.cameraToWorld.all { it.isFinite() } && k.fx > 0f && k.fy > 0f && k.width > 0 && k.height > 0
        }
        val n = usable.size
        if (n == 0) return emptyList()
        val poses = Array(n) { usable[it].pose() }
        val sharpness = FloatArray(n) { i ->
            val a = max(0, i - 1)
            val b = min(n - 1, i + 1)
            val dt = (usable[b].timestampNs - usable[a].timestampNs) * 1e-9f
            if (dt <= 0f) {
                1f
            } else {
                val turn = Math.toRadians(KeyframeSelector.rotationDegBetween(poses[a], poses[b]).toDouble()).toFloat()
                val move = KeyframeSelector.translationBetween(poses[a], poses[b])
                1f / (1f + (turn + move / BLUR_DISTANCE_M) / dt / BLUR_SPEED)
            }
        }
        val all = List(n) { View(usable[it], usable[it].imageIntrinsics.toCore(), poses[it], sharpness[it]) }
        val maxFrames = max(1, options.maxFrames)
        if (n <= maxFrames) return all
        // Very long walks are thinned evenly first: the choice costs a byte per point and frame.
        val candidates = if (n <= MAX_CANDIDATES) all else List(MAX_CANDIDATES) { all[(it.toLong() * n / MAX_CANDIDATES).toInt()] }
        return Coverage(mesh, options).select(candidates, maxFrames).map { candidates[it] }
    }

    // ---------------------------------------------------------------- charts

    /**
     * Each triangle's frame: its best view, then a few rounds of trading a little view
     * quality for agreeing with its neighbours (iterated conditional modes on a
     * quality-plus-seams cost), which turns per-triangle noise into large charts.
     */
    private fun smoothLabels(candFrame: IntArray, candScore: FloatArray, adjacency: IntArray, triangles: Int): IntArray {
        val label = IntArray(triangles) { candFrame[it * CANDIDATES] }
        repeat(SMOOTHING_PASSES) {
            var changed = 0
            for (t in 0 until triangles) {
                val base = t * CANDIDATES
                if (candFrame[base] < 0) continue
                val best = candScore[base]
                var chosen = label[t]
                var chosenCost = Float.MAX_VALUE
                for (k in 0 until CANDIDATES) {
                    val f = candFrame[base + k]
                    if (f < 0) break
                    var cost = 1f - candScore[base + k] / best
                    for (j in 0 until 3) {
                        val nb = adjacency[t * 3 + j]
                        if (nb >= 0 && label[nb] != f) cost += SEAM_COST
                    }
                    if (cost < chosenCost) {
                        chosenCost = cost
                        chosen = f
                    }
                }
                if (chosen != label[t]) {
                    label[t] = chosen
                    changed++
                }
            }
            if (changed == 0) return label
        }
        return label
    }

    /** Moves the triangles of 1–3 triangle charts to a neighbour's frame where that frame sees them too. */
    private fun absorbTinyCharts(charts: Charts, label: IntArray, candFrame: IntArray, candScore: FloatArray, adjacency: IntArray): Boolean {
        var changed = false
        for (t in label.indices) {
            val c = charts.chartOf[t]
            if (c < 0 || charts.size[c] > TINY_CHART) continue
            var bestFrame = -1
            var bestScore = 0f
            for (j in 0 until 3) {
                val nb = adjacency[t * 3 + j]
                if (nb < 0) continue
                val f = label[nb]
                if (f < 0 || f == label[t]) continue
                for (k in 0 until CANDIDATES) {
                    if (candFrame[t * CANDIDATES + k] == f && candScore[t * CANDIDATES + k] > bestScore) {
                        bestScore = candScore[t * CANDIDATES + k]
                        bestFrame = f
                    }
                }
            }
            if (bestFrame >= 0) {
                label[t] = bestFrame
                changed = true
            }
        }
        return changed
    }

    /** The atlas layout; null when cancelled. */
    private fun layOut(mesh: TriangleMesh, charts: Charts, rects: Rects, dropped: BooleanArray, isCancelled: () -> Boolean): Layout? {
        while (true) {
            if (isCancelled()) return null
            Layout.fit(rects, dropped, Palette.of(mesh, charts, rects, dropped, max(MIN_ATLAS, options.atlasSize)), options)?.let { return it }
            // Not even thumbnails fit (a tiny atlas, a shredded surface): the smallest photo
            // rectangles give way to solid colour until the rest does.
            dropSmallest(rects, dropped)
        }
    }

    private fun dropSmallest(rects: Rects, dropped: BooleanArray) {
        val alive = (0 until rects.count).filter { !dropped[it] }.sortedBy { rects.triangles[it] }
        if (alive.isEmpty()) throw IllegalStateException("texture atlas too small for the fallback colours")
        for (i in 0 until max(1, alive.size / 2)) dropped[alive[i]] = true
    }

    // ---------------------------------------------------------------- atlas

    /** Copies every rectangle's photo crop into the atlas, decoding each frame's image once. */
    private fun fillAtlas(
        capture: Capture,
        views: List<View>,
        rects: Rects,
        dropped: BooleanArray,
        layout: Layout,
        progress: ProgressListener?,
        total: Int,
        isCancelled: () -> Boolean,
    ): RgbImage? {
        val size = layout.size
        val atlas = IntArray(size * size)
        atlas.fill(BACKGROUND)
        val pad = options.padding
        // Rectangles were made frame by frame, so each frame's are consecutive.
        val usedViews = IntList()
        for (r in 0 until rects.count) if (!dropped[r] && (usedViews.size == 0 || usedViews[usedViews.size - 1] != rects.view[r])) usedViews.add(rects.view[r])
        var r = 0
        for (u in 0 until usedViews.size) {
            if (isCancelled()) return null
            val vi = usedViews[u]
            val view = views[vi]
            val image = runCatching { decoder.decode(capture.imageBytes(view.frame)) }.getOrNull()
            val source = image?.let { Source.of(it) }
            while (r < rects.count && rects.view[r] != vi) r++
            while (r < rects.count && rects.view[r] == vi) {
                if (!dropped[r]) {
                    if (source != null) {
                        copyRect(source, view.k, rects, r, layout, atlas, pad)
                    } else {
                        fillSolid(atlas, size, layout.x[r], layout.y[r], layout.w[r], layout.h[r], rects.meanColor[r])
                    }
                }
                r++
            }
            progress?.onProgress(STAGE, views.size + (u + 1) * views.size / usedViews.size, total)
        }
        layout.palette.draw(atlas, size)
        return RgbImage(size, size, atlas)
    }

    /**
     * Resamples the photo rectangle [r] into its atlas place (bilinear), gutter included:
     * the gutter shows the photo around the chart rather than a smeared edge. Scaled down,
     * each atlas pixel averages a few samples over its footprint, so fine patterns (text,
     * brick) blur instead of turning into moiré.
     */
    private fun copyRect(src: Source, k: CameraIntrinsics, rects: Rects, r: Int, layout: Layout, atlas: IntArray, pad: Int) {
        val size = layout.size
        val s = layout.scale
        // Photo coordinates are laid out in the recorded intrinsics' pixels; the decoded image may differ in size.
        val sx = src.width.toFloat() / k.width
        val sy = src.height.toFloat() / k.height
        val ax = layout.x[r]
        val ay = layout.y[r]
        val x0 = rects.x0[r]
        val y0 = rects.y0[r]
        val taps = if (s >= 0.999f) 1 else min(MAX_TAPS, ceil(1f / s).toInt())
        val n = taps * taps
        for (j in 0 until layout.h[r]) {
            val row = (ay + j) * size + ax
            for (i in 0 until layout.w[r]) {
                if (taps == 1) {
                    // Atlas pixel centre → photo coordinate (pixel centres at integers) → decoded image.
                    atlas[row + i] = src.sample(((i + 0.5f - pad) / s + x0) * sx - 0.5f, ((j + 0.5f - pad) / s + y0) * sy - 0.5f)
                    continue
                }
                var red = 0
                var green = 0
                var blue = 0
                for (ty in 0 until taps) {
                    val v = ((j + (ty + 0.5f) / taps - pad) / s + y0) * sy - 0.5f
                    for (tx in 0 until taps) {
                        val c = src.sample(((i + (tx + 0.5f) / taps - pad) / s + x0) * sx - 0.5f, v)
                        red += c shr 16 and 0xFF
                        green += c shr 8 and 0xFF
                        blue += c and 0xFF
                    }
                }
                atlas[row + i] = ((red + n / 2) / n shl 16) or ((green + n / 2) / n shl 8) or ((blue + n / 2) / n)
            }
        }
    }

    // ---------------------------------------------------------------- output

    private fun buildMesh(
        mesh: TriangleMesh,
        charts: Charts,
        views: List<View>,
        rects: Rects,
        dropped: BooleanArray,
        layout: Layout,
        atlas: RgbImage,
        start: Long,
    ): TexturingResult {
        val triangles = mesh.triangleCount
        val palette = layout.palette
        // A vertex is shared by the triangles of one atlas rectangle (or one colour patch) only.
        val groupOf = IntArray(triangles)
        var textured = 0
        for (t in 0 until triangles) {
            val c = charts.chartOf[t]
            val r = if (c >= 0) rects.rectOf[c] else -1
            groupOf[t] = if (r >= 0 && !dropped[r]) {
                textured++
                r
            } else {
                rects.count + palette.cellOf(mesh, t)
            }
        }
        val map = LongIntMap(mesh.vertexCount + mesh.vertexCount / 4)
        val sourceVertex = IntList(mesh.vertexCount + mesh.vertexCount / 4)
        val vertexGroup = IntList(mesh.vertexCount + mesh.vertexCount / 4)
        val indices = IntArray(triangles * 3)
        for (t in 0 until triangles) {
            val g = groupOf[t]
            for (j in 0 until 3) {
                val v = mesh.indices[t * 3 + j]
                val key = (g.toLong() shl 32) or v.toLong()
                var o = map[key]
                if (o < 0) {
                    o = sourceVertex.size
                    map[key] = o
                    sourceVertex.add(v)
                    vertexGroup.add(g)
                }
                indices[t * 3 + j] = o
            }
        }
        val n = sourceVertex.size
        val positions = FloatArray(n * 3)
        val colors = ByteArray(n * 3)
        val uv = FloatArray(n * 2)
        val size = layout.size.toFloat()
        val pad = options.padding
        val projected = FloatArray(2)
        for (o in 0 until n) {
            val v = sourceVertex[o]
            mesh.positions.copyInto(positions, o * 3, v * 3, v * 3 + 3)
            mesh.colors.copyInto(colors, o * 3, v * 3, v * 3 + 3)
            val g = vertexGroup[o]
            if (g < rects.count) {
                views[rects.view[g]].project(mesh.positions, v, projected)
                val inner = layout.x[g] + pad
                val innerY = layout.y[g] + pad
                val x = (inner + (projected[0] - rects.x0[g] + 0.5f) * layout.scale).coerceIn(inner.toFloat(), (layout.x[g] + layout.w[g] - pad).toFloat())
                val y = (innerY + (projected[1] - rects.y0[g] + 0.5f) * layout.scale).coerceIn(innerY.toFloat(), (layout.y[g] + layout.h[g] - pad).toFloat())
                uv[o * 2] = x / size
                uv[o * 2 + 1] = y / size
            } else {
                val cell = g - rects.count
                uv[o * 2] = (palette.cellX(cell) + CELL / 2f) / size
                uv[o * 2 + 1] = (palette.cellY(cell) + CELL / 2f) / size
            }
        }

        // Stats: rectangles renumbered without the dropped ones.
        val renumber = IntArray(rects.count) { -1 }
        val rectBoxes = IntList()
        var usedFrames = 0
        var lastView = -1
        for (r in 0 until rects.count) {
            if (dropped[r]) continue
            renumber[r] = rectBoxes.size / 4
            rectBoxes.add(layout.x[r]); rectBoxes.add(layout.y[r]); rectBoxes.add(layout.w[r]); rectBoxes.add(layout.h[r])
            if (rects.view[r] != lastView) {
                usedFrames++
                lastView = rects.view[r]
            }
        }
        val triangleRect = IntArray(triangles) { t -> if (groupOf[t] < rects.count) renumber[groupOf[t]] else -1 }
        val chartCount = (0 until charts.count).count { c -> rects.rectOf[c] >= 0 && !dropped[rects.rectOf[c]] }
        val stats = TexturingStats(
            framesConsidered = views.size,
            framesUsed = usedFrames,
            charts = chartCount,
            texturedTriangles = textured,
            fallbackTriangles = triangles - textured,
            atlasSize = layout.size,
            scale = layout.scale,
            triangleRect = triangleRect,
            atlasRects = rectBoxes.toArray(),
            millis = (System.nanoTime() - start) / 1_000_000,
        )
        return TexturingResult(TexturedMesh(TriangleMesh(positions, colors, indices), uv, atlas), stats)
    }

    // ---------------------------------------------------------------- parts

    /**
     * A frame picked for texturing: its camera axes (the pose's rotation columns: right,
     * up and back, the camera looking down -back) and position, and the intrinsics its
     * image was recorded with.
     */
    private class View(val frame: CaptureFrame, val k: CameraIntrinsics, pose: FloatArray, val weight: Float) {
        val rx = pose[0]; val ry = pose[1]; val rz = pose[2]
        val ux = pose[4]; val uy = pose[5]; val uz = pose[6]
        val bx = pose[8]; val by = pose[9]; val bz = pose[10]
        val px = pose[12]; val py = pose[13]; val pz = pose[14]

        /**
         * Image position (pixel centres at integers, v down) of vertex [v] into [out], as
         * [com.banyawa.sitescanner.core.pointcloud.DepthUnprojector] unprojects: camera
         * space is the pose's inverse (a rotation, so its transpose).
         */
        fun project(positions: FloatArray, v: Int, out: FloatArray) {
            val dx = positions[v * 3] - px
            val dy = positions[v * 3 + 1] - py
            val dz = positions[v * 3 + 2] - pz
            val xc = rx * dx + ry * dy + rz * dz
            val yc = ux * dx + uy * dy + uz * dz
            val depth = -(bx * dx + by * dy + bz * dz)
            out[0] = k.fx * xc / depth + k.cx
            out[1] = -k.fy * yc / depth + k.cy
        }
    }

    /**
     * Keyframes chosen by what they see. A few thousand points spread over the mesh are
     * scored per frame by how closely and squarely the frame sees them (1 from
     * [REF_DISTANCE_M] or nearer, head on), and frames are taken greedily by what they add
     * over those already taken, which favours new surface first and then closer views.
     * Occlusion comes from a coarse depth buffer of the points themselves splatted at their
     * spacing, so a frame does not get credit for the room behind a wall.
     */
    private class Coverage(mesh: TriangleMesh, private val options: TexturingOptions) {
        private val count = min(mesh.triangleCount, COVERAGE_SAMPLES)
        private val points = FloatArray(count * 3)
        private val normals = FloatArray(count * 3)
        private val weight = FloatArray(count)
        private val radius: Float
        private val depth = FloatArray(count)
        private val imgU = FloatArray(count)
        private val imgV = FloatArray(count)
        private val facing = FloatArray(count)
        private var zbuf = FloatArray(0)

        init {
            val p = mesh.positions
            val idx = mesh.indices
            val triangles = mesh.triangleCount
            var totalArea = 0.0
            var next = 0
            for (t in 0 until triangles) {
                val a = idx[t * 3] * 3
                val b = idx[t * 3 + 1] * 3
                val c = idx[t * 3 + 2] * 3
                val e1x = p[b] - p[a]; val e1y = p[b + 1] - p[a + 1]; val e1z = p[b + 2] - p[a + 2]
                val e2x = p[c] - p[a]; val e2y = p[c + 1] - p[a + 1]; val e2z = p[c + 2] - p[a + 2]
                val nx = e1y * e2z - e1z * e2y
                val ny = e1z * e2x - e1x * e2z
                val nz = e1x * e2y - e1y * e2x
                val l = sqrt(nx * nx + ny * ny + nz * nz)
                totalArea += l / 2.0
                // Every (triangles / count)-th triangle stands for its neighbourhood.
                if (next < count && t == (next.toLong() * triangles / count).toInt()) {
                    for (k in 0 until 3) points[next * 3 + k] = (p[a + k] + p[b + k] + p[c + k]) / 3f
                    if (l > 0f) {
                        normals[next * 3] = nx / l; normals[next * 3 + 1] = ny / l; normals[next * 3 + 2] = nz / l
                    }
                    weight[next] = l / 2f
                    next++
                }
            }
            radius = (sqrt(totalArea / count / Math.PI) * SPLAT_SCALE).toFloat()
        }

        /** Frames (indices into [views], ascending) to texture from, at most [maxFrames]. */
        fun select(views: List<View>, maxFrames: Int): IntArray {
            val n = views.size
            val quality = ByteArray(n * count)
            for (f in 0 until n) score(views[f], quality, f * count)
            val best = IntArray(count)
            // Lazy greedy: a frame's gain only shrinks as others are taken, so its last
            // computed gain bounds it and most frames need no recomputing.
            val bound = FloatArray(n) { gain(quality, it, best) }
            val picked = BooleanArray(n)
            val out = IntList(maxFrames)
            while (out.size < maxFrames) {
                var chosen = -1
                while (true) {
                    var top = -1
                    var second = 0f
                    for (f in 0 until n) {
                        if (picked[f]) continue
                        if (top < 0 || bound[f] > bound[top]) {
                            if (top >= 0) second = max(second, bound[top])
                            top = f
                        } else {
                            second = max(second, bound[f])
                        }
                    }
                    if (top < 0 || bound[top] <= 0f) break
                    bound[top] = gain(quality, top, best)
                    if (bound[top] >= second) {
                        chosen = top
                        break
                    }
                }
                // Nothing left to add: fewer frames do.
                if (chosen < 0 || bound[chosen] <= 0f) break
                picked[chosen] = true
                out.add(chosen)
                for (s in 0 until count) best[s] = max(best[s], quality[chosen * count + s].toInt() and 0xFF)
            }
            return out.toArray().also { it.sort() }
        }

        private fun gain(quality: ByteArray, f: Int, best: IntArray): Float {
            var g = 0f
            val base = f * count
            for (s in 0 until count) {
                val d = (quality[base + s].toInt() and 0xFF) - best[s]
                if (d > 0) g += d * weight[s]
            }
            return g
        }

        /** How well [view] sees each point, 0..255, into [out] from [offset]. */
        private fun score(view: View, out: ByteArray, offset: Int) {
            val k = view.k
            val zw = COVERAGE_BUFFER
            val zs = zw.toFloat() / k.width
            val zh = max(1, ceil(k.height * zs).toInt())
            if (zbuf.size < zw * zh) zbuf = FloatArray(zw * zh)
            zbuf.fill(0f, 0, zw * zh)
            val fz = k.fx * zs
            for (s in 0 until count) {
                val tx = view.px - points[s * 3]
                val ty = view.py - points[s * 3 + 1]
                val tz = view.pz - points[s * 3 + 2]
                val xc = -(view.rx * tx + view.ry * ty + view.rz * tz)
                val yc = -(view.ux * tx + view.uy * ty + view.uz * tz)
                val zc = -(view.bx * tx + view.by * ty + view.bz * tz)
                if (zc >= -NEAR_M) {
                    depth[s] = -1f
                    continue
                }
                val d = -zc
                val u = k.fx * xc / d + k.cx
                val v = -k.fy * yc / d + k.cy
                val cos = (normals[s * 3] * tx + normals[s * 3 + 1] * ty + normals[s * 3 + 2] * tz) / sqrt(tx * tx + ty * ty + tz * tz)
                depth[s] = d
                imgU[s] = u
                imgV[s] = v
                facing[s] = cos
                // A disc of the points' spacing, shrunk as it turns away (it foreshortens).
                val r = min(MAX_SPLAT, radius * fz / d * max(abs(cos), 0.25f))
                val cx = (u + 0.5f) * zs
                val cy = (v + 0.5f) * zs
                val x0 = max(0, floor(cx - r).toInt())
                val x1 = min(zw - 1, floor(cx + r).toInt())
                val y0 = max(0, floor(cy - r).toInt())
                val y1 = min(zh - 1, floor(cy + r).toInt())
                val inv = 1f / d
                for (y in y0..y1) for (x in x0..x1) if (inv > zbuf[y * zw + x]) zbuf[y * zw + x] = inv
            }
            val maxU = k.width - 1f
            val maxV = k.height - 1f
            for (s in 0 until count) {
                var q = 0
                val d = depth[s]
                val u = imgU[s]
                val v = imgV[s]
                if (d > 0f && u >= 0f && u <= maxU && v >= 0f && v <= maxV && facing[s] >= options.minViewCos) {
                    val zx = ((u + 0.5f) * zs).toInt().coerceIn(0, zw - 1)
                    val zy = ((v + 0.5f) * zs).toInt().coerceIn(0, zh - 1)
                    val here = zbuf[zy * zw + zx]
                    if (d <= (1f / here) * (1f + COVERAGE_TOLERANCE) + COVERAGE_TOLERANCE_M) {
                        val closeness = min(1f, REF_DISTANCE_M * sqrt(facing[s]) / d)
                        q = (closeness * (0.5f + 0.5f * view.weight) * 255f + 0.5f).toInt()
                    }
                }
                out[offset + s] = q.toByte()
            }
        }
    }

    /**
     * Per frame: which triangles it sees and how well. Keeps its per-vertex buffers
     * between frames so the loop over frames allocates nothing.
     */
    private class Visibility(private val mesh: TriangleMesh, private val options: TexturingOptions) {
        private val n = mesh.vertexCount
        private val camX = FloatArray(n)
        private val camY = FloatArray(n)
        private val camZ = FloatArray(n)
        private val imgU = FloatArray(n)
        private val imgV = FloatArray(n)
        private val normals = FloatArray(mesh.triangleCount * 3)
        private val centroids = FloatArray(mesh.triangleCount * 3)
        private var zbuf = FloatArray(0)

        init {
            val p = mesh.positions
            val idx = mesh.indices
            for (t in 0 until mesh.triangleCount) {
                val a = idx[t * 3] * 3
                val b = idx[t * 3 + 1] * 3
                val c = idx[t * 3 + 2] * 3
                val e1x = p[b] - p[a]; val e1y = p[b + 1] - p[a + 1]; val e1z = p[b + 2] - p[a + 2]
                val e2x = p[c] - p[a]; val e2y = p[c + 1] - p[a + 1]; val e2z = p[c + 2] - p[a + 2]
                val nx = e1y * e2z - e1z * e2y
                val ny = e1z * e2x - e1x * e2z
                val nz = e1x * e2y - e1y * e2x
                val l = sqrt(nx * nx + ny * ny + nz * nz)
                // A degenerate triangle keeps a zero normal and so faces no camera.
                if (l > 0f) {
                    normals[t * 3] = nx / l; normals[t * 3 + 1] = ny / l; normals[t * 3 + 2] = nz / l
                }
                for (k in 0 until 3) centroids[t * 3 + k] = (p[a + k] + p[b + k] + p[c + k]) / 3f
            }
        }

        fun score(view: View, viewIndex: Int, candFrame: IntArray, candScore: FloatArray) {
            val k = view.k
            val zw = max(8, options.depthTestSize)
            val zs = zw.toFloat() / k.width
            val zh = max(1, ceil(k.height * zs).toInt())
            if (zbuf.size < zw * zh) zbuf = FloatArray(zw * zh)
            // Inverse depth: 0 is empty, larger is nearer, and it interpolates linearly on screen.
            zbuf.fill(0f, 0, zw * zh)

            val p = mesh.positions
            for (v in 0 until n) {
                val dx = p[v * 3] - view.px
                val dy = p[v * 3 + 1] - view.py
                val dz = p[v * 3 + 2] - view.pz
                val xc = view.rx * dx + view.ry * dy + view.rz * dz
                val yc = view.ux * dx + view.uy * dy + view.uz * dz
                val zc = view.bx * dx + view.by * dy + view.bz * dz
                camX[v] = xc
                camY[v] = yc
                camZ[v] = zc
                if (zc < -NEAR_M) {
                    imgU[v] = k.fx * xc / -zc + k.cx
                    imgV[v] = k.fy * -yc / -zc + k.cy
                }
            }

            val idx = mesh.indices
            val triangles = mesh.triangleCount
            for (t in 0 until triangles) {
                val a = idx[t * 3]; val b = idx[t * 3 + 1]; val c = idx[t * 3 + 2]
                // Triangles crossing the near plane are left out rather than clipped: they are rare in a room.
                if (camZ[a] >= -NEAR_M || camZ[b] >= -NEAR_M || camZ[c] >= -NEAR_M) continue
                rasterize(
                    (imgU[a] + 0.5f) * zs, (imgV[a] + 0.5f) * zs, -1f / camZ[a],
                    (imgU[b] + 0.5f) * zs, (imgV[b] + 0.5f) * zs, -1f / camZ[b],
                    (imgU[c] + 0.5f) * zs, (imgV[c] + 0.5f) * zs, -1f / camZ[c],
                    zw, zh,
                )
            }

            val maxU = (k.width - 1).toFloat()
            val maxV = (k.height - 1).toFloat()
            val halfW = k.width * 0.5f
            val halfH = k.height * 0.5f
            for (t in 0 until triangles) {
                val a = idx[t * 3]; val b = idx[t * 3 + 1]; val c = idx[t * 3 + 2]
                if (camZ[a] >= -NEAR_M || camZ[b] >= -NEAR_M || camZ[c] >= -NEAR_M) continue
                val ua = imgU[a]; val va = imgV[a]; val ub = imgU[b]; val vb = imgV[b]; val uc = imgU[c]; val vc = imgV[c]
                // The whole triangle in the photo, so its crop is never clamped.
                if (ua < 0f || ua > maxU || ub < 0f || ub > maxU || uc < 0f || uc > maxU) continue
                if (va < 0f || va > maxV || vb < 0f || vb > maxV || vc < 0f || vc > maxV) continue

                val tx = view.px - centroids[t * 3]
                val ty = view.py - centroids[t * 3 + 1]
                val tz = view.pz - centroids[t * 3 + 2]
                val cos = (normals[t * 3] * tx + normals[t * 3 + 1] * ty + normals[t * 3 + 2] * tz) / sqrt(tx * tx + ty * ty + tz * tz)
                if (!(cos >= options.minViewCos)) continue
                val area = 0.5f * abs((ub - ua) * (vc - va) - (uc - ua) * (vb - va))
                if (!(area > 0f)) continue

                // Occlusion: the centroid against the depth buffer where it lands...
                val depth = -(camZ[a] + camZ[b] + camZ[c]) / 3f
                val u = k.fx * (camX[a] + camX[b] + camX[c]) / 3f / depth + k.cx
                val v = -k.fy * (camY[a] + camY[b] + camY[c]) / 3f / depth + k.cy
                val zx = ((u + 0.5f) * zs).toInt().coerceIn(0, zw - 1)
                val zy = ((v + 0.5f) * zs).toInt().coerceIn(0, zh - 1)
                val here = zbuf[zy * zw + zx]
                if (here > 0f && depth > (1f / here) * (1f + DEPTH_TOLERANCE) + DEPTH_TOLERANCE_M) continue
                // ...and nothing clearly in front of the triangle around it, which catches the
                // silhouettes of objects the coarse buffer's one sample missed.
                val nearest = min(-camZ[a], min(-camZ[b], -camZ[c])) * (1f - EDGE_TOLERANCE) - EDGE_TOLERANCE_M
                if (nearest > 0f && occludedAround(zx, zy, zw, zh, 1f / nearest)) continue

                val du = (u - k.cx) / halfW
                val dv = (v - k.cy) / halfH
                val centre = 1f / (1f + CENTRE_FALLOFF * (du * du + dv * dv))
                insert(t, viewIndex, area * cos * centre * view.weight, candFrame, candScore)
            }
        }

        private fun occludedAround(zx: Int, zy: Int, zw: Int, zh: Int, limit: Float): Boolean {
            for (y in max(0, zy - 1)..min(zh - 1, zy + 1)) {
                for (x in max(0, zx - 1)..min(zw - 1, zx + 1)) if (zbuf[y * zw + x] > limit) return true
            }
            return false
        }

        /** Keeps the [CANDIDATES] best views of triangle [t], best first. */
        private fun insert(t: Int, frame: Int, score: Float, candFrame: IntArray, candScore: FloatArray) {
            val base = t * CANDIDATES
            val last = base + CANDIDATES - 1
            if (candFrame[last] >= 0 && candScore[last] >= score) return
            var i = last
            while (i > base && (candFrame[i - 1] < 0 || candScore[i - 1] < score)) {
                candFrame[i] = candFrame[i - 1]
                candScore[i] = candScore[i - 1]
                i--
            }
            candFrame[i] = frame
            candScore[i] = score
        }

        /** Z-buffers one triangle (depth-buffer pixel units, pixel centres at +0.5), keeping the nearest inverse depth. */
        private fun rasterize(
            x0: Float, y0: Float, w0: Float,
            x1: Float, y1: Float, w1: Float,
            x2: Float, y2: Float, w2: Float,
            zw: Int, zh: Int,
        ) {
            val minX = max(0, ceil(min(x0, min(x1, x2)) - 0.5f).toInt())
            val maxX = min(zw - 1, floor(max(x0, max(x1, x2)) - 0.5f).toInt())
            if (minX > maxX) return
            val minY = max(0, ceil(min(y0, min(y1, y2)) - 0.5f).toInt())
            val maxY = min(zh - 1, floor(max(y0, max(y1, y2)) - 0.5f).toInt())
            if (minY > maxY) return
            val area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
            if (abs(area) < 1e-9f) return
            val inv = 1f / area
            for (y in minY..maxY) {
                val py = y + 0.5f
                for (x in minX..maxX) {
                    val px = x + 0.5f
                    val l0 = ((x1 - px) * (y2 - py) - (x2 - px) * (y1 - py)) * inv
                    val l1 = ((x2 - px) * (y0 - py) - (x0 - px) * (y2 - py)) * inv
                    val l2 = 1f - l0 - l1
                    if (l0 < -EDGE_EPS || l1 < -EDGE_EPS || l2 < -EDGE_EPS) continue
                    val w = l0 * w0 + l1 * w1 + l2 * w2
                    val i = y * zw + x
                    if (w > zbuf[i]) zbuf[i] = w
                }
            }
        }
    }

    /** Connected triangles sharing a frame (breadth-first over shared edges). */
    private class Charts(val chartOf: IntArray, val frame: IntArray, val size: IntArray, val count: Int) {
        companion object {
            fun build(label: IntArray, adjacency: IntArray): Charts {
                val triangles = label.size
                val chartOf = IntArray(triangles) { -1 }
                val queue = IntArray(triangles)
                val frames = IntList()
                val sizes = IntList()
                for (s in 0 until triangles) {
                    if (label[s] < 0 || chartOf[s] >= 0) continue
                    val c = frames.size
                    chartOf[s] = c
                    var head = 0
                    var tail = 0
                    queue[tail++] = s
                    while (head < tail) {
                        val t = queue[head++]
                        for (j in 0 until 3) {
                            val nb = adjacency[t * 3 + j]
                            if (nb >= 0 && chartOf[nb] < 0 && label[nb] == label[s]) {
                                chartOf[nb] = c
                                queue[tail++] = nb
                            }
                        }
                    }
                    frames.add(label[s])
                    sizes.add(tail)
                }
                return Charts(chartOf, frames.toArray(), sizes.toArray(), frames.size)
            }
        }
    }

    /**
     * The photo rectangles to copy: each chart's bounding box in its frame's image (recorded
     * intrinsics' pixels, inclusive), overlapping boxes of one frame merged where the union
     * costs no more atlas space than the two apart. Ordered by frame.
     */
    private class Rects(
        val count: Int,
        val view: IntArray,
        val x0: IntArray,
        val y0: IntArray,
        val x1: IntArray,
        val y1: IntArray,
        val triangles: IntArray,
        val meanColor: IntArray,
        /** Per chart, its rectangle. */
        val rectOf: IntArray,
    ) {
        fun width(r: Int) = x1[r] - x0[r] + 1
        fun height(r: Int) = y1[r] - y0[r] + 1

        companion object {
            fun of(mesh: TriangleMesh, charts: Charts, views: List<View>, pad: Int): Rects {
                val cc = charts.count
                val minU = FloatArray(cc) { Float.POSITIVE_INFINITY }
                val minV = FloatArray(cc) { Float.POSITIVE_INFINITY }
                val maxU = FloatArray(cc) { Float.NEGATIVE_INFINITY }
                val maxV = FloatArray(cc) { Float.NEGATIVE_INFINITY }
                val colorSum = LongArray(cc * 3)
                val p = FloatArray(2)
                for (t in charts.chartOf.indices) {
                    val c = charts.chartOf[t]
                    if (c < 0) continue
                    val view = views[charts.frame[c]]
                    for (j in 0 until 3) {
                        val v = mesh.indices[t * 3 + j]
                        view.project(mesh.positions, v, p)
                        if (p[0] < minU[c]) minU[c] = p[0]
                        if (p[0] > maxU[c]) maxU[c] = p[0]
                        if (p[1] < minV[c]) minV[c] = p[1]
                        if (p[1] > maxV[c]) maxV[c] = p[1]
                        for (ch in 0 until 3) colorSum[c * 3 + ch] += (mesh.colors[v * 3 + ch].toInt() and 0xFF).toLong()
                    }
                }
                // Boxes per chart, then merged within each frame (union-find over chart ids).
                val bx0 = IntArray(cc); val by0 = IntArray(cc); val bx1 = IntArray(cc); val by1 = IntArray(cc)
                for (c in 0 until cc) {
                    val k = views[charts.frame[c]].k
                    bx0[c] = floor(minU[c]).toInt().coerceIn(0, k.width - 1)
                    by0[c] = floor(minV[c]).toInt().coerceIn(0, k.height - 1)
                    bx1[c] = ceil(maxU[c]).toInt().coerceIn(bx0[c], k.width - 1)
                    by1[c] = ceil(maxV[c]).toInt().coerceIn(by0[c], k.height - 1)
                }
                fun paddedArea(x0: Int, y0: Int, x1: Int, y1: Int) = (x1 - x0 + 1 + 2L * pad) * (y1 - y0 + 1 + 2L * pad)
                val parent = IntArray(cc) { it }
                fun find(c: Int): Int {
                    var r = c
                    while (parent[r] != r) r = parent[r]
                    return r
                }
                val byFrame = (0 until cc).sortedWith(compareBy<Int>({ charts.frame[it] }, { -paddedArea(bx0[it], by0[it], bx1[it], by1[it]) }))
                val kept = IntArray(cc)
                val rectRoots = IntList()
                var i = 0
                while (i < cc) {
                    val frame = charts.frame[byFrame[i]]
                    var keptCount = 0
                    while (i < cc && charts.frame[byFrame[i]] == frame) {
                        val c = byFrame[i++]
                        var merged = true
                        while (merged) {
                            merged = false
                            var q = 0
                            while (q < keptCount) {
                                val o = kept[q]
                                val ux0 = min(bx0[c], bx0[o]); val uy0 = min(by0[c], by0[o])
                                val ux1 = max(bx1[c], bx1[o]); val uy1 = max(by1[c], by1[o])
                                if (paddedArea(ux0, uy0, ux1, uy1) <= paddedArea(bx0[c], by0[c], bx1[c], by1[c]) + paddedArea(bx0[o], by0[o], bx1[o], by1[o])) {
                                    bx0[c] = ux0; by0[c] = uy0; bx1[c] = ux1; by1[c] = uy1
                                    parent[o] = c
                                    kept[q] = kept[--keptCount]
                                    merged = true
                                } else {
                                    q++
                                }
                            }
                        }
                        kept[keptCount++] = c
                    }
                    for (q in 0 until keptCount) rectRoots.add(kept[q])
                }
                val count = rectRoots.size
                val rectOfRoot = IntArray(cc) { -1 }
                for (r in 0 until count) rectOfRoot[rectRoots[r]] = r
                val rectOf = IntArray(cc) { rectOfRoot[find(it)] }
                val triangles = IntArray(count)
                val sums = LongArray(count * 3)
                for (c in 0 until cc) {
                    val r = rectOf[c]
                    triangles[r] += charts.size[c]
                    for (ch in 0 until 3) sums[r * 3 + ch] += colorSum[c * 3 + ch]
                }
                val meanColor = IntArray(count) { r ->
                    val n = max(1L, triangles[r] * 3L)
                    (((sums[r * 3] / n).toInt() shl 16) or ((sums[r * 3 + 1] / n).toInt() shl 8) or (sums[r * 3 + 2] / n).toInt())
                }
                return Rects(
                    count,
                    IntArray(count) { charts.frame[rectRoots[it]] },
                    IntArray(count) { bx0[rectRoots[it]] },
                    IntArray(count) { by0[rectRoots[it]] },
                    IntArray(count) { bx1[rectRoots[it]] },
                    IntArray(count) { by1[rectRoots[it]] },
                    triangles,
                    meanColor,
                    rectOf,
                )
            }
        }
    }

    /**
     * Solid patches for the triangles no photo textures: one [CELL]-pixel square per colour
     * in use (their vertex colours averaged and quantised to [bits] a channel), laid out in
     * a grid at the atlas origin. All three corners of such a triangle sample the middle of
     * its square. The squares match JPEG's 16-pixel blocks, so the encoded atlas keeps each
     * one flat: smaller ones take on their neighbours' hue through JPEG's halved colour
     * resolution.
     */
    private class Palette(private val bits: Int, private val cellIndex: IntArray, val colors: IntArray, val columns: Int) {
        val count get() = colors.size
        val rows get() = if (count == 0) 0 else (count + columns - 1) / columns
        val width get() = if (count == 0) 0 else columns * CELL
        val height get() = rows * CELL

        fun cellOf(mesh: TriangleMesh, t: Int): Int = cellIndex[key(mesh, t, bits)]
        fun cellX(cell: Int) = cell % columns * CELL
        fun cellY(cell: Int) = cell / columns * CELL

        fun draw(atlas: IntArray, size: Int) {
            for (cell in 0 until count) fillSolid(atlas, size, cellX(cell), cellY(cell), CELL, CELL, colors[cell])
        }

        companion object {
            fun key(mesh: TriangleMesh, t: Int, bits: Int): Int {
                var k = 0
                for (ch in 0 until 3) {
                    var sum = 0
                    for (j in 0 until 3) sum += mesh.colors[mesh.indices[t * 3 + j] * 3 + ch].toInt() and 0xFF
                    k = (k shl bits) or ((sum / 3) shr (8 - bits))
                }
                return k
            }

            /**
             * The patches for the triangles without a photo, in colours coarse enough for them
             * to take at most a quarter of an atlas of [atlasSize].
             */
            fun of(mesh: TriangleMesh, charts: Charts, rects: Rects, dropped: BooleanArray, atlasSize: Int): Palette {
                var bits = 4
                while (true) {
                    val palette = quantised(mesh, charts, rects, dropped, bits)
                    if (bits == 1 || palette.width <= atlasSize / 2) return palette
                    bits--
                }
            }

            private fun quantised(mesh: TriangleMesh, charts: Charts, rects: Rects, dropped: BooleanArray, bits: Int): Palette {
                val keys = 1 shl (3 * bits)
                val cellIndex = IntArray(keys) { -1 }
                val sums = IntArray(keys * 4)
                var count = 0
                for (t in charts.chartOf.indices) {
                    val c = charts.chartOf[t]
                    if (c >= 0 && !dropped[rects.rectOf[c]]) continue
                    val k = key(mesh, t, bits)
                    var cell = cellIndex[k]
                    if (cell < 0) {
                        cell = count++
                        cellIndex[k] = cell
                    }
                    for (ch in 0 until 3) {
                        for (j in 0 until 3) sums[cell * 4 + ch] += mesh.colors[mesh.indices[t * 3 + j] * 3 + ch].toInt() and 0xFF
                    }
                    sums[cell * 4 + 3] += 3
                }
                val colors = IntArray(count) { cell ->
                    val n = sums[cell * 4 + 3]
                    ((sums[cell * 4] / n) shl 16) or ((sums[cell * 4 + 1] / n) shl 8) or (sums[cell * 4 + 2] / n)
                }
                val columns = max(1, ceil(sqrt(count.toDouble())).toInt())
                return Palette(bits, cellIndex, colors, columns)
            }
        }
    }

    /** Where every rectangle (and the palette) went in the atlas, at [scale] of the photos' resolution. */
    private class Layout(
        val size: Int,
        val scale: Float,
        val x: IntArray,
        val y: IntArray,
        val w: IntArray,
        val h: IntArray,
        /** At the atlas origin, aligned with JPEG's blocks. */
        val palette: Palette,
    ) {
        companion object {
            /**
             * The smallest power-of-two atlas up to [TexturingOptions.atlasSize] holding
             * everything at full resolution; else the full size at the largest scale that
             * fits. Null when not even [MIN_SCALE] fits.
             */
            fun fit(rects: Rects, dropped: BooleanArray, palette: Palette, options: TexturingOptions): Layout? {
                val maxSize = max(MIN_ATLAS, options.atlasSize)
                var size = min(maxSize, FIRST_ATLAS)
                while (true) {
                    tryPack(rects, dropped, palette, options.padding, size, 1f)?.let { return it }
                    if (size >= maxSize) break
                    size = min(maxSize, size * 2)
                }
                if (tryPack(rects, dropped, palette, options.padding, maxSize, MIN_SCALE) == null) return null
                var lo = MIN_SCALE
                var hi = 1f
                repeat(SCALE_STEPS) {
                    val mid = (lo + hi) / 2f
                    if (tryPack(rects, dropped, palette, options.padding, maxSize, mid) != null) lo = mid else hi = mid
                }
                return tryPack(rects, dropped, palette, options.padding, maxSize, lo)
            }

            /** Shelf packing: the palette at the origin, then the rectangles tallest first, left to right in rows. */
            private fun tryPack(rects: Rects, dropped: BooleanArray, palette: Palette, pad: Int, size: Int, scale: Float): Layout? {
                val n = rects.count
                val w = IntArray(n)
                val h = IntArray(n)
                for (r in 0 until n) {
                    if (dropped[r]) continue
                    w[r] = ceil(rects.width(r) * scale - 1e-3f).toInt().coerceAtLeast(1) + 2 * pad
                    h[r] = ceil(rects.height(r) * scale - 1e-3f).toInt().coerceAtLeast(1) + 2 * pad
                }
                if (palette.width > size || palette.height > size) return null
                val order = LongArray(n) { (h[it].toLong() shl 32) or it.toLong() }
                order.sort()
                val x = IntArray(n)
                val y = IntArray(n)
                var cx = palette.width
                var cy = 0
                var shelf = palette.height
                for (q in n - 1 downTo 0) {
                    val r = (order[q] and 0xFFFFFFFFL).toInt()
                    if (w[r] == 0) continue
                    if (w[r] > size) return null
                    if (cx + w[r] > size) {
                        cy += shelf
                        cx = 0
                        shelf = 0
                    }
                    if (cy + h[r] > size) return null
                    x[r] = cx
                    y[r] = cy
                    cx += w[r]
                    shelf = max(shelf, h[r])
                }
                return Layout(size, scale, x, y, w, h, palette)
            }
        }
    }

    /** A decoded frame as packed RGB pixels. */
    private class Source(val width: Int, val height: Int, val pixels: IntArray) {
        /** Bilinear at ([u], [v]), pixel centres at integers, clamped to the edges. */
        fun sample(u: Float, v: Float): Int {
            val fu = floor(u)
            val fv = floor(v)
            val xa = fu.toInt().coerceIn(0, width - 1)
            val xb = (fu.toInt() + 1).coerceIn(0, width - 1)
            val ya = fv.toInt().coerceIn(0, height - 1) * width
            val yb = (fv.toInt() + 1).coerceIn(0, height - 1) * width
            return bilinear(pixels[ya + xa], pixels[ya + xb], pixels[yb + xa], pixels[yb + xb], u - fu, v - fv)
        }

        companion object {
            fun of(image: ColorImage): Source =
                if (image is RgbImage) {
                    Source(image.width, image.height, image.pixels)
                } else {
                    Source(image.width, image.height, IntArray(image.width * image.height) { image.rgbAt(it % image.width, it / image.width) })
                }
        }
    }

    private companion object {
        const val STAGE = "texture"

        /** Views remembered per triangle for the chart smoothing. */
        const val CANDIDATES = 4

        /** Cost of a triangle's texture differing from a neighbour's, against the share of view quality given up. */
        const val SEAM_COST = 0.2f
        const val SMOOTHING_PASSES = 5
        const val TINY_CHART = 3

        const val NEAR_M = 0.05f

        /** A triangle behind the depth buffer by more than this (share of depth, plus metres) is occluded. */
        const val DEPTH_TOLERANCE = 0.02f
        const val DEPTH_TOLERANCE_M = 0.02f

        /** Around it, only something this far in front counts: grazing surfaces step in depth pixel to pixel. */
        const val EDGE_TOLERANCE = 0.05f
        const val EDGE_TOLERANCE_M = 0.05f
        const val EDGE_EPS = 1e-4f

        /** Views get less weight toward the image corners (lens blur, vignetting, distortion): 1 / (1 + f·r²). */
        const val CENTRE_FALLOFF = 0.3f

        /** Camera speed (radians a second, moves counted at 2 m) at which a frame's weight halves for blur. */
        const val BLUR_SPEED = 1.0f
        const val BLUR_DISTANCE_M = 2f

        /** Points and depth-buffer width for choosing keyframes. */
        const val COVERAGE_SAMPLES = 4096
        const val COVERAGE_BUFFER = 64
        const val MAX_CANDIDATES = 2000

        /** Splats a bit wider than the points' spacing, so a surface leaves no gaps to see through. */
        const val SPLAT_SCALE = 1.5f
        const val MAX_SPLAT = 6f
        const val COVERAGE_TOLERANCE = 0.1f
        const val COVERAGE_TOLERANCE_M = 0.1f

        /** A surface seen head on from this near or nearer counts as seen in full detail. */
        const val REF_DISTANCE_M = 1.5f

        /** Side of a solid-colour patch: one JPEG block (16 pixels with halved colour resolution). */
        const val CELL = 16
        const val MIN_ATLAS = 512
        const val FIRST_ATLAS = 256
        const val MIN_SCALE = 1f / 32f
        const val SCALE_STEPS = 12

        /** Samples per axis at most for an atlas pixel of a scaled-down crop. */
        const val MAX_TAPS = 3
        const val BACKGROUND = 0x808080

        fun bilinear(p00: Int, p10: Int, p01: Int, p11: Int, fx: Float, fy: Float): Int {
            var out = 0
            var shift = 16
            while (shift >= 0) {
                val a = ((p00 shr shift) and 0xFF) + (((p10 shr shift) and 0xFF) - ((p00 shr shift) and 0xFF)) * fx
                val b = ((p01 shr shift) and 0xFF) + (((p11 shr shift) and 0xFF) - ((p01 shr shift) and 0xFF)) * fx
                out = (out shl 8) or (a + (b - a) * fy + 0.5f).toInt().coerceIn(0, 255)
                shift -= 8
            }
            return out
        }

        fun fillSolid(atlas: IntArray, size: Int, x: Int, y: Int, w: Int, h: Int, rgb: Int) {
            for (j in 0 until h) atlas.fill(rgb, (y + j) * size + x, (y + j) * size + x + w)
        }

        /** Per triangle and edge (vertices j, j+1), the triangle across it, or -1 (border or non-manifold). */
        fun adjacency(mesh: TriangleMesh): IntArray {
            val triangles = mesh.triangleCount
            val adjacency = IntArray(triangles * 3) { -1 }
            val edges = LongIntMap(triangles * 3 / 2 + 16)
            val idx = mesh.indices
            for (t in 0 until triangles) {
                for (j in 0 until 3) {
                    val a = idx[t * 3 + j]
                    val b = idx[t * 3 + (j + 1) % 3]
                    val key = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
                    val other = edges[key]
                    if (other < 0) {
                        edges[key] = t * 3 + j
                    } else if (adjacency[other] < 0 && other / 3 != t) {
                        adjacency[other] = t
                        adjacency[t * 3 + j] = other / 3
                    }
                }
            }
            return adjacency
        }
    }
}
