package com.banyawa.sitescanner.core.export

import com.banyawa.sitescanner.core.floorplan.DoorSwing
import com.banyawa.sitescanner.core.floorplan.ElevationBuilder
import com.banyawa.sitescanner.core.floorplan.FloorPlan
import com.banyawa.sitescanner.core.floorplan.FloorPlanExtractor
import com.banyawa.sitescanner.core.floorplan.Hinge
import com.banyawa.sitescanner.core.floorplan.Opening
import com.banyawa.sitescanner.core.floorplan.OpeningType
import com.banyawa.sitescanner.core.floorplan.SiteAlignment
import com.banyawa.sitescanner.core.floorplan.SyntheticRoom
import com.banyawa.sitescanner.core.floorplan.WallSegment
import com.banyawa.sitescanner.core.geometry.Vec2
import com.banyawa.sitescanner.core.geometry.Vec3
import com.banyawa.sitescanner.core.pointcloud.PointCloud
import com.banyawa.sitescanner.core.project.Measurement
import com.banyawa.sitescanner.core.units.LengthFormat
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

    /** Square plan with a door on the south wall and a window on the east wall (interior is inside the square). */
    private fun planWithOpenings(): FloorPlan {
        val base = squarePlan()
        // South wall seen from inside faces south: left = east. Hinged there, opening into the room.
        val door = Opening(
            "op1", OpeningType.DOOR, Vec2(2f, 0f), Vec2(1.1f, 0f), 0f, 2.05f, Vec2(4f, 0f), Vec2(0f, 0f), 0.9f,
            swing = DoorSwing(Hinge.LEFT, inward = true),
        )
        // East wall seen from inside faces east: left = north.
        val window = Opening("op2", OpeningType.WINDOW, Vec2(4f, 2.2f), Vec2(4f, 1.0f), 0.9f, 2.0f, Vec2(4f, 3f), Vec2(4f, 0f), 0.6f)
        return base.copy(
            walls = listOf(
                WallSegment(Vec2(0f, 0f), Vec2(1.1f, 0f)),
                WallSegment(Vec2(2f, 0f), Vec2(4f, 0f)),
            ) + base.walls.drop(1),
            openings = listOf(door, window),
        )
    }

    @Test
    fun openingInteriorNormalsPointInside() {
        val (door, window) = planWithOpenings().openings
        assertEquals(Vec2(0f, 1f), door.interiorNormal)
        assertEquals(-1f, window.interiorNormal.x, 1e-6f)
        assertEquals(1.2f, window.width, 1e-5f)
        assertEquals(0.8f, window.distanceFromWallStart, 1e-5f)
    }

    @Test
    fun dxfDrawsOpeningsAndSchedule() {
        val text = FloorPlanDxf.build(planWithOpenings()).toString()
        File("build/test-output").mkdirs()
        File("build/test-output/openings.dxf").writeText(text)
        for (layer in listOf(FloorPlanDxf.LAYER_DOORS, FloorPlanDxf.LAYER_WINDOWS, FloorPlanDxf.LAYER_TAGS, FloorPlanDxf.LAYER_SCHEDULE)) {
            assertTrue("missing layer $layer", text.contains("\n$layer\n"))
        }
        assertTrue(text.contains("\nD1\n"))
        assertTrue(text.contains("\nW1\n"))
        assertTrue(text.contains("\n900x2050\n"))
        assertTrue(text.contains("\n1200x1100 SILL 900\n"))
        assertTrue(text.contains("DOOR / WINDOW SCHEDULE"))
        assertTrue("low confidence window flagged", text.contains("\nVERIFY\n"))
        // Leaf hinged at x = 2000 opens north; the swing arc runs from the open leaf (90°)
        // counter-clockwise to the closed position (180°).
        assertTrue(text.contains("\nARC\n"))
        assertTrue(text.contains(" 10\n2000.000000\n 20\n0.000000\n 30\n0.000000\n 40\n900.000000\n 50\n90.000000\n 51\n180.000000\n"))
        assertTrue(text.contains("\nSWING\n"))
        assertTrue(text.contains("\nL-IN\n"))
    }

    @Test
    fun dxfFromDetectedOpenings() {
        val holes = listOf(
            SyntheticRoom.Opening(edge = 0, from = 1.0f, to = 1.9f, topM = 2.05f),
            SyntheticRoom.Opening(edge = 1, from = 0.8f, to = 2.0f, bottomM = 0.9f, topM = 2.0f),
        )
        // Leaf hinged on the door's west jamb, opened 60° into the room.
        val leaf = SyntheticRoom.Panel(Vec2(1.0f, 0f), Vec2(1.45f, 0.779f), 0f, 2.03f)
        val result = FloorPlanExtractor().extract(SyntheticRoom.rectangle(4f, 3f, yawDeg = 12f, openings = holes, panels = listOf(leaf)))
        assertEquals(2, result.plan.openings.size)
        assertEquals(DoorSwing(Hinge.RIGHT, inward = true), result.plan.openings.single { it.type == OpeningType.DOOR }.swing)
        val text = FloorPlanDxf.build(result.plan, result.slice).toString()
        assertTrue(text.contains("\nARC\n"))
        File("build/test-output").mkdirs()
        File("build/test-output/detected.dxf").writeText(text)
        val obj = StringWriter().also { WallsObj.write(result.plan, it) }.toString()
        File("build/test-output/detected.obj").writeText(obj)
    }

    @Test
    fun objCutsOpenings() {
        val out = StringWriter()
        WallsObj.write(planWithOpenings(), out)
        val lines = out.toString().lines()
        // South: 2 wall pieces; east: 2 pieces around the window; north, west: 1 each.
        // Door: header only (no sill). Window: sill + header.
        assertEquals(6 + 1 + 2, lines.count { it.startsWith("f ") })
        assertTrue(lines.contains("o D1_door"))
        assertTrue(lines.contains("o W1_window"))
        assertEquals(2, lines.count { it.startsWith("l ") })
    }

    @Test
    fun openingCsvRows() {
        val out = StringWriter()
        OpeningCsv.write(planWithOpenings().openings, out, "Scan 1")
        val lines = out.toString().removePrefix("\uFEFF").trim().lines()
        assertEquals(3, lines.size)
        assertEquals("Scan 1,D1,DOOR,900,2050,0,2050,2000,1100,4000,0.90,LEFT,IN,SCAN", lines[1])
        assertEquals("Scan 1,W1,WINDOW,1200,1100,900,2000,800,1000,3000,0.60,,,SCAN", lines[2])
    }

    @Test
    fun manualOpeningsAreMarked() {
        val (door, window) = planWithOpenings().openings
        val manual = listOf(door.copy(swing = DoorSwing(Hinge.BOTH, inward = false)), window.copy(manual = true))
        val csv = StringWriter().also { OpeningCsv.write(manual, it) }.toString().trim().lines()
        assertTrue(csv[1].endsWith(",BOTH,OUT,SCAN"))
        assertTrue(csv[2].endsWith(",,,MANUAL"))

        val text = FloorPlanDxf.build(planWithOpenings().copy(openings = manual)).toString()
        assertTrue(text.contains("\nMANUAL\n"))
        assertTrue("checked by hand, no longer flagged", !text.contains("\nVERIFY\n"))
        assertTrue(text.contains("\nPAIR-OUT\n"))
        // A pair: two leaves, two arcs.
        assertEquals(2, text.split("\nARC\n").size - 1)
    }

    @Test
    fun elevationsDxf() {
        val holes = listOf(
            SyntheticRoom.Opening(edge = 0, from = 1.0f, to = 1.9f, topM = 2.05f),
            SyntheticRoom.Opening(edge = 1, from = 0.8f, to = 2.0f, bottomM = 0.9f, topM = 2.0f),
        )
        val leaf = SyntheticRoom.Panel(Vec2(1.0f, 0f), Vec2(1.45f, 0.779f), 0f, 2.03f)
        val cabinet = SyntheticRoom.Panel(Vec2(0.5f, 2.85f), Vec2(1.5f, 2.85f), 1.4f, 2.0f)
        val cloud = SyntheticRoom.rectangle(4f, 3f, yawDeg = 12f, openings = holes, panels = listOf(leaf, cabinet))
        val result = FloorPlanExtractor().extract(cloud)
        val elevations = ElevationBuilder().build(cloud, result.plan)
        assertEquals(4, elevations.size)
        val tags = com.banyawa.sitescanner.core.floorplan.OpeningTags.assign(result.plan.openings)

        val text = ElevationDxf.build(elevations, tags, ElevationDxf.Options(title = "Test")).toString()
        File("build/test-output").mkdirs()
        File("build/test-output/elevations.dxf").writeText(text)
        for (layer in listOf(ElevationDxf.LAYER_WALL, ElevationDxf.LAYER_DOORS, ElevationDxf.LAYER_WINDOWS, ElevationDxf.LAYER_DIMS, ElevationDxf.LAYER_SCAN, ElevationDxf.LAYER_SCAN_FRONT)) {
            assertTrue("missing layer $layer", text.contains("\n$layer\n"))
        }
        for (key in listOf("A", "B", "C", "D")) assertTrue(text.contains("\nELEVATION $key\n"))
        assertTrue(text.contains("\nD1\n") && text.contains("\nW1\n"))
        // Wall lengths and the room height are dimensioned; the window's sill too.
        assertTrue(text.contains("\n2600\n") || text.contains("\n2599\n"))
        val sill = elevations.flatMap { it.openings }.single { it.opening.type == OpeningType.WINDOW }.bottom
        assertTrue(text.contains("\n${LengthFormat.toMillimeters(sill)}\n"))
        assertEquals(0, text.lines().dropLastWhile { it.isEmpty() }.size % 2)

        val plan = FloorPlanDxf.build(result.plan, result.slice, elevations = elevations).toString()
        File("build/test-output/plan_with_keys.dxf").writeText(plan)
        assertEquals(4, plan.split("\nCIRCLE\n").size - 1)
        assertTrue(plan.contains("\n${FloorPlanDxf.LAYER_ELEVATION_KEYS}\n"))
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
