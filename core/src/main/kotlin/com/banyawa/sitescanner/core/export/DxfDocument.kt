package com.banyawa.sitescanner.core.export

/**
 * Minimal ASCII DXF (AutoCAD R12 / AC1009) writer: layers, LINE, ARC, CIRCLE, TEXT, POINT and POLYLINE.
 * R12 is the most widely readable DXF flavour (AutoCAD, BricsCAD, ZWCAD, LibreCAD,
 * QCAD, SketchUp, Revit). Non-ASCII text (e.g. Thai labels) is written as \U+XXXX escapes.
 */
class DxfDocument {
    enum class HAlign(val code: Int) { LEFT(0), CENTER(1), RIGHT(2) }

    private data class Layer(val name: String, val color: Int)

    private val layers = LinkedHashMap<String, Layer>()
    private val entities = StringBuilder()
    private var minX = Double.POSITIVE_INFINITY
    private var minY = Double.POSITIVE_INFINITY
    private var minZ = Double.POSITIVE_INFINITY
    private var maxX = Double.NEGATIVE_INFINITY
    private var maxY = Double.NEGATIVE_INFINITY
    private var maxZ = Double.NEGATIVE_INFINITY

    var entityCount = 0
        private set

    /** [color] is an AutoCAD Color Index (1 red, 2 yellow, 3 green, 4 cyan, 5 blue, 6 magenta, 7 white/black, 8 grey). */
    fun layer(name: String, color: Int): DxfDocument {
        layers[name] = Layer(sanitizeName(name), color)
        return this
    }

    fun line(layer: String, x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double) {
        ensureLayer(layer)
        entity("LINE", layer)
        pair(10, x1); pair(20, y1); pair(30, z1)
        pair(11, x2); pair(21, y2); pair(31, z2)
        extend(x1, y1, z1)
        extend(x2, y2, z2)
    }

    fun line(layer: String, x1: Double, y1: Double, x2: Double, y2: Double) = line(layer, x1, y1, 0.0, x2, y2, 0.0)

    /** Arc around ([cx], [cy]) running counter-clockwise from [startDeg] to [endDeg]. */
    fun arc(layer: String, cx: Double, cy: Double, radius: Double, startDeg: Double, endDeg: Double) {
        ensureLayer(layer)
        entity("ARC", layer)
        pair(10, cx); pair(20, cy); pair(30, 0.0)
        pair(40, radius)
        pair(50, startDeg)
        pair(51, endDeg)
        extend(cx - radius, cy - radius, 0.0)
        extend(cx + radius, cy + radius, 0.0)
    }

    fun circle(layer: String, cx: Double, cy: Double, radius: Double) {
        ensureLayer(layer)
        entity("CIRCLE", layer)
        pair(10, cx); pair(20, cy); pair(30, 0.0)
        pair(40, radius)
        extend(cx - radius, cy - radius, 0.0)
        extend(cx + radius, cy + radius, 0.0)
    }

    fun point(layer: String, x: Double, y: Double, z: Double = 0.0) {
        ensureLayer(layer)
        entity("POINT", layer)
        pair(10, x); pair(20, y); pair(30, z)
        extend(x, y, z)
    }

    /**
     * 2D polyline through [xy] (x0, y0, x1, y1, …), [closed] back to its first point: the
     * R12 POLYLINE / VERTEX / SEQEND form, which every reader of AC1009 files understands
     * (LWPOLYLINE only came with R14). Fewer than two points draws nothing.
     */
    fun polyline(layer: String, xy: DoubleArray, closed: Boolean = false) {
        require(xy.size % 2 == 0) { "x, y pairs" }
        if (xy.size < 4) return
        ensureLayer(layer)
        entity("POLYLINE", layer)
        val name = layers.getValue(layer).name
        pair(66, 1) // Vertices follow.
        pair(10, 0.0); pair(20, 0.0); pair(30, 0.0)
        pair(70, if (closed) 1 else 0)
        for (i in 0 until xy.size / 2) {
            pair(0, "VERTEX")
            pair(8, name)
            pair(10, xy[i * 2]); pair(20, xy[i * 2 + 1]); pair(30, 0.0)
            extend(xy[i * 2], xy[i * 2 + 1], 0.0)
        }
        pair(0, "SEQEND")
        pair(8, name)
    }

