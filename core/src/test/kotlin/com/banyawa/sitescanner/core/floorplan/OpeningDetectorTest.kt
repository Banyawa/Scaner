package com.banyawa.sitescanner.core.floorplan

import com.banyawa.sitescanner.core.floorplan.SyntheticRoom.Opening as Hole
import com.banyawa.sitescanner.core.floorplan.SyntheticRoom.Panel
import com.banyawa.sitescanner.core.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rooms are 4 m (x) × 3 m (y), 2.6 m high. Edges: 0 south (y=0, t from x=0), 1 east
 * (x=4, t from y=0), 2 north (y=3, t from x=4), 3 west (x=0, t from y=3).
 */
class OpeningDetectorTest {
    private val extractor = FloorPlanExtractor()
    private val tol = 0.03f

    private fun openings(cloud: com.banyawa.sitescanner.core.pointcloud.PointCloud) = extractor.extract(cloud).plan.openings

    private val door = Hole(edge = 0, from = 1.0f, to = 1.9f, bottomM = 0f, topM = 2.05f)
    private val windowEast = Hole(edge = 1, from = 0.8f, to = 2.0f, bottomM = 0.9f, topM = 2.0f)
    private val windowNorth = Hole(edge = 2, from = 1.5f, to = 2.7f, bottomM = 1.0f, topM = 2.1f)

    @Test
    fun detectsDoorAndWindowsWithSizes() {
        val found = openings(SyntheticRoom.rectangle(4f, 3f, yawDeg = 20f, openings = listOf(door, windowEast, windowNorth)))
        assertEquals("found: $found", 3, found.size)

        val d = found.single { it.type == OpeningType.DOOR }
        assertEquals(0.90f, d.width, tol)
        assertEquals(0f, d.bottom, 0f)
        assertEquals(2.05f, d.top, tol)
        // Seen from inside, the south wall's left corner is the east one (x = 4).
        assertEquals(4f, d.wallLength, tol)
        assertEquals(2.1f, d.distanceFromWallStart, tol)
        assertEquals(1.0f, d.distanceToWallEnd, tol)

        val windows = found.filter { it.type == OpeningType.WINDOW }.sortedBy { it.bottom }
        assertEquals(2, windows.size)
        val (e, n) = windows
        assertEquals(1.2f, e.width, tol)
        assertEquals(0.9f, e.bottom, tol)
        assertEquals(2.0f, e.top, tol)
        assertEquals(3f, e.wallLength, tol)
        assertEquals(1.2f, n.width, tol)
        assertEquals(1.0f, n.bottom, tol)
        assertEquals(2.1f, n.top, tol)
        // North wall seen from inside: left corner is the west one; the hole starts 1.5 m from the east corner.
        assertEquals(4f - 2.7f, n.distanceFromWallStart, tol)

        for (o in found) assertTrue("confidence ${o.confidence}", o.confidence >= 0.7f)
        assertEquals(listOf("op1", "op2", "op3"), found.map { it.id })
    }

    @Test
    fun interiorNormalPointsIntoRoom() {
        val plan = extractor.extract(SyntheticRoom.rectangle(4f, 3f, yawDeg = 0f, openings = listOf(door, windowEast))).plan
        val bounds = plan.bounds()!!
        assertEquals(2, plan.openings.size)
        for (o in plan.openings) {
            val probe = o.midpoint + o.interiorNormal * 0.5f
            assertTrue("$o probe $probe", probe.x > bounds.min.x && probe.x < bounds.max.x && probe.y > bounds.min.y && probe.y < bounds.max.y)
        }
    }

    @Test
    fun doorNextToCornerIsFound() {
        // 10 cm return between the corner and the door: too short to be a wall on its own.
        val cornerDoor = Hole(edge = 3, from = 0.1f, to = 1.0f, topM = 2.1f)
        val found = openings(SyntheticRoom.rectangle(4f, 3f, yawDeg = 8f, openings = listOf(cornerDoor)))
        val d = found.single()
        assertEquals(OpeningType.DOOR, d.type)
        assertEquals(0.9f, d.width, tol)
        assertEquals(2.1f, d.top, tol)
    }

    @Test
    fun seeThroughDoorAndMullionWindow() {
        // Next room's wall visible 1.5 m behind the door; a 5 cm mullion splits the window.
        val behindDoor = Panel(Vec2(0.6f, -1.5f), Vec2(2.4f, -1.5f), 0f, 2.3f)
        val paneA = Hole(edge = 2, from = 0.5f, to = 1.675f, bottomM = 0.9f, topM = 2.0f)
        val paneB = Hole(edge = 2, from = 1.725f, to = 2.9f, bottomM = 0.9f, topM = 2.0f)
        val found = openings(
            SyntheticRoom.rectangle(4f, 3f, yawDeg = -35f, openings = listOf(door, paneA, paneB), panels = listOf(behindDoor)),
        )
        assertEquals("found: $found", 2, found.size)
        val d = found.single { it.type == OpeningType.DOOR }
        assertTrue("see-through raises confidence", d.confidence > 0.9f)
        val w = found.single { it.type == OpeningType.WINDOW }
        assertEquals(2.4f, w.width, tol)
    }

