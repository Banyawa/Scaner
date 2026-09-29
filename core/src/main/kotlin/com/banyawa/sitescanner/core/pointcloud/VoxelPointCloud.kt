package com.banyawa.sitescanner.core.pointcloud

import kotlin.math.floor

/**
 * Sparse voxel grid that fuses many noisy depth observations into one point per voxel
 * (confidence-weighted centroid and colour). Uses open addressing over primitive arrays
 * so millions of voxels fit in a phone's heap.
 *
 * Not thread-safe; see [ScanIntegrator] for a synchronised wrapper.
 */
class VoxelPointCloud(
    val voxelSize: Float = 0.01f,
    val maxVoxels: Int = 3_000_000,
) : PointSink {
    private val invVoxel = 1f / voxelSize

    private var tableKeys = LongArray(INITIAL_TABLE) { EMPTY }
    private var tableSlots = IntArray(INITIAL_TABLE)
    private var mask = INITIAL_TABLE - 1

    private var sumX = FloatArray(INITIAL_DATA)
    private var sumY = FloatArray(INITIAL_DATA)
    private var sumZ = FloatArray(INITIAL_DATA)
    private var sumR = FloatArray(INITIAL_DATA)
    private var sumG = FloatArray(INITIAL_DATA)
    private var sumB = FloatArray(INITIAL_DATA)
    private var sumW = FloatArray(INITIAL_DATA)

    var size = 0
        private set

    val isFull get() = size >= maxVoxels

    override fun accept(x: Float, y: Float, z: Float, rgb: Int, weight: Float) {
        if (weight <= 0f || x.isNaN() || y.isNaN() || z.isNaN()) return
        val ix = floor(x * invVoxel).toInt()
        val iy = floor(y * invVoxel).toInt()
        val iz = floor(z * invVoxel).toInt()
        if (ix <= -COORD_OFFSET || ix >= COORD_OFFSET ||
            iy <= -COORD_OFFSET || iy >= COORD_OFFSET ||
            iz <= -COORD_OFFSET || iz >= COORD_OFFSET
        ) return
        val key = packKey(ix, iy, iz)
        val slot = findOrInsert(key)
        if (slot < 0) return
        sumX[slot] += x * weight
        sumY[slot] += y * weight
        sumZ[slot] += z * weight
        sumR[slot] += ((rgb shr 16) and 0xFF) * weight
        sumG[slot] += ((rgb shr 8) and 0xFF) * weight
        sumB[slot] += (rgb and 0xFF) * weight
        sumW[slot] += weight
    }

    /** Accumulated confidence weight of voxel [slot] (insertion order). */
    fun weightAt(slot: Int) = sumW[slot]

    /**
     * Exports voxel centroids whose accumulated weight is at least [minWeight].
     * When more than [maxPoints] qualify, an evenly strided subset is returned.
     */
    fun toPointCloud(minWeight: Float = 0f, maxPoints: Int = Int.MAX_VALUE): PointCloud {
        var qualifying = 0
        for (i in 0 until size) if (sumW[i] >= minWeight) qualifying++
        val step = if (qualifying > maxPoints) (qualifying + maxPoints - 1) / maxPoints else 1
        val n = (qualifying + step - 1) / step
        val xyz = FloatArray(n * 3)
        val rgb = ByteArray(n * 3)
        var seen = 0
        var o = 0
        for (i in 0 until size) {
            val w = sumW[i]
            if (w < minWeight) continue
            if (seen++ % step != 0 || o >= n) continue
            val inv = 1f / w
            xyz[o * 3] = sumX[i] * inv
            xyz[o * 3 + 1] = sumY[i] * inv
            xyz[o * 3 + 2] = sumZ[i] * inv
            rgb[o * 3] = (sumR[i] * inv + 0.5f).toInt().coerceIn(0, 255).toByte()
            rgb[o * 3 + 1] = (sumG[i] * inv + 0.5f).toInt().coerceIn(0, 255).toByte()
            rgb[o * 3 + 2] = (sumB[i] * inv + 0.5f).toInt().coerceIn(0, 255).toByte()
            o++
        }
        return PointCloud(xyz, rgb)
    }

    fun clear() {
        tableKeys = LongArray(INITIAL_TABLE) { EMPTY }
        tableSlots = IntArray(INITIAL_TABLE)
        mask = INITIAL_TABLE - 1
        sumX = FloatArray(INITIAL_DATA)
        sumY = FloatArray(INITIAL_DATA)
        sumZ = FloatArray(INITIAL_DATA)
        sumR = FloatArray(INITIAL_DATA)
        sumG = FloatArray(INITIAL_DATA)
        sumB = FloatArray(INITIAL_DATA)
        sumW = FloatArray(INITIAL_DATA)
        size = 0
    }

    private fun findOrInsert(key: Long): Int {
        var idx = hash(key) and mask
        while (true) {
            val k = tableKeys[idx]
            if (k == key) return tableSlots[idx]
            if (k == EMPTY) break
            idx = (idx + 1) and mask
        }
        if (size >= maxVoxels) return -1
        if ((size + 1) * 10 > tableKeys.size * 6) {
            growTable()
            return findOrInsert(key)
        }
        ensureDataCapacity(size + 1)
        val slot = size++
        tableKeys[idx] = key
        tableSlots[idx] = slot
        return slot
    }

    private fun growTable() {
        val oldKeys = tableKeys
        val oldSlots = tableSlots
        val newCap = oldKeys.size * 2
        tableKeys = LongArray(newCap) { EMPTY }
        tableSlots = IntArray(newCap)
        mask = newCap - 1
        for (i in oldKeys.indices) {
            val k = oldKeys[i]
            if (k == EMPTY) continue
            var idx = hash(k) and mask
            while (tableKeys[idx] != EMPTY) idx = (idx + 1) and mask
            tableKeys[idx] = k
            tableSlots[idx] = oldSlots[i]
        }
    }

    private fun ensureDataCapacity(needed: Int) {
        if (needed <= sumX.size) return
        val newCap = maxOf(needed, minOf(maxVoxels, sumX.size + (sumX.size shr 1) + 1))
        sumX = sumX.copyOf(newCap)
        sumY = sumY.copyOf(newCap)
        sumZ = sumZ.copyOf(newCap)
        sumR = sumR.copyOf(newCap)
        sumG = sumG.copyOf(newCap)
        sumB = sumB.copyOf(newCap)
        sumW = sumW.copyOf(newCap)
    }

    companion object {
        private const val EMPTY = -1L
        private const val INITIAL_TABLE = 1 shl 14
        private const val INITIAL_DATA = 1 shl 13
        private const val COORD_BITS = 21
        private const val COORD_OFFSET = 1 shl (COORD_BITS - 1)
        private const val COORD_MASK = (1L shl COORD_BITS) - 1

        internal fun packKey(ix: Int, iy: Int, iz: Int): Long =
            (((ix + COORD_OFFSET).toLong() and COORD_MASK) shl (2 * COORD_BITS)) or
                (((iy + COORD_OFFSET).toLong() and COORD_MASK) shl COORD_BITS) or
                ((iz + COORD_OFFSET).toLong() and COORD_MASK)

        private fun hash(key: Long): Int {
            var h = key * -0x61c8864680b583ebL
            h = h xor (h ushr 29)
            return (h xor (h ushr 32)).toInt()
        }
    }
}
