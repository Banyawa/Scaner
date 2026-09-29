package com.banyawa.sitescanner.core.export

import com.banyawa.sitescanner.core.floorplan.FloorPlan
import com.banyawa.sitescanner.core.floorplan.FloorPlanExtractor
import com.banyawa.sitescanner.core.floorplan.SiteAlignment
import com.banyawa.sitescanner.core.floorplan.SyntheticRoom
import com.banyawa.sitescanner.core.floorplan.WallSegment
import com.banyawa.sitescanner.core.geometry.Vec2
import com.banyawa.sitescanner.core.geometry.Vec3
import com.banyawa.sitescanner.core.pointcloud.PointCloud
import com.banyawa.sitescanner.core.project.Measurement
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringWriter

class ExportTest {
    private val cloud = PointCloud(
        floatArrayOf(1f, 2f, 3f, -0.5f, 0.25f, 10f),
        byteArrayOf(255.toByte(), 0, 10, 1, 2, 3),
    )

    @Test
    fun plyBinaryRoundTrip() {
        val bytes = ByteArrayOutputStream().also { Ply.write(cloud, it) }.toByteArray()
        assertEquals(cloud.size * 15, bytes.size - String(bytes, Charsets.US_ASCII).indexOf("end_header\n") - "end_header\n".length)
        val back = Ply.read(ByteArrayInputStream(bytes))
        assertArrayEquals(cloud.xyz, back.xyz, 0f)
        assertArrayEquals(cloud.rgb, back.rgb)
    }

    @Test
    fun plyWritesSiteFrameWhenAligned() {
        val bytes = ByteArrayOutputStream().also { Ply.write(cloud, it, SiteAlignment(0f, floorY = 1f)) }.toByteArray()
        val back = Ply.read(ByteArrayInputStream(bytes))
        // AR (1, 2, 3) -> site (1, -3, 1)
        assertEquals(1f, back.xyz[0], 1e-6f)
        assertEquals(-3f, back.xyz[1], 1e-6f)
        assertEquals(1f, back.xyz[2], 1e-6f)
    }

    @Test
    fun plyAsciiWithExtraPropertiesAndFaces() {
        val text = """
            ply
            format ascii 1.0
            comment made elsewhere
            element vertex 2
            property float x
            property float y
            property float z
            property float nx
            property uchar red
            property uchar green
            property uchar blue
            property uchar alpha
            element face 1
            property list uchar int vertex_indices
            end_header
            0 1 2 0.5 10 20 30 255
            3 4 5 0.5 40 50 60 255
            3 0 1 1
        """.trimIndent() + "\n"
        val pc = Ply.read(ByteArrayInputStream(text.toByteArray()))
        assertEquals(2, pc.size)
        assertEquals(5f, pc.xyz[5], 0f)
        assertEquals(0x28323C, pc.color(1))
    }

    @Test
    fun ptsHasCountAndSevenColumns() {
        val out = StringWriter()
        Pts.write(cloud, out, SiteAlignment.IDENTITY)
        val lines = out.toString().trim().lines()
        assertEquals("2", lines[0])
        assertEquals(7, lines[1].split(' ').size)
        assertTrue(lines[1].startsWith("1.0000 -3.0000 2.0000 "))
        assertTrue(lines[1].endsWith(" 255 0 10"))
    }

    @Test
    fun numbersFormatting() {
        assertEquals("-0.5000", Numbers.fixed(-0.5, 4))
        assertEquals("0.0000", Numbers.fixed(-0.00001, 4))
        assertEquals("12.0010", Numbers.fixed(12.001, 4))
        assertEquals("3", Numbers.fixed(2.6, 0))
    }

    private fun squarePlan() = FloorPlan(
        walls = listOf(
            WallSegment(Vec2(0f, 0f), Vec2(4f, 0f)),
            WallSegment(Vec2(4f, 0f), Vec2(4f, 3f)),
            WallSegment(Vec2(4f, 3f), Vec2(0f, 3f)),
            WallSegment(Vec2(0f, 3f), Vec2(0f, 0f)),
        ),
        alignment = SiteAlignment(0f, floorY = -1.2f),
        ceilingY = 1.45f,
    )

    private val measurement = Measurement("m1", "ผนัง A", Vec3(0f, -1.2f, 0f), Vec3(4f, -1.2f, 0f))

    @Test
    fun dxfStructureAndContent() {
        val dxf = FloorPlanDxf.build(squarePlan(), floatArrayOf(0.1f, 0.1f), listOf(measurement), FloorPlanDxf.Options(title = "Test"))
        val text = dxf.toString()
        assertTrue(text.contains("AC1009"))
        assertTrue(text.trimEnd().endsWith("EOF"))
        for (layer in listOf(FloorPlanDxf.LAYER_WALLS, FloorPlanDxf.LAYER_DIMS, FloorPlanDxf.LAYER_MEASURE)) {
            assertTrue("missing layer $layer", text.contains("\n$layer\n"))
        }
        assertTrue("wall dimension", text.contains("\n4000\n"))
        assertTrue("room height note", text.contains("Floor to ceiling: 2650 mm"))
        assertTrue("thai label escaped", text.contains("\\U+0E1C\\U+0E19\\U+0E31\\U+0E07 A = 4000"))
        // Pairs: every group code line is followed by a value line.
        assertEquals(0, text.lines().dropLastWhile { it.isEmpty() }.size % 2)

        // Keep a copy for external validation (e.g. ezdxf audit).
        File("build/test-output").mkdirs()
        File("build/test-output/plan.dxf").writeText(text)
    }

    @Test
    fun dxfFromExtractedPlan() {
        val result = FloorPlanExtractor().extract(SyntheticRoom.rectangle(5f, 3.5f, yawDeg = 12f))
        val text = FloorPlanDxf.build(result.plan, result.slice).toString()
        assertTrue(text.contains("\n5000\n") || text.contains("\n4999\n") || text.contains("\n5001\n"))
        File("build/test-output").mkdirs()
        File("build/test-output/extracted.dxf").writeText(text)
    }

    @Test
    fun objWallsAndMeasurements() {
        val out = StringWriter()
        WallsObj.write(squarePlan(), out, listOf(measurement))
        val lines = out.toString().lines()
        assertEquals(4 * 4 + 2, lines.count { it.startsWith("v ") })
        assertEquals(4, lines.count { it.startsWith("f ") })
        assertEquals(1, lines.count { it.startsWith("l ") })
        assertTrue(lines.contains("v 4.0000 2.6500 -3.0000"))
    }

    @Test
    fun csvRows() {
        val out = StringWriter()
        MeasurementCsv.write(listOf(measurement, measurement.copy(label = "a,b")), SiteAlignment(0f, -1.2f), out, "Scan 1")
        val lines = out.toString().removePrefix("﻿").trim().lines()
        assertEquals(3, lines.size)
        assertEquals("Scan 1,1,ผนัง A,4000,0,0,0,4000,0,0,4000,0,0", lines[1])
        assertTrue(lines[2].contains("\"a,b\""))
    }
}
