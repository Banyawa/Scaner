package com.banyawa.sitescanner.core.stereo

import com.banyawa.sitescanner.core.pointcloud.CameraIntrinsics
import com.banyawa.sitescanner.core.pointcloud.DepthFrame
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One posed image taking part in stereo matching.
 *
 * @param gray luminance image, usually a downscaled copy of the camera image ([GrayImage.of]).
 * @param intrinsics pinhole intrinsics at [gray]'s resolution ([GrayImage.scaleIntrinsics]).
 * @param cameraToWorld column-major pose in the OpenGL convention (camera looks down -Z, +Y up),
 *   as stored with keyframes and used by [DepthFrame].
 */
class StereoView(val gray: GrayImage, val intrinsics: CameraIntrinsics, val cameraToWorld: FloatArray) {
    init {
        require(cameraToWorld.size == 16) { "pose must be a 4x4 matrix" }
        require(intrinsics.width == gray.width && intrinsics.height == gray.height) {
            "intrinsics must be given at the grey image's resolution"
        }
    }
}

data class StereoParams(
    /** Nearest depth plane, metres. */
    val minDepthM: Float = 0.3f,
    /** Farthest depth plane, metres. Surfaces beyond it get no depth rather than a wrong one. */
    val maxDepthM: Float = 6f,
    /**
     * Number of depth planes, spaced evenly in inverse depth: disparity is linear in inverse
     * depth, so equal steps there move the warped patch by equal pixel steps at any distance.
     */
    val planes: Int = 64,
    /** Matching window half size: 2 gives 5×5 ZNCC patches. */
    val patchRadius: Int = 2,
    /** Minimum aggregated ZNCC of the chosen plane. */
    val minNcc: Float = 0.6f,
    /**
     * The best score must beat the second-best local peak (at least two planes away) by this
     * much. Repetitive texture matches equally well at several depths; that is a guess, not
     * a measurement.
     */
    val minUniqueness: Float = 0.1f,
    /**
     * Minimum grey-level variance (levels²) of the reference patch. ZNCC normalises contrast
     * away, so a blank wall's sensor noise would otherwise "match" at a random depth.
     */
    val minTextureVariance: Float = 4f,
    /** Views that must see a patch at a plane for that plane to be scored (capped at the number of neighbours). */
    val minViews: Int = 2,
    /**
     * Scores are averaged over the best this-many views at each plane, so a view in which the
     * surface is occluded or specular does not drag a correct plane down. 0 = drop the worst
     * third of the neighbours (2 of 3, 3 of 4).
     */
    val bestViews: Int = 0,
    /** Confidence (0..255) below which a pixel gets no depth. */
    val minConfidence: Int = 64,
)

/**
 * Depth estimated for the reference view: row-major millimetres along the optical axis
 * (0 = no estimate) and confidence 0..255 (unsigned bytes), the layout of [DepthFrame], at the
 * reference [GrayImage]'s resolution described by [intrinsics].
 */
class StereoResult(
    val width: Int,
    val height: Int,
    val depthMm: ShortArray,
    val confidence: ByteArray,
    val intrinsics: CameraIntrinsics,
) {
    /** Wraps the estimate so it can be fused like sensor depth; [cameraToWorld] is the reference pose. */
    fun toDepthFrame(cameraToWorld: FloatArray, timestampNs: Long = 0L): DepthFrame =
        DepthFrame(width, height, depthMm, confidence, intrinsics, cameraToWorld, timestampNs = timestampNs)
}

