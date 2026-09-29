package com.banyawa.sitescanner.core.export

import com.banyawa.sitescanner.core.floorplan.SiteAlignment
import com.banyawa.sitescanner.core.mesh.TriangleMesh
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI

class MeshFilesTest {
    /** A unit square on the floor (y = 1 in ARCore), facing up, split into two triangles. */
    private val square = TriangleMesh(
        positions = floatArrayOf(0f, 1f, 0f, 1f, 1f, 0f, 1f, 1f, -1f, 0f, 1f, -1f),
        colors = byteArrayOf(255.toByte(), 0, 0, 0, 255.toByte(), 0, 0, 0, 255.toByte(), 128.toByte(), 128.toByte(), 128.toByte()),
        indices = intArrayOf(0, 1, 2, 0, 2, 3),
    )

    @Test
    fun plyRoundTrip() {
        val out = ByteArrayOutputStream()
        MeshPly.write(square, out)
        val back = MeshPly.read(ByteArrayInputStream(out.toByteArray()))
        assertArrayEquals(square.positions, back.positions, 0f)
        assertArrayEquals(square.colors, back.colors)
        assertArrayEquals(square.indices, back.indices)
    }

    @Test
    fun plyKeepsTextureCoordinates() {
        val uv = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        val out = ByteArrayOutputStream()
        MeshPly.write(square, out, uv = uv)
        val back = MeshPly.readTextured(ByteArrayInputStream(out.toByteArray()))
        assertArrayEquals(uv, back.uv, 0f)
        assertArrayEquals(square.positions, back.mesh.positions, 0f)
        assertArrayEquals(square.indices, back.mesh.indices)
        // Plain readers still get the mesh, and untextured files have no coordinates.
        assertArrayEquals(square.colors, MeshPly.read(ByteArrayInputStream(out.toByteArray())).colors)
        val plain = ByteArrayOutputStream().also { MeshPly.write(square, it) }
        assertEquals(null, MeshPly.readTextured(ByteArrayInputStream(plain.toByteArray())).uv)
    }

    @Test
    fun glbIsWellFormed() {
        val out = ByteArrayOutputStream()
        Glb.write(square, out)
        val bytes = out.toByteArray()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(Glb.MAGIC, b.getInt(0))
        assertEquals(2, b.getInt(4))
        assertEquals(bytes.size, b.getInt(8))

        val jsonLength = b.getInt(12)
        assertEquals(Glb.CHUNK_JSON, b.getInt(16))
        assertEquals(0, jsonLength % 4)
        val json = Json.parseToJsonElement(String(bytes, 20, jsonLength, Charsets.UTF_8)).jsonObject

        val binLength = b.getInt(20 + jsonLength)
        assertEquals(Glb.CHUNK_BIN, b.getInt(24 + jsonLength))
        assertEquals(bytes.size, 28 + jsonLength + binLength)
        val declared = json["buffers"]!!.jsonArray[0].jsonObject["byteLength"]!!.jsonPrimitive.int
        assertTrue(declared <= binLength && binLength - declared < 4)

        val accessors = json["accessors"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(4, 4, 4, 6), accessors.map { it["count"]!!.jsonPrimitive.int })
        // Every buffer view fits the binary chunk and starts 4-byte aligned.
        for (view in json["bufferViews"]!!.jsonArray.map { it.jsonObject }) {
            val offset = view["byteOffset"]!!.jsonPrimitive.int
            assertEquals(0, offset % 4)
            assertTrue(offset + view["byteLength"]!!.jsonPrimitive.int <= declared)
        }
        // Indices come last and are the triangles as given.
        val indexOffset = 20 + jsonLength + 8 + 4 * 12 * 3
        assertEquals(listOf(0, 1, 2, 0, 2, 3), (0 until 6).map { b.getInt(indexOffset + it * 4) })
        // Colours are linear: sRGB 128 is about 0.216.
        val grey = b.getFloat(20 + jsonLength + 8 + 4 * 12 * 2 + 3 * 12)
        assertEquals(0.216f, grey, 0.002f)
    }

    @Test
    fun objHasColouredVerticesAndOneBasedFaces() {
        val w = StringWriter()
        MeshObj.write(square, w)
        val lines = w.toString().lines()
        assertEquals("v 0.0000 1.0000 0.0000 1.000 0.000 0.000", lines.first { it.startsWith("v ") })
        assertEquals(listOf("f 1 2 3", "f 1 3 4"), lines.filter { it.startsWith("f ") })
    }

    @Test
    fun siteFramesPutTheFloorAtZeroAndKeepWinding() {
        val alignment = SiteAlignment(yawRad = (PI / 2).toFloat(), floorY = 1f)
        val zUp = MeshFrames.siteZUp(square, alignment)
        for (v in 0 until 4) assertEquals(0f, zUp.positions[v * 3 + 2], 1e-6f)
        // Still facing up after the turn: +Z in the site frame, +Y in the Y-up frame.
        assertEquals(1f, zUp.normals()[2], 1e-5f)
        val yUp = MeshFrames.siteYUp(square, alignment)
        for (v in 0 until 4) assertEquals(0f, yUp.positions[v * 3 + 1], 1e-6f)
        assertEquals(1f, yUp.normals()[1], 1e-5f)
    }
}
