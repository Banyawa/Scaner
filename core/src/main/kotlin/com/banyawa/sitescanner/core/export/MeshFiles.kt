package com.banyawa.sitescanner.core.export

import com.banyawa.sitescanner.core.capture.ImageEncoder
import com.banyawa.sitescanner.core.floorplan.SiteAlignment
import com.banyawa.sitescanner.core.mesh.TexturedMesh
import com.banyawa.sitescanner.core.mesh.TriangleMesh
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/** Mesh coordinates in the site frame shared by all exports: walls square to the axes, floor at 0. */
object MeshFrames {
    /** Site frame, Z up (as the point cloud PLY / PTS exports). */
    fun siteZUp(mesh: TriangleMesh, alignment: SiteAlignment): TriangleMesh =
        mesh.mapPositions { x, y, z, out, o -> alignment.toSite(x, y, z, out, o) }

    /** Site frame, Y up (glTF and OBJ convention): site (x, y, z) → (x, z, -y), as the walls OBJ. */
    fun siteYUp(mesh: TriangleMesh, alignment: SiteAlignment): TriangleMesh {
        val site = FloatArray(3)
        return mesh.mapPositions { x, y, z, out, o ->
            alignment.toSite(x, y, z, site, 0)
            out[o] = site[0]
            out[o + 1] = site[2]
            out[o + 2] = -site[1]
        }
    }
}

/** A mesh read from PLY with its texture coordinates, when the file had them. */
class MeshPlyData(val mesh: TriangleMesh, val uv: FloatArray?)

/**
 * Binary PLY meshes: vertices with x, y, z float, red, green, blue uchar and, for textured
 * meshes, s, t float (the texture coordinates, origin top left); faces as uchar-counted
 * int index lists. Used to store scan meshes and to export them (MeshLab, CloudCompare,
 * Blender).
 */
object MeshPly {
    fun write(mesh: TriangleMesh, file: File, comment: String = "frame arcore Y-up metres", uv: FloatArray? = null) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { write(mesh, it, comment, uv) }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Could not write ${file.path}")
        }
    }

    fun write(mesh: TriangleMesh, out: OutputStream, comment: String = "frame arcore Y-up metres", uv: FloatArray? = null) {
        require(uv == null || uv.size == mesh.vertexCount * 2) { "two texture coordinates per vertex" }
        val bos = BufferedOutputStream(out, 1 shl 16)
        val header = buildString {
            append("ply\nformat binary_little_endian 1.0\ncomment Site Scanner\ncomment $comment\n")
            append("element vertex ${mesh.vertexCount}\n")
            append("property float x\nproperty float y\nproperty float z\n")
            append("property uchar red\nproperty uchar green\nproperty uchar blue\n")
            if (uv != null) append("property float s\nproperty float t\n")
            append("element face ${mesh.triangleCount}\n")
            append("property list uchar int vertex_indices\n")
            append("end_header\n")
        }
        bos.write(header.toByteArray(Charsets.US_ASCII))
        val buf = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
        fun room(bytes: Int) {
            if (buf.remaining() < bytes) {
                bos.write(buf.array(), 0, buf.position())
                buf.clear()
            }
        }
        for (v in 0 until mesh.vertexCount) {
            room(23)
            buf.putFloat(mesh.positions[v * 3]).putFloat(mesh.positions[v * 3 + 1]).putFloat(mesh.positions[v * 3 + 2])
            buf.put(mesh.colors[v * 3]).put(mesh.colors[v * 3 + 1]).put(mesh.colors[v * 3 + 2])
            if (uv != null) buf.putFloat(uv[v * 2]).putFloat(uv[v * 2 + 1])
        }
        for (t in 0 until mesh.triangleCount) {
            room(13)
            buf.put(3.toByte()).putInt(mesh.indices[t * 3]).putInt(mesh.indices[t * 3 + 1]).putInt(mesh.indices[t * 3 + 2])
        }
        bos.write(buf.array(), 0, buf.position())
        bos.flush()
    }

    fun read(file: File): TriangleMesh = file.inputStream().use { read(it) }

    /** Reads meshes as [write] writes them; texture coordinates, if any, are dropped. */
    fun read(input: InputStream): TriangleMesh = readTextured(input).mesh

    fun readTextured(file: File): MeshPlyData = file.inputStream().use { readTextured(it) }

    /** Reads meshes as [write] writes them, with their texture coordinates when present. */
    fun readTextured(input: InputStream): MeshPlyData {
        val stream = DataInputStream(BufferedInputStream(input, 1 shl 16))
        var vertices = -1
        var faces = -1
        var textured = false
        while (true) {
            val line = readLine(stream)
            when {
                line == "end_header" -> break
                line.startsWith("format ") && line != "format binary_little_endian 1.0" -> throw IOException("Unsupported mesh PLY: $line")
                line.startsWith("element vertex ") -> vertices = line.substringAfterLast(' ').toInt()
                line.startsWith("element face ") -> faces = line.substringAfterLast(' ').toInt()
                line == "property float s" -> textured = true
            }
        }
        if (vertices < 0 || faces < 0) throw IOException("PLY has no mesh")
        val positions = FloatArray(vertices * 3)
        val colors = ByteArray(vertices * 3)
        val uv = if (textured) FloatArray(vertices * 2) else null
        val stride = if (textured) 23 else 15
        val record = ByteArray(23)
        val rb = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
        for (v in 0 until vertices) {
            stream.readFully(record, 0, stride)
            positions[v * 3] = rb.getFloat(0)
            positions[v * 3 + 1] = rb.getFloat(4)
            positions[v * 3 + 2] = rb.getFloat(8)
            colors[v * 3] = record[12]
            colors[v * 3 + 1] = record[13]
            colors[v * 3 + 2] = record[14]
            if (uv != null) {
                uv[v * 2] = rb.getFloat(15)
                uv[v * 2 + 1] = rb.getFloat(19)
            }
        }
        val indices = IntArray(faces * 3)
        for (t in 0 until faces) {
            stream.readFully(record, 0, 13)
            if (record[0].toInt() != 3) throw IOException("Only triangles are supported")
            indices[t * 3] = rb.getInt(1)
            indices[t * 3 + 1] = rb.getInt(5)
            indices[t * 3 + 2] = rb.getInt(9)
        }
        return MeshPlyData(TriangleMesh(positions, colors, indices), uv)
    }

    private fun readLine(stream: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = stream.read()
            if (c < 0) throw IOException("Unexpected end of PLY header")
            if (c == '\n'.code) return sb.toString().trim()
            sb.append(c.toChar())
        }
    }
}