/**
 * Multi-view plane-sweep stereo: depth for a reference image from a few neighbouring images
 * with known poses, the way ARCore's motion stereo gets depth on phones without a depth sensor.
 *
 * For each depth plane parallel to the reference image, every reference pixel is assumed to lie
 * on the plane and is looked up in each neighbour; where the assumption is right the reference
 * and neighbour patches look alike. Patches are compared with zero-mean normalised
 * cross-correlation (ZNCC), which ignores the exposure and gain differences between frames.
 * Each pixel then takes the plane with the best score across views, refined between planes
 * with a parabola, and keeps it only if the match is textured, strong and unambiguous.
 *
 * The lookup is a homography per plane and view. With the reference ray `r = K_ref⁻¹·(u, v, 1)`
 * and inverse depth `ρ`, the neighbour pixel is the dehomogenised `K_n·R·r + ρ·K_n·t`, so
 * `K_n·R·r` is precomputed per pixel and each plane costs three multiply-adds and a division
 * per pixel before sampling. Patch sums use separable box filters over the warped image.
 *
 * Cost: `planes × neighbours × pixels × (2·patchRadius + 1) × 2` window taps; about 0.1 s for
 * 160×120, 64 planes, 3 neighbours on a laptop JVM once JIT-compiled. Memory: the full cost volume,
 * `planes × pixels` floats (4.9 MB at those settings), plus about `(4 × neighbours + 8)` floats
 * per pixel, all allocated per call. Single-threaded; callers may run frames in parallel.
 */
object PlaneSweepStereo {
    /** Grey levels are matched with this subtracted so float sums of squares keep their precision. */
    private const val OFFSET = 128f

    /** Cost-volume value of a plane seen by too few views (ZNCC itself lies in [-1, 1]). */
    private const val INVALID = -2f

    /** A warped patch flatter than this variance (levels²) cannot correlate: it scores 0. */
    private const val FLAT_VARIANCE = 0.01f

    /** Points closer than this (metres along a neighbour's optical axis) or behind it are not projected. */
    private const val MIN_PROJECTED_Z = 0.01f

    /** Uniqueness margin (in ZNCC) from which it no longer lowers confidence. */
    private const val FULL_UNIQUENESS = 0.3f

    /** Valid depths a 3×3 median window needs (the pixel included); fewer marks a speckle. */
    private const val MIN_MEDIAN_SUPPORT = 3

    /**
     * Depth of [reference] from its [neighbours] (a few nearby keyframes with enough baseline:
     * 10–30 cm at room distances). Pixels without a trustworthy match get depth 0: low
     * texture, fewer than [StereoParams.minViews] views, a match on the first or last plane
     * (surface likely out of range), weak or ambiguous scores, or isolated speckles.
     */
    fun estimate(reference: StereoView, neighbours: List<StereoView>, params: StereoParams = StereoParams()): StereoResult {
        require(neighbours.isNotEmpty()) { "stereo needs at least one neighbouring view" }
        require(params.planes >= 3) { "need at least 3 planes" }
        require(params.minDepthM > 0f && params.maxDepthM > params.minDepthM) { "invalid depth range" }
        require(params.patchRadius >= 1) { "patch radius must be at least 1" }
        require(params.minNcc < 1f) { "minNcc must be below 1" }
        val w = reference.gray.width
        val h = reference.gray.height
        require(w > 2 * params.patchRadius && h > 2 * params.patchRadius) { "image smaller than the matching patch" }
        return Sweep(reference, neighbours, params).run()
    }

