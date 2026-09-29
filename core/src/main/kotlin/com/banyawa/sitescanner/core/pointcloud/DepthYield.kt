package com.banyawa.sitescanner.core.pointcloud

/**
 * How much of each raw depth frame survives filtering during a scan. Raw depth with its
 * confidence is the most accurate source, but some phones, dim rooms and plain walls give
 * almost no confident pixels; once [window] frames in a row keep less than [minFraction]
 * of their pixels the scan should switch to ARCore's smoothed depth, which covers the
 * whole image.
 */
class DepthYield(private val window: Int = 8, private val minFraction: Float = 0.03f) {
    private val kept = IntArray(window)
    private val pixels = IntArray(window)
    private var count = 0

    /** Frames reported so far. */
    @get:Synchronized
    val frames: Int get() = count

    @Synchronized
    fun add(keptPixels: Int, totalPixels: Int) {
        kept[count % window] = keptPixels
        pixels[count % window] = totalPixels
        count++
    }

    /** Share of pixels kept over the last [window] frames, or null before any frame. */
    @Synchronized
    fun fraction(): Float? {
        val n = minOf(count, window)
        if (n == 0) return null
        val total = pixels.take(n).sum()
        return if (total == 0) 0f else kept.take(n).sum().toFloat() / total
    }

    /** True once a full window of frames has come in with too little usable depth. */
    @Synchronized
    fun tooSparse(): Boolean = count >= window && (fraction() ?: 1f) < minFraction
}
