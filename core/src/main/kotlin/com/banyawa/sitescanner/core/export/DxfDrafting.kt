package com.banyawa.sitescanner.core.export

import com.banyawa.sitescanner.core.geometry.Vec2
import com.banyawa.sitescanner.core.units.LengthFormat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Drafting conventions shared by the plan and elevation drawings (millimetres). */
internal object DxfDrafting {
    fun mm(m: Float) = LengthFormat.toMillimeters(m).toString()

    /** Text angle along [dir], flipped so it never reads upside down. */
    fun readableAngle(dir: Vec2): Double {
        var angle = Math.toDegrees(atan2(dir.y.toDouble(), dir.x.toDouble()))
        if (angle > 90.0) angle -= 180.0
        if (angle <= -90.0) angle += 180.0
        return angle
    }

    /**
     * Aligned dimension from [a] to [b] (mm), its line [offset] mm away on the [side] (unit)
     * of the measured points: extension lines, 45° ticks and the text above the line.
     */
    fun dimension(
        dxf: DxfDocument,
        layer: String,
        a: Vec2,
        b: Vec2,
        side: Vec2,
        offset: Float,
        textHeight: Float,
        text: String = LengthFormat.toMillimeters(a.distanceTo(b) / 1000f).toString(),
    ) {
        if (a.distanceTo(b) <= 0f) return
        val dir = (b - a).normalized()
        val da = a + side * offset
        val db = b + side * offset
        fun line(p: Vec2, q: Vec2) = dxf.line(layer, p.x.toDouble(), p.y.toDouble(), q.x.toDouble(), q.y.toDouble())

        // Extension lines: small gap at the object, overshoot past the dimension line.
        line(a + side * EXTENSION_GAP, a + side * (offset + EXTENSION_OVER))
        line(b + side * EXTENSION_GAP, b + side * (offset + EXTENSION_OVER))
        line(da, db)
        // Architectural 45° ticks.
        val tick = (dir + side).normalized() * (textHeight * 0.6f)
        line(da - tick, da + tick)
        line(db - tick, db + tick)

        // Text sits above the dimension line as it reads, whichever side that is.
        val angle = readableAngle(dir)
        val rad = Math.toRadians(angle)
        val up = Vec2(-sin(rad).toFloat(), cos(rad).toFloat())
        val at = Vec2.lerp(da, db, 0.5f) + up * (textHeight * 0.3f)
        dxf.text(layer, at.x.toDouble(), at.y.toDouble(), textHeight.toDouble(), text, angle, DxfDocument.HAlign.CENTER)
    }

    private const val EXTENSION_GAP = 50f
    private const val EXTENSION_OVER = 80f
}