    /**
     * Writes into [out] (12 floats) the projection of reference rays into the neighbour view:
     * `M = K_n·R` row-major in `out[0..8]` and `b = K_n·t` in `out[9..11]`, where `R`, `t` take
     * reference camera coordinates to neighbour camera coordinates. A reference pixel with
     * normalised ray `r = (nx, ny, 1)` at inverse depth `ρ` lands on neighbour pixel
     * `(x/z, y/z)` with `(x, y, z) = M·r + ρ·b` (and `z > 0` when in front of the neighbour).
     *
     * Derivation: in an "OpenCV" camera frame (x right, y down, z forward) a point at depth
     * `d` on ray `r` is `d·r`, and its pixel is `K·p / p.z`. An OpenGL pose's camera frame is
     * the same with Y and Z negated, so the OpenCV camera-to-world rotation is the pose's
     * rotation with its Y and Z columns negated. Then `p_n = R·(d·r) + t` with
     * `R = R_n^T·R_ref`, `t = R_n^T·(c_ref - c_n)`, and dividing by `d` (a projective no-op)
     * gives `R·r + ρ·t`; for the plane `z = d` this is the familiar homography
     * `K_n·(R + t·n^T/d)·K_ref⁻¹` with `n = (0, 0, 1)`.
     */
    internal fun relativeProjection(referencePose: FloatArray, pose: FloatArray, k: CameraIntrinsics, out: FloatArray) {
        val rr = cvRotation(referencePose)
        val rn = cvRotation(pose)
        val r = FloatArray(9)
        for (i in 0..2) {
            for (j in 0..2) {
                r[i * 3 + j] = rn[i * 3] * rr[j * 3] + rn[i * 3 + 1] * rr[j * 3 + 1] + rn[i * 3 + 2] * rr[j * 3 + 2]
            }
        }
        val dx = referencePose[12] - pose[12]
        val dy = referencePose[13] - pose[13]
        val dz = referencePose[14] - pose[14]
        val t0 = rn[0] * dx + rn[1] * dy + rn[2] * dz
        val t1 = rn[3] * dx + rn[4] * dy + rn[5] * dz
        val t2 = rn[6] * dx + rn[7] * dy + rn[8] * dz
        for (j in 0..2) {
            out[j] = k.fx * r[j] + k.cx * r[6 + j]
            out[3 + j] = k.fy * r[3 + j] + k.cy * r[6 + j]
            out[6 + j] = r[6 + j]
        }
        out[9] = k.fx * t0 + k.cx * t2
        out[10] = k.fy * t1 + k.cy * t2
        out[11] = t2
    }

    /** Columns of the OpenCV-frame camera-to-world rotation of an OpenGL [pose], column i at `[3i, 3i + 3)`. */
    private fun cvRotation(pose: FloatArray) = floatArrayOf(
        pose[0], pose[1], pose[2],
        -pose[4], -pose[5], -pose[6],
        -pose[8], -pose[9], -pose[10],
    )

    /** Buffers and passes of one [estimate] call. */
    private class Sweep(val reference: StereoView, val neighbours: List<StereoView>, val params: StereoParams) {
        val w = reference.gray.width
        val h = reference.gray.height
        val wh = w * h
        val r = params.patchRadius
        val patchSize = (2 * r + 1) * (2 * r + 1)
        val invPatch = 1f / patchSize
        val views = neighbours.size
        val planes = params.planes
        val minViews = params.minViews.coerceIn(1, views)
        val bestViews = (if (params.bestViews > 0) params.bestViews else views - views / 3).coerceIn(1, views)
        val invFar = 1f / params.maxDepthM
        val step = (1f / params.minDepthM - invFar) / (planes - 1)

        /** Reference grey levels minus [OFFSET]. */
        val refC = FloatArray(wh)

        /** Per-pixel sum of the reference patch and n·variance; [refOk] marks textured, complete patches. */
        val refSum = FloatArray(wh)
        val refVar = FloatArray(wh)
        val refOk = BooleanArray(wh)

        /** Per neighbour (offset j·wh): `K_n·R·r` for every reference ray, and `K_n·t` per neighbour. */
        val ax = FloatArray(views * wh)
        val ay = FloatArray(views * wh)
        val az = FloatArray(views * wh)
        val bx = FloatArray(views)
        val by = FloatArray(views)
        val bz = FloatArray(views)

        /** Aggregated score per plane and pixel, plane-major. */
        val cost = FloatArray(planes * wh)
        val warped = FloatArray(wh)
        val rowSum = FloatArray(wh)
        val rowSq = FloatArray(wh)
        val rowCross = FloatArray(wh)

        /** ZNCC of each neighbour at the current plane, NaN where the patch left that view. */
        val ncc = FloatArray(views * wh)
        val top = FloatArray(bestViews)

        val depthM = FloatArray(wh)
        val confidence = ByteArray(wh)

        fun run(): StereoResult {
            referenceStatistics()
            projections()
            for (i in 0 until planes) {
                val rho = invFar + i * step
                for (j in 0 until views) {
                    warp(j, rho)
                    score(j)
                }
                aggregate(i * wh)
            }
            pick()
            return StereoResult(w, h, medianToMm(), confidence, reference.intrinsics)
        }

