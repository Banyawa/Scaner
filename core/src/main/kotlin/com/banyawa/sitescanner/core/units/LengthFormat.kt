package com.banyawa.sitescanner.core.units

import java.util.Locale
import kotlin.math.roundToLong

enum class LengthUnit { MILLIMETER, CENTIMETER, METER }

object LengthFormat {
    /** Shop drawings are dimensioned in millimetres, so that is the default. */
    fun format(meters: Float, unit: LengthUnit = LengthUnit.MILLIMETER): String = when (unit) {
        LengthUnit.MILLIMETER -> "${toMillimeters(meters)} mm"
        LengthUnit.CENTIMETER -> String.format(Locale.US, "%.1f cm", meters * 100f)
        LengthUnit.METER -> String.format(Locale.US, "%.3f m", meters)
    }

    fun toMillimeters(meters: Float): Long = (meters.toDouble() * 1000.0).roundToLong()
}
