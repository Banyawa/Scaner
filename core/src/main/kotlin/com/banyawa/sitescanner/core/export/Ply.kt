package com.banyawa.sitescanner.core.export

import com.banyawa.sitescanner.core.floorplan.SiteAlignment
import com.banyawa.sitescanner.core.pointcloud.PointCloud
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PLY point clouds (x, y, z float + red, green, blue uchar). Binary little-endian is
 * written; ascii and both binary flavours are read. Opens in CloudCompare, MeshLab,
 * Blender and Autodesk ReCap.
 */
object Ply {
    fun write(cloud: PointCloud, file: File, alignment: SiteAlignment? = null) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { write(cloud, it, alignment) }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Could not write ${file.path}")
        }
    }

    /** Writes [cloud]; when [alignment] is given, points are converted to the Z-up site frame. */
    fun write(cloud: PointCloud, out: OutputStream, alignment: SiteAlignment? = null) {
        val bos = BufferedOutputStream(out, 1 shl 16)
        val header = buildString {
            append("ply\n")
            append("format binary_little_endian 1.0\n")
            append("comment Site Scanner\n")
            append(if (alignment != null) "comment frame site Z-up metres\n" else "comment frame arcore Y-up metres\n")
            append("element vertex ${cloud.size}\n")
            append("property float x\nproperty float y\nproperty float z\n")
            append("property uchar red\nproperty uchar green\nproperty uchar blue\n")
            append("end_header\n")
        }
        bos.write(header.toByteArray(Charsets.US_ASCII))
        val chunk = ByteBuffer.allocate(RECORD * 4096).order(ByteOrder.LITTLE_ENDIAN)
        val site = FloatArray(3)
        for (i in 0 until cloud.size) {
            if (chunk.remaining() < RECORD) {
                bos.write(chunk.array(), 0, chunk.position())
                chunk.clear()
            }
            val x = cloud.xyz[i * 3]
            val y = cloud.xyz[i * 3 + 1]
            val z = cloud.xyz[i * 3 + 2]
            if (alignment != null) {
                alignment.toSite(x, y, z, site, 0)
                chunk.putFloat(site[0]).putFloat(site[1]).putFloat(site[2])
            } else {
                chunk.putFloat(x).putFloat(y).putFloat(z)
            }
            chunk.put(cloud.rgb[i * 3]).put(cloud.rgb[i * 3 + 1]).put(cloud.rgb[i * 3 + 2])
        }
        bos.write(chunk.array(), 0, chunk.position())
        bos.flush()
    }

    fun read(file: File): PointCloud = file.inputStream().use { read(it) }

    fun read(input: InputStream): PointCloud {
        val stream = BufferedInputStream(input, 1 shl 16)
        val header = parseHeader(stream)
        val vertexIndex = header.elements.indexOfFirst { it.name == "vertex" }
        if (vertexIndex < 0) return PointCloud.EMPTY
        val reader: RecordReader = when (header.format) {
            "ascii" -> AsciiReader(stream)
            "binary_little_endian" -> BinaryReader(stream, ByteOrder.LITTLE_ENDIAN)
            "binary_big_endian" -> BinaryReader(stream, ByteOrder.BIG_ENDIAN)
            else -> throw IOException("Unsupported PLY format ${header.format}")
        }
        for (e in header.elements.subList(0, vertexIndex)) {
            repeat(e.count) { reader.skipRecord(e) }
        }
        val vertex = header.elements[vertexIndex]
        val props = vertex.properties
        val ix = props.indexOfFirst { it.name == "x" }
        val iy = props.indexOfFirst { it.name == "y" }
        val iz = props.indexOfFirst { it.name == "z" }
        if (ix < 0 || iy < 0 || iz < 0) throw IOException("PLY vertex has no x/y/z")
        val ir = props.indexOfFirst { it.name in RED }
        val ig = props.indexOfFirst { it.name in GREEN }
        val ib = props.indexOfFirst { it.name in BLUE }

        val n = vertex.count
        val xyz = FloatArray(n * 3)
        val rgb = ByteArray(n * 3)
        val values = DoubleArray(props.size)
        for (i in 0 until n) {
            reader.readRecord(vertex, values)
            xyz[i * 3] = values[ix].toFloat()
            xyz[i * 3 + 1] = values[iy].toFloat()
            xyz[i * 3 + 2] = values[iz].toFloat()
            rgb[i * 3] = colorByte(values, props, ir)
            rgb[i * 3 + 1] = colorByte(values, props, ig)
            rgb[i * 3 + 2] = colorByte(values, props, ib)
        }
        return PointCloud(xyz, rgb)
    }

    private fun colorByte(values: DoubleArray, props: List<Property>, index: Int): Byte {
        if (index < 0) return 0xB0.toByte()
        val v = values[index]
        val scaled = if (props[index].type == "float" || props[index].type == "double") v * 255.0 else v
        return scaled.toInt().coerceIn(0, 255).toByte()
    }

    private const val RECORD = 15
    private val RED = setOf("red", "r", "diffuse_red")
    private val GREEN = setOf("green", "g", "diffuse_green")
    private val BLUE = setOf("blue", "b", "diffuse_blue")

    // --- header -----------------------------------------------------------------------

    private class Property(val name: String, val type: String, val listCountType: String? = null)
    private class Element(val name: String, val count: Int, val properties: MutableList<Property> = ArrayList()) {
        /** Byte size of one binary record, or -1 when it contains list properties. */
        val fixedRecordSize: Int by lazy {
            if (properties.any { it.listCountType != null }) -1 else properties.sumOf { sizeOf(it.type) }
        }
    }
    private class Header(val format: String, val elements: List<Element>)

    private fun parseHeader(stream: InputStream): Header {
        if (readLine(stream) != "ply") throw IOException("Not a PLY file")
        var format = ""
        val elements = ArrayList<Element>()
        while (true) {
            val line = readLine(stream)
            val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            when (parts[0]) {
                "format" -> format = parts[1]
                "element" -> elements.add(Element(parts[1], parts[2].toInt()))
                "property" -> {
                    val element = elements.lastOrNull() ?: throw IOException("property before element")
                    if (parts[1] == "list") {
                        element.properties.add(Property(parts[4], normalizeType(parts[3]), normalizeType(parts[2])))
                    } else {
                        element.properties.add(Property(parts[2], normalizeType(parts[1])))
                    }
                }
                "end_header" -> return Header(format, elements)
            }
        }
    }

    private fun readLine(stream: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = stream.read()
            if (c < 0) throw EOFException("Unexpected end of PLY header")
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString().trim()
    }

    private fun normalizeType(t: String) = when (t) {
        "int8", "char" -> "char"
        "uint8", "uchar" -> "uchar"
        "int16", "short" -> "short"
        "uint16", "ushort" -> "ushort"
        "int32", "int" -> "int"
        "uint32", "uint" -> "uint"
        "float32", "float" -> "float"
        "float64", "double" -> "double"
        else -> throw IOException("Unknown PLY type $t")
    }

    private fun sizeOf(type: String) = when (type) {
        "char", "uchar" -> 1
        "short", "ushort" -> 2
        "int", "uint", "float" -> 4
        "double" -> 8
        else -> throw IOException("Unknown PLY type $type")
    }

    // --- body readers -------------------------------------------------------------------

    private interface RecordReader {
        /** Reads one record; list properties yield their element count. */
        fun readRecord(element: Element, out: DoubleArray)
        fun skipRecord(element: Element)
    }

    private class BinaryReader(private val stream: InputStream, private val order: ByteOrder) : RecordReader {
        private val buf = ByteBuffer.allocate(8).order(order)
        private var record = ByteBuffer.allocate(64).order(order)

        private fun readFully(target: ByteArray, size: Int) {
            var read = 0
            while (read < size) {
                val r = stream.read(target, read, size - read)
                if (r < 0) throw EOFException("Unexpected end of PLY data")
                read += r
            }
        }

        private fun decode(b: ByteBuffer, offset: Int, type: String): Double = when (type) {
            "char" -> b.get(offset).toDouble()
            "uchar" -> (b.get(offset).toInt() and 0xFF).toDouble()
            "short" -> b.getShort(offset).toDouble()
            "ushort" -> (b.getShort(offset).toInt() and 0xFFFF).toDouble()
            "int" -> b.getInt(offset).toDouble()
            "uint" -> (b.getInt(offset).toLong() and 0xFFFFFFFFL).toDouble()
            "float" -> b.getFloat(offset).toDouble()
            else -> b.getDouble(offset)
        }

        private fun readScalar(type: String): Double {
            readFully(buf.array(), sizeOf(type))
            return decode(buf, 0, type)
        }

        override fun readRecord(element: Element, out: DoubleArray) {
            val size = element.fixedRecordSize
            if (size >= 0) {
                // Fast path: one read per record.
                if (record.capacity() < size) record = ByteBuffer.allocate(size).order(order)
                readFully(record.array(), size)
                var offset = 0
                for ((i, p) in element.properties.withIndex()) {
                    out[i] = decode(record, offset, p.type)
                    offset += sizeOf(p.type)
                }
                return
            }
            for ((i, p) in element.properties.withIndex()) {
                if (p.listCountType != null) {
                    val count = readScalar(p.listCountType).toInt()
                    repeat(count) { readScalar(p.type) }
                    out[i] = count.toDouble()
                } else {
                    out[i] = readScalar(p.type)
                }
            }
        }

        override fun skipRecord(element: Element) = readRecord(element, DoubleArray(element.properties.size))
    }

    private class AsciiReader(private val stream: InputStream) : RecordReader {
        private val tokens = ArrayDeque<String>()

        private fun next(): String {
            while (tokens.isEmpty()) {
                val line = readLine(stream)
                line.split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { tokens.addLast(it) }
            }
            return tokens.removeFirst()
        }

        override fun readRecord(element: Element, out: DoubleArray) {
            for ((i, p) in element.properties.withIndex()) {
                if (p.listCountType != null) {
                    val count = next().toDouble().toInt()
                    repeat(count) { next() }
                    out[i] = count.toDouble()
                } else {
                    out[i] = next().toDouble()
                }
            }
        }

        override fun skipRecord(element: Element) = readRecord(element, DoubleArray(element.properties.size))
    }
}
