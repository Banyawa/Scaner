package com.banyawa.sitescanner.core.floorplan

import com.banyawa.sitescanner.core.floorplan.SyntheticRoom.Opening as Hole
import com.banyawa.sitescanner.core.floorplan.SyntheticRoom.Panel
import com.banyawa.sitescanner.core.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rooms are 4 m (x) × 3 m (y), 2.6 m high, the room on the +y side of the south wall
 * (y = 0). Seen from inside facing south, the left jamb is the east one (larger x).
 */
class DoorSwingDetectorTest {
    private val extractor = FloorPlanExtractor()
    private val door = Hole(edge = 0, from = 1.0f, to = 1.9f, topM = 2.05f)

    /** Leaf of [width] hinged at plan point [hinge], turned [deg] from closed towards [closedDir] rotated to [openDir]. */
    private fun leaf(hinge: Vec2, closedDir: Vec2, openDir: Vec2, deg: Float, width: Float = 0.9f, top: Float = 2.03f): Panel {
        val a = Math.toRadians(deg.toDouble())
        val tip = hinge + (closedDir * cos(a).toFloat() + openDir * sin(a).toFloat()) * width
        return Panel(hinge, tip, 0f, top)
    }

    private fun plan(holes: List<Hole>, panels: List<Panel>, yaw: Float = 15f) =
        extractor.extract(SyntheticRoom.rectangle(4f, 3f, yawDeg = yaw, openings = holes, panels = panels)).plan

    private val east = Vec2(1f, 0f)
    private val west = Vec2(-1f, 0f)
    private val room = Vec2(0f, 1f)
    private val outside = Vec2(0f, -1f)

    @Test
    fun partlyOpenLeafIntoTheRoom() {
        // Hinged on the west jamb (x = 1.0): the right jamb seen from inside.
        val p = plan(listOf(door), listOf(leaf(Vec2(1.0f, 0f), east, room, 45f)))
        val d = p.openings.single { it.type == OpeningType.DOOR }
        assertEquals(DoorSwing(Hinge.RIGHT, inward = true), d.swing)
        // The leaf is not left behind as a short wall: south (two pieces), east, north, west.
        assertEquals("walls: ${p.walls}", 5, p.walls.size)
    }

    @Test
    fun leafOpenOutwardAtRightAngle() {
        val p = plan(listOf(door), listOf(leaf(Vec2(1.9f, 0f), west, outside, 90f)), yaw = -25f)
        val d = p.openings.single { it.type == OpeningType.DOOR }
        assertEquals(DoorSwing(Hinge.LEFT, inward = false), d.swing)
    }

    @Test
    fun wideOpenLeafIntoTheRoom() {
        val p = plan(listOf(door), listOf(leaf(Vec2(1.9f, 0f), west, room, 150f)), yaw = 30f)
        assertEquals(DoorSwing(Hinge.LEFT, inward = true), p.openings.single().swing)
    }

    @Test
    fun pairOfLeaves() {
        val pair = Hole(edge = 0, from = 1.2f, to = 2.8f, topM = 2.1f)
        val leaves = listOf(
            leaf(Vec2(1.2f, 0f), east, room, 80f, width = 0.8f, top = 2.08f),
            leaf(Vec2(2.8f, 0f), west, room, 95f, width = 0.8f, top = 2.08f),
        )
        val d = plan(listOf(pair), leaves).openings.single { it.type == OpeningType.DOOR }
        assertEquals(1.6f, d.width, 0.03f)
        assertEquals(DoorSwing(Hinge.BOTH, inward = true), d.swing)
    }

    @Test
    fun outwardLeafHingedOnFarFaceOfThickWall() {
        // 200 mm wall: far face at y = -0.2 with reveals at the jambs; hinge on the far face.
        val t = -0.2f
        val panels = listOf(
            Panel(Vec2(-0.2f, t), Vec2(1.0f, t), 0f, 2.6f),
            Panel(Vec2(1.9f, t), Vec2(4.2f, t), 0f, 2.6f),
            Panel(Vec2(1.0f, t), Vec2(1.9f, t), 2.05f, 2.6f),
            Panel(Vec2(1.0f, 0f), Vec2(1.0f, t), 0f, 2.05f),
            Panel(Vec2(1.9f, 0f), Vec2(1.9f, t), 0f, 2.05f),
            leaf(Vec2(1.0f, t), east, outside, 70f),
        )
        val d = plan(listOf(door), panels).openings.single { it.type == OpeningType.DOOR }
        assertEquals(DoorSwing(Hinge.RIGHT, inward = false), d.swing)
    }