        private fun referenceStatistics() {
            val src = reference.gray.pixels
            for (i in 0 until wh) refC[i] = src[i] - OFFSET
            val minVar = params.minTextureVariance * patchSize
            for (y in r until h - r) {
                for (x in r until w - r) {
                    var s = 0f
                    var ss = 0f
                    for (dy in -r..r) {
                        val row = (y + dy) * w + x
                        for (dx in -r..r) {
                            val g = refC[row + dx]
                            s += g
                            ss += g * g
                        }
                    }
                    val p = y * w + x
                    val varN = ss - s * s * invPatch
                    refSum[p] = s
                    refVar[p] = varN
                    refOk[p] = varN > 0f && varN >= minVar
                }
            }
        }

        private fun projections() {
            val kr = reference.intrinsics
            val m = FloatArray(12)
            for (j in 0 until views) {
                val view = neighbours[j]
                relativeProjection(reference.cameraToWorld, view.cameraToWorld, view.intrinsics, m)
                bx[j] = m[9]
                by[j] = m[10]
                bz[j] = m[11]
                var p = j * wh
                for (v in 0 until h) {
                    val ny = (v - kr.cy) / kr.fy
                    for (u in 0 until w) {
                        val nx = (u - kr.cx) / kr.fx
                        ax[p] = m[0] * nx + m[1] * ny + m[2]
                        ay[p] = m[3] * nx + m[4] * ny + m[5]
                        az[p] = m[6] * nx + m[7] * ny + m[8]
                        p++
                    }
                }
            }
        }

        /** Neighbour [j] resampled onto the reference pixels as if they all lay at inverse depth [rho]. */
        private fun warp(j: Int, rho: Float) {
            val g = neighbours[j].gray
            val o = j * wh
            val qx = bx[j] * rho
            val qy = by[j] * rho
            val qz = bz[j] * rho
            for (p in 0 until wh) {
                val z = az[o + p] + qz
                warped[p] = if (z > MIN_PROJECTED_Z * rho) {
                    val iz = 1f / z
                    g.sample((ax[o + p] + qx) * iz, (ay[o + p] + qy) * iz) - OFFSET
                } else {
                    Float.NaN
                }
            }
        }

        /** ZNCC of each reference patch against the warped neighbour [j], into `ncc[j·wh ..]`. */
        private fun score(j: Int) {
            // Horizontal pass: window sums along rows of W, W² and R·W. A NaN anywhere poisons
            // the sums, which is exactly the "patch left the image" signal we want.
            for (y in 0 until h) {
                val row = y * w
                for (x in r until w - r) {
                    var s = 0f
                    var ss = 0f
                    var sr = 0f
                    val c = row + x
                    for (k in c - r..c + r) {
                        val v = warped[k]
                        s += v
                        ss += v * v
                        sr += v * refC[k]
                    }
                    rowSum[c] = s
                    rowSq[c] = ss
                    rowCross[c] = sr
                }
            }
            // Vertical pass and the correlation itself, only where the reference patch is usable.
            val o = j * wh
            for (y in r until h - r) {
                for (x in r until w - r) {
                    val p = y * w + x
                    if (!refOk[p]) continue
                    var s = 0f
                    var ss = 0f
                    var sr = 0f
                    var k = p - r * w
                    val end = p + r * w
                    while (k <= end) {
                        s += rowSum[k]
                        ss += rowSq[k]
                        sr += rowCross[k]
                        k += w
                    }
                    ncc[o + p] = if (s.isNaN()) {
                        Float.NaN
                    } else {
                        val varN = ss - s * s * invPatch
                        if (varN <= FLAT_VARIANCE * patchSize) {
                            0f
                        } else {
                            val z = (sr - refSum[p] * s * invPatch) / sqrt(refVar[p] * varN)
                            if (z > 1f) 1f else if (z < -1f) -1f else z
                        }
                    }
                }
            }
        }