/**
 * Binary glTF 2.0 (.glb) with vertex colours: opens in Windows 3D Viewer, Blender,
 * SketchUp (with an importer), web viewers and AR Quick Look converters. Positions are
 * written as given (glTF is Y up, metres); colours are converted from the camera's sRGB
 * to the linear values glTF expects. The material is unlit where supported, since the
 * colours already carry the room's lighting.
 */
object Glb {
    fun write(mesh: TriangleMesh, out: OutputStream, name: String = "Site Scanner mesh") {
        val n = mesh.vertexCount
        val normals = mesh.normals()
        val posBytes = n * 12
        val normBytes = n * 12
        val colBytes = n * 12
        val idxBytes = mesh.indices.size * 4
        val bin = ByteBuffer.allocate(posBytes + normBytes + colBytes + idxBytes).order(ByteOrder.LITTLE_ENDIAN)
        val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until n * 3) {
            val p = mesh.positions[i]
            bin.putFloat(p)
            if (p < min[i % 3]) min[i % 3] = p
            if (p > max[i % 3]) max[i % 3] = p
        }
        for (v in normals) bin.putFloat(v)
        for (c in mesh.colors) bin.putFloat(SRGB_TO_LINEAR[c.toInt() and 0xFF])
        for (i in mesh.indices) bin.putInt(i)
        if (n == 0) min.fill(0f).also { max.fill(0f) }

        // Bounds must match the stored floats exactly: the shortest round-tripping decimal does.
        fun vec(a: FloatArray) = a.joinToString(",", "[", "]") { it.toString() }
        val json = """
            {"asset":{"version":"2.0","generator":"Site Scanner"},
            "extensionsUsed":["KHR_materials_unlit"],
            "scene":0,"scenes":[{"nodes":[0]}],
            "nodes":[{"mesh":0,"name":${quote(name)}}],
            "materials":[{"name":"scan","doubleSided":true,
              "pbrMetallicRoughness":{"baseColorFactor":[1,1,1,1],"metallicFactor":0,"roughnessFactor":1},
              "extensions":{"KHR_materials_unlit":{}}}],
            "meshes":[{"name":${quote(name)},"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"COLOR_0":2},"indices":3,"material":0,"mode":4}]}],
            "buffers":[{"byteLength":${bin.capacity()}}],
            "bufferViews":[
              {"buffer":0,"byteOffset":0,"byteLength":$posBytes,"target":34962},
              {"buffer":0,"byteOffset":$posBytes,"byteLength":$normBytes,"target":34962},
              {"buffer":0,"byteOffset":${posBytes + normBytes},"byteLength":$colBytes,"target":34962},
              {"buffer":0,"byteOffset":${posBytes + normBytes + colBytes},"byteLength":$idxBytes,"target":34963}],
            "accessors":[
              {"bufferView":0,"componentType":5126,"count":$n,"type":"VEC3","min":${vec(min)},"max":${vec(max)}},
              {"bufferView":1,"componentType":5126,"count":$n,"type":"VEC3"},
              {"bufferView":2,"componentType":5126,"count":$n,"type":"VEC3"},
              {"bufferView":3,"componentType":5125,"count":${mesh.indices.size},"type":"SCALAR"}]}
        """.trimIndent().replace("\n", "")
        val jsonBytes = pad(json.toByteArray(Charsets.UTF_8), ' '.code.toByte())
        val binBytes = pad(bin.array(), 0)