    @Test
    fun noLeafNoSwing() {
        val d = plan(listOf(door), emptyList()).openings.single()
        assertEquals(OpeningType.DOOR, d.type)
        assertNull(d.swing)
    }

    @Test
    fun wallBesideTheJambIsNotALeaf() {
        // Door 100 mm from the west corner: the west wall runs past the jamb into the room.
        val cornerDoor = Hole(edge = 0, from = 0.1f, to = 1.0f, topM = 2.1f)
        val d = plan(listOf(cornerDoor), emptyList(), yaw = 8f).openings.single()
        assertEquals(OpeningType.DOOR, d.type)
        assertNull(d.swing)
    }

    @Test
    fun leafOpenAlongsideTheNextWall() {
        // Door 120 mm from the west corner, hinged on that side and opened square: the leaf
        // stands parallel to the west wall, 120 mm off it.
        val cornerDoor = Hole(edge = 0, from = 0.12f, to = 1.02f, topM = 2.05f)
        val p = plan(listOf(cornerDoor), listOf(leaf(Vec2(0.12f, 0f), east, room, 90f)), yaw = -12f)
        assertEquals(DoorSwing(Hinge.RIGHT, inward = true), p.openings.single { it.type == OpeningType.DOOR }.swing)
    }

    @Test
    fun partitionAtTheJambIsNotALeaf() {
        // A 1.5 m full-height partition starts right at the east jamb.
        val partition = Panel(Vec2(1.9f, 0f), Vec2(1.9f, 1.5f), 0f, 2.6f)
        val d = plan(listOf(door), listOf(partition)).openings.single { it.type == OpeningType.DOOR }
        assertNull(d.swing)
    }

    @Test
    fun lowFurnitureIsNotALeaf() {
        // A 0.8 m high sideboard end at the jamb.
        val sideboard = Panel(Vec2(1.0f, 0.02f), Vec2(1.0f, 0.9f), 0f, 0.8f)
        val d = plan(listOf(door), listOf(sideboard)).openings.single { it.type == OpeningType.DOOR }
        assertNull(d.swing)
    }

    @Test
    fun robustToMissingPoints() {
        val cloud = SyntheticRoom.rectangle(
            4f, 3f, yawDeg = 20f, openings = listOf(door), panels = listOf(leaf(Vec2(1.0f, 0f), east, room, 60f)), dropout = 0.4f,
        )
        val d = extractor.extract(cloud).plan.openings.single { it.type == OpeningType.DOOR }
        assertEquals(DoorSwing(Hinge.RIGHT, inward = true), d.swing)
    }

    @Test
    fun leavesDrawnOpenAtRightAngles() {
        // South wall seen from inside: start (left) is east.
        val base = Opening("d", OpeningType.DOOR, Vec2(2f, 0f), Vec2(1.1f, 0f), 0f, 2.05f, Vec2(4f, 0f), Vec2(0f, 0f))
        assertTrue(base.leaves.isEmpty())

        val left = base.copy(swing = DoorSwing(Hinge.LEFT, inward = true)).leaves.single()
        assertEquals(Vec2(2f, 0f), left.hinge)
        assertEquals(Vec2(1.1f, 0f), left.closed)
        assertEquals(2f, left.open.x, 1e-5f)
        assertEquals(0.9f, left.open.y, 1e-5f)
        val mid = left.arcPoint(0.5f)
        assertEquals(0.9f, mid.distanceTo(left.hinge), 1e-5f)
        assertTrue(mid.x < 2f && mid.y > 0f)

        val out = base.copy(swing = DoorSwing(Hinge.RIGHT, inward = false)).leaves.single()
        assertEquals(Vec2(1.1f, 0f), out.hinge)
        assertEquals(-0.9f, out.open.y, 1e-5f)

        val pair = base.copy(swing = DoorSwing(Hinge.BOTH, inward = true)).leaves
        assertEquals(2, pair.size)
        assertEquals(0.45f, pair[0].width, 1e-5f)
        assertEquals(pair[0].closed, pair[1].closed)
    }

    @Test
    fun swingCodesAndMirroring() {
        assertEquals("L-IN", DoorSwing(Hinge.LEFT, true).code)
        assertEquals("PAIR-OUT", DoorSwing(Hinge.BOTH, false).code)
        assertEquals(DoorSwing(Hinge.LEFT, false), DoorSwing(Hinge.RIGHT, true).mirrored())
    }
}