        /** Robust mean over views of this plane's scores: the best [bestViews] of those that saw the patch. */
        private fun aggregate(base: Int) {
            for (p in 0 until wh) {
                if (!refOk[p]) {
                    cost[base + p] = INVALID
                    continue
                }
                var valid = 0
                var filled = 0
                var idx = p
                for (j in 0 until views) {
                    val s = ncc[idx]
                    idx += wh
                    if (s.isNaN()) continue
                    valid++
                    var k: Int
                    if (filled < bestViews) {
                        k = filled++
                    } else if (s > top[bestViews - 1]) {
                        k = bestViews - 1
                    } else {
                        continue
                    }
                    while (k > 0 && top[k - 1] < s) {
                        top[k] = top[k - 1]
                        k--
                    }
                    top[k] = s
                }
                cost[base + p] = if (valid < minViews) {
                    INVALID
                } else {
                    var sum = 0f
                    for (k in 0 until filled) sum += top[k]
                    sum / filled
                }
            }
        }

        /** Best plane per pixel, sub-plane refinement, uniqueness and confidence. */
        private fun pick() {
            val minNcc = params.minNcc
            val fullUniqueness = max(FULL_UNIQUENESS, 2f * params.minUniqueness)
            for (p in 0 until wh) {
                if (!refOk[p]) continue
                var best = INVALID
                var bi = -1
                var idx = p
                for (i in 0 until planes) {
                    val s = cost[idx]
                    if (s > best) {
                        best = s
                        bi = i
                    }
                    idx += wh
                }
                // A peak on the first or last plane usually means the surface is out of range.
                if (bi <= 0 || bi >= planes - 1 || best < minNcc) continue
                val before = cost[p + (bi - 1) * wh]
                val after = cost[p + (bi + 1) * wh]
                if (before == INVALID || after == INVALID) continue

                // Strongest competing local peak at least two planes away from the winner.
                var second = -1f
                var prev = INVALID
                idx = p
                for (i in 0 until planes) {
                    val s = cost[idx]
                    val next = if (i + 1 < planes) cost[idx + wh] else INVALID
                    if (abs(i - bi) >= 2 && s > second && s >= prev && s >= next) second = s
                    prev = s
                    idx += wh
                }
                val uniqueness = best - second
                if (uniqueness < params.minUniqueness) continue

                // Parabola through the three scores around the peak, in plane units.
                val curvature = before - 2f * best + after
                val offset = if (curvature < 0f) (0.5f * (before - after) / curvature).coerceIn(-0.5f, 0.5f) else 0f
                val rho = invFar + (bi + offset) * step

                val scoreTerm = (best - minNcc) / (1f - minNcc)
                val uniqueTerm = min(1f, uniqueness / fullUniqueness)
                val c = (255f * sqrt(scoreTerm * uniqueTerm)).roundToInt()
                if (c < params.minConfidence) continue
                depthM[p] = 1f / rho
                confidence[p] = c.toByte()
            }
        }

        /**
         * 3×3 median over the valid depths around each valid pixel, in millimetres. Holes are
         * not filled; a pixel with fewer than [MIN_MEDIAN_SUPPORT] valid depths around it is an
         * isolated speckle and is dropped (its confidence too).
         */
        private fun medianToMm(): ShortArray {
            val out = ShortArray(wh)
            val window = FloatArray(9)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val p = y * w + x
                    if (depthM[p] <= 0f) continue
                    var n = 0
                    for (yy in max(0, y - 1)..min(h - 1, y + 1)) {
                        for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
                            val d = depthM[yy * w + xx]
                            if (d <= 0f) continue
                            var k = n++
                            while (k > 0 && window[k - 1] > d) {
                                window[k] = window[k - 1]
                                k--
                            }
                            window[k] = d
                        }
                    }
                    if (n < MIN_MEDIAN_SUPPORT) {
                        confidence[p] = 0
                        continue
                    }
                    val median = 0.5f * (window[(n - 1) / 2] + window[n / 2])
                    out[p] = (median * 1000f).roundToInt().coerceIn(1, 65535).toShort()
                }
            }
            return out
        }
    }
}
