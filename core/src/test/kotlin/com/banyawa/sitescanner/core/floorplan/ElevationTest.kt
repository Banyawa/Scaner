package com.banyawa.sitescanner.core.floorplan

import com.banyawa.sitescanner.core.floorplan.SyntheticRoom.Opening as Hole
import com.banyawa.sitescanner.core.floorplan.SyntheticRoom.Panel
import com.banyawa.sitescanner.core.geometry.Vec2
import com.banyawa.sitescanner.core.pointcloud.PointCloud
import com.banyawa.sitescanner.core.project.Measurement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4 m (x) × 3 m (y) room, 2.6 m high: a door in the south wall, a window in the east wall
 * and a wall cabinet 150 mm in front of the north wall.
 */
class ElevationTest {
    private val tol = 0.03f
    private val door = Hole(edge = 0, from = 1.0f, to = 1.9f, topM = 2.05f)
    private val window = Hole(edge = 1, from = 0.8f, to = 2.0f, bottomM = 0.9f, topM = 2.0f)
    private val cabinet = Panel(Vec2(0.5f, 2.85f), Vec2(1.5f, 2.85f), 1.4f, 2.0f)

    private val cloud: PointCloud = SyntheticRoom.rectangle(4f, 3f, yawDeg = 12f, openings = listOf(door, window), panels = listOf(cabinet))
    private val plan = FloorPlanExtractor().extract(cloud).plan
    private val elevations = ElevationBuilder().build(cloud, plan)

    private fun points(e: Elevation) = (0 until e.pointCount).map { Triple(e.points[it * 3], e.points[it * 3 + 1], e.points[it * 3 + 2]) }

    @Test
    fun oneElevationPerWallClockwiseFromNorth() {
        assertEquals(listOf("A", "B", "C", "D"), elevations.map { it.key })
        assertEquals(listOf(4f, 3f, 4f, 3f), elevations.map { it.length }.map { Math.round(it * 10) / 10f })
        for (e in elevations) {
            assertEquals(2.6f, e.height, tol)
            // Seen from inside: the room is in front of the wall.
            val probe = e.midpoint + e.interiorNormal * 0.5f
            assertTrue("${e.key} faces the room", plan.bounds()!!.let { b -> probe.x in b.min.x..b.max.x && probe.y in b.min.y..b.max.y })
        }
    }

    @Test
    fun doorsAndWindowsSeenFromInside() {
        val (north, east, south, west) = elevations
        assertTrue(north.openings.isEmpty() && west.openings.isEmpty())

        // South wall seen from inside faces south: its left corner is the east one.
        val d = south.openings.single()
        assertEquals(OpeningType.DOOR, d.opening.type)
        assertEquals(4f - 1.9f, d.left, tol)
        assertEquals(4f - 1.0f, d.right, tol)
        assertEquals(2.05f, d.top, tol)
        assertNull(d.swing)

        // East wall faces east: left is north.
        val w = east.openings.single()
        assertEquals(3f - 2.0f, w.left, tol)
        assertEquals(1.2f, w.width, tol)
        assertEquals(0.9f, w.bottom, tol)
        assertEquals(2.0f, w.top, tol)
    }

    @Test
    fun underlayShowsWallSurfaceAndWhatIsOnIt() {
        val south = elevations[2]
        val southPoints = points(south)
        assertTrue(southPoints.size > 1000)
        // The doorway is a hole in the scan.
        assertTrue(southPoints.none { (x, z, _) -> x in 2.15f..2.95f && z in 0.1f..1.95f })
        assertTrue(southPoints.all { it.third < Elevation.SURFACE_DEPTH })

        // North wall faces north: left is west, so x runs with the room's x.
        val front = points(elevations[0]).filter { it.third > 0.1f }
        assertTrue("cabinet front seen: ${front.size}", front.size > 200)
        for ((x, z, depth) in front) {
            assertTrue("x=$x z=$z", x in 0.45f..1.55f && z in 1.35f..2.05f)
            assertEquals(0.15f, depth, 0.03f)
        }
    }

    @Test
    fun measurementsAlongAWallAreShownOnIt() {
        val north = elevations[0]
        fun world(x: Float, z: Float) = plan.alignment.toWorld(north.start + north.direction * x + north.interiorNormal * 0.05f, z)
        val along = Measurement("m1", "socket height", world(2.5f, 0f), world(2.5f, 0.3f))
        val across = Measurement("m2", "room", world(2f, 1f), plan.alignment.toWorld(elevations[2].midpoint, 1f))
        val attached = ElevationBuilder.attach(elevations, plan, listOf(along, across))
        val m = attached[0].measurements.single()
        assertEquals("socket height", m.label)
        assertEquals(2.5f, m.x0, 1e-3f)
        assertEquals(0f, m.z0, 1e-3f)
        assertEquals(0.3f, m.z1, 1e-3f)
        assertEquals(0.3f, m.lengthM, 1e-3f)
        assertTrue(attached.drop(1).all { it.measurements.isEmpty() })
    }

    @Test
    fun editedOpeningsReplaceDetectedOnes() {
        val detectedDoor = plan.openings.single { it.type == OpeningType.DOOR }
        // The user swapped the room side and set the swing: still hinged on the same jamb.
        val edited = detectedDoor.copy(swing = DoorSwing(Hinge.LEFT, inward = true)).flipped()
        val attached = ElevationBuilder.attach(elevations, plan.copy(openings = listOf(edited)), emptyList())
        val d = attached[2].openings.single()
        assertEquals(DoorSwing(Hinge.LEFT, inward = true), d.swing)
        assertEquals(4f - 1.9f, d.left, tol)
        assertTrue(attached[1].openings.isEmpty())
        // Walls and scan are untouched.
        assertTrue(attached[2].points === elevations[2].points)
    }

    @Test
    fun keysRunPastZ() {
        assertEquals("A", ElevationBuilder.key(0))
        assertEquals("Z", ElevationBuilder.key(25))
        assertEquals("A2", ElevationBuilder.key(26))
    }

    @Test
    fun noWallsNoElevations() {
        assertTrue(ElevationBuilder().build(PointCloud.EMPTY, FloorPlan(emptyList(), SiteAlignment.IDENTITY)).isEmpty())
    }
}