        val header = ByteBuffer.allocate(12 + 8 + 8).order(ByteOrder.LITTLE_ENDIAN)
        val total = 12 + 8 + jsonBytes.size + 8 + binBytes.size
        val bos = BufferedOutputStream(out, 1 shl 16)
        header.putInt(MAGIC).putInt(2).putInt(total)
        header.putInt(jsonBytes.size).putInt(CHUNK_JSON)
        bos.write(header.array(), 0, 20)
        bos.write(jsonBytes)
        header.clear()
        header.putInt(binBytes.size).putInt(CHUNK_BIN)
        bos.write(header.array(), 0, 8)
        bos.write(binBytes)
        bos.flush()
    }

    /**
     * The photo-textured model: positions, normals and texture coordinates, with the atlas
     * embedded as a JPEG ([encoder] at [quality]) and an unlit material showing it as
     * photographed.
     *
     * The vertex colours are kept as the application-specific attribute `_COLOR_0`, not
     * `COLOR_0`: glTF multiplies `COLOR_0` into the base colour texture, which would darken
     * every photo by its own blurred copy. Tools that read custom attributes (Blender) still
     * get them.
     */
    fun write(textured: TexturedMesh, out: OutputStream, name: String, encoder: ImageEncoder, quality: Int = 85) {
        val mesh = textured.mesh
        val n = mesh.vertexCount
        val jpeg = encoder.encodeJpeg(textured.atlas, quality)
        val normals = mesh.normals()
        val posBytes = n * 12
        val normBytes = n * 12
        val uvBytes = n * 8
        val colBytes = n * 12
        val idxBytes = mesh.indices.size * 4
        val normOffset = posBytes
        val uvOffset = normOffset + normBytes
        val colOffset = uvOffset + uvBytes
        val idxOffset = colOffset + colBytes
        val imageOffset = idxOffset + idxBytes
        val binLength = imageOffset + jpeg.size
        val bin = ByteBuffer.allocate(binLength).order(ByteOrder.LITTLE_ENDIAN)
        val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until n * 3) {
            val p = mesh.positions[i]
            bin.putFloat(p)
            if (p < min[i % 3]) min[i % 3] = p
            if (p > max[i % 3]) max[i % 3] = p
        }
        for (v in 0 until n) {
            // Normals must be unit length; a vertex of degenerate triangles only has none.
            val x = normals[v * 3]
            val y = normals[v * 3 + 1]
            val z = normals[v * 3 + 2]
            if (x * x + y * y + z * z > 0.5f) bin.putFloat(x).putFloat(y).putFloat(z) else bin.putFloat(0f).putFloat(1f).putFloat(0f)
        }
        for (i in 0 until n * 2) bin.putFloat(textured.uv[i].coerceIn(0f, 1f))
        for (c in mesh.colors) bin.putFloat(SRGB_TO_LINEAR[c.toInt() and 0xFF])
        for (i in mesh.indices) bin.putInt(i)
        bin.put(jpeg)
        if (n == 0) min.fill(0f).also { max.fill(0f) }

        fun vec(a: FloatArray) = a.joinToString(",", "[", "]") { it.toString() }
        val json = """
            {"asset":{"version":"2.0","generator":"Site Scanner"},
            "extensionsUsed":["KHR_materials_unlit"],
            "scene":0,"scenes":[{"nodes":[0]}],
            "nodes":[{"mesh":0,"name":${quote(name)}}],
            "materials":[{"name":"scan","doubleSided":true,
              "pbrMetallicRoughness":{"baseColorTexture":{"index":0},"metallicFactor":0,"roughnessFactor":1},
              "extensions":{"KHR_materials_unlit":{}}}],
            "meshes":[{"name":${quote(name)},"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2,"_COLOR_0":3},"indices":4,"material":0,"mode":4}]}],
            "textures":[{"sampler":0,"source":0}],
            "samplers":[{"magFilter":9729,"minFilter":9987,"wrapS":33071,"wrapT":33071}],
            "images":[{"bufferView":5,"mimeType":"image/jpeg"}],
            "buffers":[{"byteLength":$binLength}],
            "bufferViews":[
              {"buffer":0,"byteOffset":0,"byteLength":$posBytes,"target":34962},
              {"buffer":0,"byteOffset":$normOffset,"byteLength":$normBytes,"target":34962},
              {"buffer":0,"byteOffset":$uvOffset,"byteLength":$uvBytes,"target":34962},
              {"buffer":0,"byteOffset":$colOffset,"byteLength":$colBytes,"target":34962},
              {"buffer":0,"byteOffset":$idxOffset,"byteLength":$idxBytes,"target":34963},
              {"buffer":0,"byteOffset":$imageOffset,"byteLength":${jpeg.size}}],
            "accessors":[
              {"bufferView":0,"componentType":5126,"count":$n,"type":"VEC3","min":${vec(min)},"max":${vec(max)}},
              {"bufferView":1,"componentType":5126,"count":$n,"type":"VEC3"},
              {"bufferView":2,"componentType":5126,"count":$n,"type":"VEC2"},
              {"bufferView":3,"componentType":5126,"count":$n,"type":"VEC3"},
              {"bufferView":4,"componentType":5125,"count":${mesh.indices.size},"type":"SCALAR"}]}
        """.trimIndent().replace("\n", "")
        writeChunks(pad(json.toByteArray(Charsets.UTF_8), ' '.code.toByte()), pad(bin.array(), 0), out)
    }

    private fun writeChunks(jsonBytes: ByteArray, binBytes: ByteArray, out: OutputStream) {
        val header = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        val bos = BufferedOutputStream(out, 1 shl 16)
        header.putInt(MAGIC).putInt(2).putInt(12 + 8 + jsonBytes.size + 8 + binBytes.size)
        header.putInt(jsonBytes.size).putInt(CHUNK_JSON)
        bos.write(header.array(), 0, 20)
        bos.write(jsonBytes)
        header.clear()
        header.putInt(binBytes.size).putInt(CHUNK_BIN)
        bos.write(header.array(), 0, 8)
        bos.write(binBytes)
        bos.flush()
    }

    private fun pad(bytes: ByteArray, fill: Byte): ByteArray {
        val padded = (bytes.size + 3) / 4 * 4
        return if (padded == bytes.size) bytes else bytes.copyOf(padded).also { it.fill(fill, bytes.size, padded) }
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    const val MAGIC = 0x46546C67
    const val CHUNK_JSON = 0x4E4F534A
    const val CHUNK_BIN = 0x004E4942

    private val SRGB_TO_LINEAR = FloatArray(256) {
        val c = it / 255.0
        (if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)).toFloat()
    }
}

