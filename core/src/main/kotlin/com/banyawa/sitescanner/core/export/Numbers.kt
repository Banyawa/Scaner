package com.banyawa.sitescanner.core.export

import kotlin.math.abs
import kotlin.math.roundToLong

/** Locale-independent fixed-point formatting, much faster than String.format for millions of values. */
internal object Numbers {
    private val POW10 = LongArray(10).also { p -> p[0] = 1; for (i in 1 until p.size) p[i] = p[i - 1] * 10 }

    fun appendFixed(sb: StringBuilder, value: Double, decimals: Int): StringBuilder {
        require(decimals in 0 until POW10.size)
        if (value.isNaN() || value.isInfinite()) return sb.append('0')
        val scale = POW10[decimals]
        val scaled = (abs(value) * scale).roundToLong()
        if (value < 0 && scaled != 0L) sb.append('-')
        sb.append(scaled / scale)
        if (decimals > 0) {
            sb.append('.')
            val frac = (scaled % scale).toString()
            for (i in frac.length until decimals) sb.append('0')
            sb.append(frac)
        }
        return sb
    }

    fun fixed(value: Double, decimals: Int): String = appendFixed(StringBuilder(), value, decimals).toString()
}