    /**
     * Text anchored at ([x], [y]). With CENTER/RIGHT alignment the anchor is the
     * baseline's centre/right end.
     */
    fun text(
        layer: String,
        x: Double,
        y: Double,
        height: Double,
        text: String,
        rotationDeg: Double = 0.0,
        align: HAlign = HAlign.LEFT,
    ) {
        ensureLayer(layer)
        entity("TEXT", layer)
        pair(10, x); pair(20, y); pair(30, 0.0)
        pair(40, height)
        pair(1, encodeText(text))
        if (rotationDeg != 0.0) pair(50, rotationDeg)
        if (align != HAlign.LEFT) {
            pair(72, align.code)
            pair(11, x); pair(21, y); pair(31, 0.0)
        }
        extend(x, y, 0.0)
    }

    fun write(out: Appendable) {
        val sb = StringBuilder()
        fun p(code: Int, value: String) {
            sb.append(code.toString().padStart(3)).append('\n').append(value).append('\n')
        }
        fun pn(code: Int, value: Double) = p(code, Numbers.fixed(value, 6))

        val hasExtents = minX.isFinite()
        // HEADER
        p(0, "SECTION"); p(2, "HEADER")
        p(9, "\$ACADVER"); p(1, "AC1009")
        p(9, "\$INSUNITS"); p(70, "4") // millimetres
        p(9, "\$EXTMIN"); pn(10, if (hasExtents) minX else 0.0); pn(20, if (hasExtents) minY else 0.0); pn(30, if (hasExtents) minZ else 0.0)
        p(9, "\$EXTMAX"); pn(10, if (hasExtents) maxX else 0.0); pn(20, if (hasExtents) maxY else 0.0); pn(30, if (hasExtents) maxZ else 0.0)
        p(0, "ENDSEC")

        // TABLES
        p(0, "SECTION"); p(2, "TABLES")
        p(0, "TABLE"); p(2, "LTYPE"); p(70, "1")
        p(0, "LTYPE"); p(2, "CONTINUOUS"); p(70, "0"); p(3, "Solid line"); p(72, "65"); p(73, "0"); pn(40, 0.0)
        p(0, "ENDTAB")
        val allLayers = listOf(Layer("0", 7)) + layers.values.filter { it.name != "0" }
        p(0, "TABLE"); p(2, "LAYER"); p(70, allLayers.size.toString())
        for (l in allLayers) {
            p(0, "LAYER"); p(2, l.name); p(70, "0"); p(62, l.color.toString()); p(6, "CONTINUOUS")
        }
        p(0, "ENDTAB")
        p(0, "TABLE"); p(2, "STYLE"); p(70, "1")
        p(0, "STYLE"); p(2, "STANDARD"); p(70, "0"); pn(40, 0.0); pn(41, 1.0); pn(50, 0.0); p(71, "0"); pn(42, 2.5); p(3, "txt"); p(4, "")
        p(0, "ENDTAB")
        p(0, "ENDSEC")

        out.append(sb)
        sb.setLength(0)

        // ENTITIES
        p(0, "SECTION"); p(2, "ENTITIES")
        out.append(sb)
        out.append(entities)
        sb.setLength(0)
        p(0, "ENDSEC")
        p(0, "EOF")
        out.append(sb)
    }

    override fun toString(): String = StringBuilder().also { write(it) }.toString()

    private fun ensureLayer(name: String) {
        if (name !in layers) layer(name, 7)
    }

    private fun entity(type: String, layer: String) {
        entityCount++
        pair(0, type)
        pair(8, layers.getValue(layer).name)
    }

    private fun pair(code: Int, value: String) {
        entities.append(code.toString().padStart(3)).append('\n').append(value).append('\n')
    }

    private fun pair(code: Int, value: Int) = pair(code, value.toString())

    private fun pair(code: Int, value: Double) {
        entities.append(code.toString().padStart(3)).append('\n')
        Numbers.appendFixed(entities, value, 6).append('\n')
    }

    private fun extend(x: Double, y: Double, z: Double) {
        if (x < minX) minX = x
        if (y < minY) minY = y
        if (z < minZ) minZ = z
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
        if (z > maxZ) maxZ = z
    }

    companion object {
        /** DXF strings are single-line; non-ASCII becomes AutoCAD's \U+XXXX escape. */
        fun encodeText(text: String): String {
            val sb = StringBuilder()
            for (ch in text) {
                when {
                    ch == '\n' || ch == '\r' -> sb.append(' ')
                    ch.code in 32..126 -> sb.append(ch)
                    else -> sb.append("\\U+").append(ch.code.toString(16).uppercase().padStart(4, '0'))
                }
            }
            return sb.toString()
        }

        private fun sanitizeName(name: String) =
            name.map { if (it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_') it.uppercaseChar() else '_' }
                .joinToString("")
                .ifEmpty { "0" }
    }
}