/**
 * Wavefront OBJ with a colour after each vertex ("v x y z r g b", read by MeshLab, Blender
 * and CloudCompare). Metres, as given (the site frame is Y up for OBJ).
 */
object MeshObj {
    fun write(mesh: TriangleMesh, out: Writer) {
        val sb = StringBuilder(1 shl 16)
        sb.append("# Site Scanner - colour mesh of the scan\n# units: metres, Y up\n")
        for (v in 0 until mesh.vertexCount) {
            sb.append("v ")
            for (a in 0 until 3) Numbers.appendFixed(sb, mesh.positions[v * 3 + a].toDouble(), 4).append(' ')
            for (a in 0 until 3) {
                Numbers.appendFixed(sb, (mesh.colors[v * 3 + a].toInt() and 0xFF) / 255.0, 3)
                sb.append(if (a < 2) ' ' else '\n')
            }
            if (sb.length > FLUSH_AT) flush(sb, out)
        }
        for (t in 0 until mesh.triangleCount) {
            sb.append("f ").append(mesh.indices[t * 3] + 1).append(' ')
                .append(mesh.indices[t * 3 + 1] + 1).append(' ')
                .append(mesh.indices[t * 3 + 2] + 1).append('\n')
            if (sb.length > FLUSH_AT) flush(sb, out)
        }
        flush(sb, out)
        out.flush()
    }

    private fun flush(sb: StringBuilder, out: Writer) {
        out.write(sb.toString())
        sb.setLength(0)
    }

    private const val FLUSH_AT = 1 shl 16
}
