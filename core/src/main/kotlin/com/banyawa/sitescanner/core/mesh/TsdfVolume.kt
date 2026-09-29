package com.banyawa.sitescanner.core.mesh

import com.banyawa.sitescanner.core.pointcloud.DepthFilter
import com.banyawa.sitescanner.core.pointcloud.DepthFrame
import com.banyawa.sitescanner.core.pointcloud.DepthUnprojector
import com.banyawa.sitescanner.core.pointcloud.YuvFrame
import com.banyawa.sitescanner.core.util.FloatList
import com.banyawa.sitescanner.core.util.IntList
import com.banyawa.sitescanner.core.util.LongIntMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Truncated signed distance volume: depth frames fused into one surface with a colour,
 * the way KinectFusion / voxblox build meshes from depth cameras. Each depth pixel updates
 * the voxels along its camera ray within [truncation] of the measured surface (positive in
 * front, negative behind), so noise averages out over many frames and [extractMesh] finds
 * a clean surface where the distance crosses zero.
 *
 * Voxels live in 8×8×8 blocks, allocated only near observed surfaces, up to [maxBlocks]
 * (about 3.5 KB each). Not thread-safe.
 */
class TsdfVolume(
    val voxelSize: Float = DEFAULT_VOXEL_M,
    val truncation: Float = voxelSize * 4,
    val maxBlocks: Int = 20_000,
) {
    private class Block {
        /** Signed distance / truncation, scaled to ±[SDF_SCALE]. */
        val sdf = ShortArray(VOXELS)
        val weight = ByteArray(VOXELS)
        val colorWeight = ByteArray(VOXELS)
        val rgb = ByteArray(VOXELS * 3)
    }

    private val invVoxel = 1f / voxelSize
    private val index = LongIntMap(4096)
    private val blocks = ArrayList<Block>()
    private val blockCoords = IntList()
    private var cachedKey = Long.MIN_VALUE
    private var cachedIndex = -1
    private var cachedBlock: Block? = null

    /** Frame number that last touched each block, to list each block once per frame. */
    private var blockStamps = IntArray(1024) { -1 }
    private var stamp = 0

    val blockCount: Int get() = blocks.size

    /** No more blocks can be allocated: surfaces not seen yet are not added any more. */
    val isFull: Boolean get() = blocks.size >= maxBlocks

    var framesIntegrated = 0
        private set

    /**
     * Fuses one depth frame. The blocks around the measured surface are found from every
     * [pixelStep]-th pixel; then every voxel in them is projected into the depth image, so
     * the surface is covered without gaps even where one depth pixel spans several voxels.
     * Returns the number of voxels updated.
     */
    fun integrate(frame: DepthFrame, filter: DepthFilter = DepthFilter(minConfidence = 0), pixelStep: Int = 2): Int {
        val k = frame.intrinsics
        val m = frame.cameraToWorld
        val ox = m[12]
        val oy = m[13]
        val oz = m[14]
        val conf = frame.confidence
        val step = max(1, pixelStep)
        stamp++

        // Blocks the truncation band around this frame's surface passes through.
        val touched = IntList(256)
        var v = 0
        while (v < frame.height) {
            val ny = (v - k.cy) / k.fy
            var u = 0
            while (u < frame.width) {
                val d = usableDepth(frame, filter, v * frame.width + u)
                if (d > 0f) {
                    val nx = (u - k.cx) / k.fx
                    // The pixel's ray in camera space (OpenGL: looking down -Z), turned into the world.
                    val dx = m[0] * nx - m[4] * ny - m[8]
                    val dy = m[1] * nx - m[5] * ny - m[9]
                    val dz = m[2] * nx - m[6] * ny - m[10]
                    val len = sqrt(dx * dx + dy * dy + dz * dz)
                    if (len > 0f && len.isFinite()) {
                        for (j in -1..1) {
                            // Points on the ray at the surface and a truncation before and behind it.
                            val t = d + j * truncation / len
                            touch(ox + dx * t, oy + dy * t, oz + dz * t, touched)
                        }
                    }
                }
                u += step
            }
            v += step
        }

        var updated = 0
        for (n in 0 until touched.size) updated += integrateBlock(touched[n], frame, filter)
        framesIntegrated++
        return updated
    }

    /** Depth in metres at pixel [i] if it passes [filter], else 0. */
    private fun usableDepth(frame: DepthFrame, filter: DepthFilter, i: Int): Float {
        val mm = frame.depthMm[i].toInt() and 0xFFFF
        if (mm == 0) return 0f
        val c = frame.confidence?.let { it[i].toInt() and 0xFF } ?: 255
        val d = mm * 0.001f
        if (d < filter.minDepthM || d > filter.maxDepthM || c < filter.minConfidence) return 0f
        if (filter.maxEdgeJump > 0f && DepthUnprojector.isDepthEdge(frame, i % frame.width, i / frame.width, mm, filter.maxEdgeJump)) return 0f
        return d
    }

    private fun touch(x: Float, y: Float, z: Float, touched: IntList) {
        val ix = floor(x * invVoxel).toInt()
        val iy = floor(y * invVoxel).toInt()
        val iz = floor(z * invVoxel).toInt()
        if (abs(ix) >= COORD_LIMIT || abs(iy) >= COORD_LIMIT || abs(iz) >= COORD_LIMIT) return
        blockAt(ix shr 3, iy shr 3, iz shr 3, create = true) ?: return
        val idx = cachedIndex
        if (blockStamps[idx] != stamp) {
            blockStamps[idx] = stamp
            touched.add(idx)
        }
    }

    /** Projects each voxel of block [b] into the depth image and fuses the distance it sees. */
    private fun integrateBlock(b: Int, frame: DepthFrame, filter: DepthFilter): Int {
        val block = blocks[b]
        val k = frame.intrinsics
        val m = frame.cameraToWorld
        val color = frame.color
        val ck = frame.colorIntrinsics
        val bx = blockCoords[b * 3] * 8
        val by = blockCoords[b * 3 + 1] * 8
        val bz = blockCoords[b * 3 + 2] * 8
        var updated = 0
        for (i in 0 until VOXELS) {
            // Voxel centre relative to the camera, then into camera space (rotation transposed).
            val wx = (bx + (i and 7) + 0.5f) * voxelSize - m[12]
            val wy = (by + ((i shr 3) and 7) + 0.5f) * voxelSize - m[13]
            val wz = (bz + ((i shr 6) and 7) + 0.5f) * voxelSize - m[14]
            val xc = m[0] * wx + m[1] * wy + m[2] * wz
            val yc = m[4] * wx + m[5] * wy + m[6] * wz
            val zc = m[8] * wx + m[9] * wy + m[10] * wz
            val z = -zc
            if (z <= filter.minDepthM * 0.5f) continue
            val nx = xc / z
            val ny = -yc / z
            val u = (nx * k.fx + k.cx + 0.5f).toInt()
            val v = (ny * k.fy + k.cy + 0.5f).toInt()
            if (u < 0 || v < 0 || u >= frame.width || v >= frame.height) continue
            val pixel = v * frame.width + u
            val d = usableDepth(frame, filter, pixel)
            if (d <= 0f) continue
            // Distance along the ray from the voxel to the measured surface.
            val sdf = (d - z) * sqrt(1f + nx * nx + ny * ny)
            if (sdf < -truncation) continue
            val c = frame.confidence?.let { it[pixel].toInt() and 0xFF } ?: 255
            val rgb = if (color != null && ck != null && abs(sdf) <= COLOR_BAND_VOXELS * voxelSize) {
                color.rgbAt((ck.fx * nx + ck.cx + 0.5f).toInt(), (ck.fy * ny + ck.cy + 0.5f).toInt())
            } else {
                NO_COLOR
            }
            update(block, i, min(sdf, truncation) / truncation, observationWeight(d, c), rgb)
            updated++
        }
        return updated
    }

    private fun update(block: Block, i: Int, sdf: Float, w: Int, rgb: Int) {
        val wOld = block.weight[i].toInt() and 0xFF
        val total = wOld + w
        val s = (block.sdf[i] / SDF_SCALE * wOld + sdf * w) / total
        block.sdf[i] = (s * SDF_SCALE).roundToInt().toShort()
        block.weight[i] = min(total, MAX_WEIGHT).toByte()
        if (rgb != NO_COLOR) {
            val cOld = block.colorWeight[i].toInt() and 0xFF
            val cTotal = cOld + w
            for (ch in 0 until 3) {
                val old = block.rgb[i * 3 + ch].toInt() and 0xFF
                val value = (rgb shr (16 - 8 * ch)) and 0xFF
                block.rgb[i * 3 + ch] = ((old * cOld + value * w + cTotal / 2) / cTotal).toByte()
            }
            block.colorWeight[i] = min(cTotal, MAX_WEIGHT).toByte()
        }
    }

    /** Close, confident depth counts more: raw depth error grows with the square of distance. */
    private fun observationWeight(d: Float, confidence: Int): Int {
        val near = min(1f, (NEAR_M / d) * (NEAR_M / d))
        return (MAX_OBSERVATION_WEIGHT * near * confidence / 255f).roundToInt().coerceIn(1, MAX_OBSERVATION_WEIGHT)
    }

    private fun blockAt(bx: Int, by: Int, bz: Int, create: Boolean): Block? {
        val key = pack(bx, by, bz)
        if (key == cachedKey) return cachedBlock
        var idx = index[key]
        if (idx < 0) {
            if (!create || blocks.size >= maxBlocks) return null
            idx = blocks.size
            blocks += Block()
            blockCoords.add(bx)
            blockCoords.add(by)
            blockCoords.add(bz)
            if (idx >= blockStamps.size) blockStamps = blockStamps.copyOf(blockStamps.size * 2)
            index[key] = idx
        }
        cachedKey = key
        cachedIndex = idx
        cachedBlock = blocks[idx]
        return blocks[idx]
    }

    /**
     * Surface-nets mesh of the zero crossing: a vertex in every voxel cell the surface
     * passes through (the average of the crossings on its edges, coloured likewise) and a
     * quad across every crossed voxel edge, facing the free space. Voxels count once
     * fused with at least [minWeight]; crossings between distances far apart, where the
     * back of one surface meets the front of another, are skipped.
     */
    fun extractMesh(minWeight: Int = DEFAULT_MIN_WEIGHT): TriangleMesh {
        val positions = FloatList(1 shl 16)
        val colors = IntList(1 shl 14)
        val indices = IntList(1 shl 16)
        val cellVertex = LongIntMap(1 shl 14)
        val sdf = FloatArray(L3)
        val weight = IntArray(L3)
        val rgb = IntArray(L3)

        // Pass 1: one vertex per cell (8 voxel centres) that the surface crosses.
        for (b in blocks.indices) {
            val bx = blockCoords[b * 3]
            val by = blockCoords[b * 3 + 1]
            val bz = blockCoords[b * 3 + 2]
            load(bx, by, bz, sdf, weight, rgb)
            for (lz in 0 until 8) for (ly in 0 until 8) for (lx in 0 until 8) {
                val base = local(lx, ly, lz)
                var negative = 0
                var valid = true
                for (c in 0 until 8) {
                    val li = base + CORNER_OFFSETS[c]
                    if (weight[li] < minWeight) {
                        valid = false
                        break
                    }
                    if (sdf[li] < 0f) negative++
                }
                if (!valid || negative == 0 || negative == 8) continue

                var sx = 0f
                var sy = 0f
                var sz = 0f
                var n = 0
                var sr = 0
                var sg = 0
                var sb = 0
                var nc = 0
                for (e in 0 until 12) {
                    val c0 = EDGE_CORNERS[e * 2]
                    val c1 = EDGE_CORNERS[e * 2 + 1]
                    val a = sdf[base + CORNER_OFFSETS[c0]]
                    val bv = sdf[base + CORNER_OFFSETS[c1]]
                    if ((a < 0f) == (bv < 0f) || abs(a - bv) > MAX_JUMP) continue
                    val t = a / (a - bv)
                    sx += (c0 and 1) + t * ((c1 and 1) - (c0 and 1))
                    sy += ((c0 shr 1) and 1) + t * (((c1 shr 1) and 1) - ((c0 shr 1) and 1))
                    sz += ((c0 shr 2) and 1) + t * (((c1 shr 2) and 1) - ((c0 shr 2) and 1))
                    n++
                    val ca = rgb[base + CORNER_OFFSETS[c0]]
                    val cb = rgb[base + CORNER_OFFSETS[c1]]
                    val mixed = when {
                        ca == NO_COLOR && cb == NO_COLOR -> NO_COLOR
                        ca == NO_COLOR -> cb
                        cb == NO_COLOR -> ca
                        else -> lerpRgb(ca, cb, t)
                    }
                    if (mixed != NO_COLOR) {
                        sr += (mixed shr 16) and 0xFF
                        sg += (mixed shr 8) and 0xFF
                        sb += mixed and 0xFF
                        nc++
                    }
                }
                if (n == 0) continue
                val ix = bx * 8 + lx
                val iy = by * 8 + ly
                val iz = bz * 8 + lz
                cellVertex[pack(ix, iy, iz)] = positions.size / 3
                // Voxel values sit at voxel centres, hence the half voxel.
                positions.add((ix + 0.5f + sx / n) * voxelSize)
                positions.add((iy + 0.5f + sy / n) * voxelSize)
                positions.add((iz + 0.5f + sz / n) * voxelSize)
                colors.add(if (nc > 0) ((sr / nc) shl 16) or ((sg / nc) shl 8) or (sb / nc) else YuvFrame.DEFAULT_RGB)
            }
        }

        // Pass 2: a quad joining the four cells around every crossed voxel edge.
        for (b in blocks.indices) {
            val bx = blockCoords[b * 3]
            val by = blockCoords[b * 3 + 1]
            val bz = blockCoords[b * 3 + 2]
            load(bx, by, bz, sdf, weight, rgb)
            for (lz in 0 until 8) for (ly in 0 until 8) for (lx in 0 until 8) {
                val p = local(lx, ly, lz)
                if (weight[p] < minWeight) continue
                val a = sdf[p]
                val ix = bx * 8 + lx
                val iy = by * 8 + ly
                val iz = bz * 8 + lz
                for (axis in 0 until 3) {
                    val q = p + AXIS_OFFSETS[axis]
                    if (weight[q] < minWeight) continue
                    val bv = sdf[q]
                    if ((a < 0f) == (bv < 0f) || abs(a - bv) > MAX_JUMP) continue
                    // Cells around the edge, counter-clockwise seen from the edge's + end.
                    val v0: Int
                    val v1: Int
                    val v2: Int
                    val v3: Int
                    when (axis) {
                        0 -> {
                            v0 = cellVertex[pack(ix, iy - 1, iz - 1)]
                            v1 = cellVertex[pack(ix, iy, iz - 1)]
                            v2 = cellVertex[pack(ix, iy, iz)]
                            v3 = cellVertex[pack(ix, iy - 1, iz)]
                        }
                        1 -> {
                            v0 = cellVertex[pack(ix - 1, iy, iz - 1)]
                            v1 = cellVertex[pack(ix - 1, iy, iz)]
                            v2 = cellVertex[pack(ix, iy, iz)]
                            v3 = cellVertex[pack(ix, iy, iz - 1)]
                        }
                        else -> {
                            v0 = cellVertex[pack(ix - 1, iy - 1, iz)]
                            v1 = cellVertex[pack(ix, iy - 1, iz)]
                            v2 = cellVertex[pack(ix, iy, iz)]
                            v3 = cellVertex[pack(ix - 1, iy, iz)]
                        }
                    }
                    if (v0 < 0 || v1 < 0 || v2 < 0 || v3 < 0) continue
                    // The surface faces the free space: toward the positive distance.
                    if (a < 0f) quad(indices, v0, v1, v2, v3) else quad(indices, v0, v3, v2, v1)
                }
            }
        }

        val packed = colors.toArray()
        val colorBytes = ByteArray(packed.size * 3)
        for (i in packed.indices) {
            colorBytes[i * 3] = (packed[i] shr 16).toByte()
            colorBytes[i * 3 + 1] = (packed[i] shr 8).toByte()
            colorBytes[i * 3 + 2] = packed[i].toByte()
        }
        return TriangleMesh(positions.toArray(), colorBytes, indices.toArray())
    }

    /** Copies block ([bx], [by], [bz]) plus one voxel layer of its +x/+y/+z neighbours into 9³ arrays. */
    private fun load(bx: Int, by: Int, bz: Int, sdf: FloatArray, weight: IntArray, rgb: IntArray) {
        val near = arrayOfNulls<Block>(8)
        for (n in 0 until 8) near[n] = blockAt(bx + (n and 1), by + ((n shr 1) and 1), bz + ((n shr 2) and 1), create = false)
        for (z in 0 until L) for (y in 0 until L) for (x in 0 until L) {
            val li = local(x, y, z)
            val block = near[(x shr 3) or ((y shr 3) shl 1) or ((z shr 3) shl 2)]
            if (block == null) {
                weight[li] = 0
                continue
            }
            val vi = (x and 7) or ((y and 7) shl 3) or ((z and 7) shl 6)
            weight[li] = block.weight[vi].toInt() and 0xFF
            sdf[li] = block.sdf[vi] / SDF_SCALE
            rgb[li] = if (block.colorWeight[vi].toInt() == 0) {
                NO_COLOR
            } else {
                ((block.rgb[vi * 3].toInt() and 0xFF) shl 16) or
                    ((block.rgb[vi * 3 + 1].toInt() and 0xFF) shl 8) or
                    (block.rgb[vi * 3 + 2].toInt() and 0xFF)
            }
        }
    }

    private fun quad(indices: IntList, a: Int, b: Int, c: Int, d: Int) {
        indices.add(a)
        indices.add(b)
        indices.add(c)
        indices.add(a)
        indices.add(c)
        indices.add(d)
    }

    companion object {
        const val DEFAULT_VOXEL_M = 0.02f

        /** Two close observations: a surface seen in a single frame is too likely noise. */
        const val DEFAULT_MIN_WEIGHT = 8

        private const val VOXELS = 512
        private const val L = 9
        private const val L3 = L * L * L
        private const val SDF_SCALE = 32767f
        private const val MAX_WEIGHT = 250
        private const val MAX_OBSERVATION_WEIGHT = 4
        private const val NEAR_M = 1.5f
        private const val NO_COLOR = -1

        /** Voxels this close to the surface (in voxels) take the pixel's colour. */
        private const val COLOR_BAND_VOXELS = 1.5f

        /** Largest jump in distance (in truncations) between neighbours still read as one surface. */
        private const val MAX_JUMP = 1.5f

        private const val COORD_BITS = 21
        private const val COORD_OFFSET = 1 shl (COORD_BITS - 1)
        private const val COORD_LIMIT = COORD_OFFSET - 16
        private const val COORD_MASK = (1L shl COORD_BITS) - 1

        private fun local(x: Int, y: Int, z: Int) = x + L * (y + L * z)

        /** Corner c of a cell is at (c & 1, c >> 1 & 1, c >> 2 & 1). */
        private val CORNER_OFFSETS = IntArray(8) { local(it and 1, (it shr 1) and 1, (it shr 2) and 1) }

        /** The 12 cell edges as corner pairs differing in one axis. */
        private val EDGE_CORNERS = buildList {
            for (c in 0 until 8) for (bit in intArrayOf(1, 2, 4)) if ((c and bit) == 0) {
                add(c)
                add(c or bit)
            }
        }.toIntArray()

        private val AXIS_OFFSETS = intArrayOf(local(1, 0, 0), local(0, 1, 0), local(0, 0, 1))

        private fun pack(x: Int, y: Int, z: Int): Long =
            (((x + COORD_OFFSET).toLong() and COORD_MASK) shl (2 * COORD_BITS)) or
                (((y + COORD_OFFSET).toLong() and COORD_MASK) shl COORD_BITS) or
                ((z + COORD_OFFSET).toLong() and COORD_MASK)

        private fun lerpRgb(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int): Int {
                val x = (a shr shift) and 0xFF
                val y = (b shr shift) and 0xFF
                return (x + (y - x) * t).roundToInt().coerceIn(0, 255)
            }
            return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