    @Test
    fun partlyOpenDoorLeafDoesNotHideDoor() {
        // Leaf hinged at x = 1.0, opened 45° into the room.
        val leaf = Panel(Vec2(1.0f, 0f), Vec2(1.636f, 0.636f), 0f, 2.03f)
        val found = openings(SyntheticRoom.rectangle(4f, 3f, yawDeg = 15f, openings = listOf(door), panels = listOf(leaf)))
        assertEquals("found: $found", 1, found.count { it.type == OpeningType.DOOR })
        // Leaf points right at the hinge look like jamb surface, so allow a few cm there.
        assertEquals(0.9f, found.single { it.type == OpeningType.DOOR }.width, 0.05f)
    }

    @Test
    fun plainRoomHasNoOpenings() {
        assertTrue(openings(SyntheticRoom.rectangle(4f, 3f, yawDeg = 20f)).isEmpty())
    }

    @Test
    fun unscannedTopOfWallsIsNotAWindow() {
        assertTrue(openings(SyntheticRoom.rectangle(4f, 3f, yawDeg = 20f, wallMaxHeight = 2.0f)).isEmpty())
    }

    @Test
    fun wardrobeHidingTheWallIsNotADoor() {
        val hidden = Hole(edge = 1, from = 0.8f, to = 1.8f, bottomM = 0f, topM = 2.2f)
        val front = Panel(Vec2(3.4f, 0.8f), Vec2(3.4f, 1.8f), 0f, 2.2f)
        val sides = listOf(
            Panel(Vec2(3.4f, 0.8f), Vec2(4f, 0.8f), 0f, 2.2f),
            Panel(Vec2(3.4f, 1.8f), Vec2(4f, 1.8f), 0f, 2.2f),
        )
        val found = openings(SyntheticRoom.rectangle(4f, 3f, yawDeg = 20f, openings = listOf(hidden), panels = listOf(front) + sides))
        assertTrue("found: $found", found.isEmpty())
    }

    @Test
    fun kitchenCounterIsNotAWindow() {
        // 0.9 m high counter along the whole north wall, 0.6 m deep, hiding the wall below it.
        val hidden = Hole(edge = 2, from = 0f, to = 4f, bottomM = 0f, topM = 0.9f)
        val front = Panel(Vec2(0f, 2.4f), Vec2(4f, 2.4f), 0f, 0.9f)
        val found = openings(SyntheticRoom.rectangle(4f, 3f, yawDeg = 20f, openings = listOf(hidden), panels = listOf(front)))
        assertTrue("found: $found", found.isEmpty())
    }

    @Test
    fun robustToMissingPoints() {
        val found = openings(
            SyntheticRoom.rectangle(4f, 3f, yawDeg = 20f, openings = listOf(door, windowEast, windowNorth), dropout = 0.4f),
        )
        assertEquals("found: $found", 3, found.size)
        assertEquals(1, found.count { it.type == OpeningType.DOOR })
    }

    @Test
    fun realignKeepsPhysicalPosition() {
        val o = openings(SyntheticRoom.rectangle(4f, 3f, yawDeg = 20f, openings = listOf(door))).single()
        val a = SiteAlignment(yawRad = 0.35f, floorY = -1.4f)
        val b = SiteAlignment(yawRad = -1.1f, floorY = -1.38f)
        val moved = o.realign(a, b)
        assertEquals(o.width, moved.width, 1e-4f)
        assertEquals(o.top - 0.02f, moved.top, 1e-4f)
        val world = a.toWorld(o.start, 0f)
        val world2 = b.toWorld(moved.start, 0f)
        assertEquals(world.x, world2.x, 1e-4f)
        assertEquals(world.z, world2.z, 1e-4f)
        val back = moved.realign(b, a)
        assertEquals(o.start.x, back.start.x, 1e-4f)
        assertEquals(o.end.y, back.end.y, 1e-4f)

        val edits = OpeningEdits(a, listOf(o))
        val plan = FloorPlan(emptyList(), b)
        assertEquals(moved, edits.applyTo(plan).openings.single())
    }

    @Test
    fun toWorldInvertsToSite() {
        val a = SiteAlignment(yawRad = 0.7f, floorY = -1.2f)
        val w = a.toWorld(Vec2(1.5f, -2f), 0.8f)
        val s = a.toSite(w)
        assertEquals(1.5f, s.x, 1e-5f)
        assertEquals(-2f, s.y, 1e-5f)
        assertEquals(0.8f, s.z, 1e-5f)
    }

    @Test
    fun tagsNumberPerType() {
        val base = Opening("a", OpeningType.DOOR, Vec2.ZERO, Vec2(1f, 0f), 0f, 2f, Vec2.ZERO, Vec2(3f, 0f))
        val list = listOf(base, base.copy(id = "b", type = OpeningType.WINDOW), base.copy(id = "c"), base.copy(id = "d", type = OpeningType.OPENING))
        assertEquals(mapOf("a" to "D1", "b" to "W1", "c" to "D2", "d" to "O1"), OpeningTags.assign(list))
    }
}
