package com.banyawa.sitescanner.core.floorplan

import com.banyawa.sitescanner.core.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningPlacementTest {
    private val tol = 1e-4f

    /** 4 × 3 m room, the south wall split by a gap from x = 1.1 to 2.0. */
    private fun room() = FloorPlan(
        walls = listOf(
            WallSegment(Vec2(0f, 0f), Vec2(1.1f, 0f)),
            WallSegment(Vec2(2f, 0f), Vec2(4f, 0f)),
            WallSegment(Vec2(4f, 0f), Vec2(4f, 3f)),
            WallSegment(Vec2(4f, 3f), Vec2(0f, 3f)),
            WallSegment(Vec2(0f, 3f), Vec2(0f, 0f)),
        ),
        alignment = SiteAlignment(0.3f, -1.2f),
        ceilingY = 1.45f,
    )

    @Test
    fun doorPlacedOnTappedWallAcrossItsGap() {
        val o = OpeningPlacement.place(room(), Vec2(1.55f, 0.06f), OpeningType.DOOR, "u1")!!
        assertEquals("u1", o.id)
        assertTrue(o.manual)
        assertNull(o.swing)
        // Host wall is the whole south wall, corner to corner, seen from inside: left = east.
        assertEquals(Vec2(4f, 0f), o.wallStart)
        assertEquals(Vec2(0f, 0f), o.wallEnd)
        assertEquals(0f, o.interiorNormal.x, tol)
        assertEquals(1f, o.interiorNormal.y, tol)
        // Default door 800 × 2000 centred on the tap.
        assertEquals(0.8f, o.width, tol)
        assertEquals(1.55f, o.midpoint.x, tol)
        assertEquals(0f, o.bottom, 0f)
        assertEquals(2.0f, o.top, tol)
        assertEquals(4f - 1.95f, o.distanceFromWallStart, tol)
    }

    @Test
    fun windowNearCornerStaysOnTheWall() {
        val o = OpeningPlacement.place(room(), Vec2(3.95f, 2.9f), OpeningType.WINDOW, "u1")!!
        // Nearest wall is the east one (x = 4), seen from inside facing east: left = north.
        assertEquals(Vec2(4f, 3f), o.wallStart)
        assertEquals(0f, o.distanceFromWallStart, tol)
        assertEquals(1.0f, o.width, tol)
        assertEquals(0.9f, o.bottom, tol)
        assertEquals(-1f, o.interiorNormal.x, tol)
    }

    @Test
    fun openingTakesRoomHeight() {
        val o = OpeningPlacement.place(room(), Vec2(0.1f, 1.5f), OpeningType.OPENING, "u1")!!
        assertEquals(2.65f, o.top, tol)
        assertEquals(1f, o.interiorNormal.x, tol)
    }

    @Test
    fun tapAwayFromWallsPlacesNothing() {
        assertNull(OpeningPlacement.place(room(), Vec2(2f, 1.5f), OpeningType.DOOR, "u1"))
        assertNull(OpeningPlacement.place(room(), Vec2(6f, 0f), OpeningType.DOOR, "u1"))
    }

    @Test
    fun roomSideOfLShapedRoom() {
        // L-shaped room: the inner corner walls face away from the plan's centroid.
        val outline = listOf(Vec2(0f, 0f), Vec2(6f, 0f), Vec2(6f, 2f), Vec2(2f, 2f), Vec2(2f, 6f), Vec2(0f, 6f))
        val walls = outline.indices.map { WallSegment(outline[it], outline[(it + 1) % outline.size]) }
        val plan = FloorPlan(walls, SiteAlignment.IDENTITY)
        val onInner = OpeningPlacement.place(plan, Vec2(4f, 2.05f), OpeningType.DOOR, "u1")!!
        assertEquals(-1f, onInner.interiorNormal.y, tol)
        val onInnerVertical = OpeningPlacement.place(plan, Vec2(2.05f, 4f), OpeningType.DOOR, "u1")!!
        assertEquals(-1f, onInnerVertical.interiorNormal.x, tol)
    }

    @Test
    fun sideFollowsExistingOpeningOnTheWall() {
        val wall = FloorPlan(listOf(WallSegment(Vec2(0f, 0f), Vec2(5f, 0f))), SiteAlignment.IDENTITY)
        val existing = Opening("op1", OpeningType.WINDOW, Vec2(1f, 0f), Vec2(2f, 0f), 0.9f, 2f, Vec2(0f, 0f), Vec2(5f, 0f))
        assertEquals(-1f, existing.interiorNormal.y, tol)
        val o = OpeningPlacement.place(wall.copy(openings = listOf(existing)), Vec2(4f, 0f), OpeningType.DOOR, "u1")!!
        assertEquals(-1f, o.interiorNormal.y, tol)
        assertTrue(o.sameWallAs(existing))
    }

    @Test
    fun newIdsDoNotClash() {
        val base = Opening("u1", OpeningType.DOOR, Vec2.ZERO, Vec2(1f, 0f), 0f, 2f, Vec2.ZERO, Vec2(3f, 0f))
        assertEquals("u1", OpeningPlacement.newId(emptyList()))
        assertEquals("u2", OpeningPlacement.newId(listOf(base, base.copy(id = "op1"))))
    }

    @Test
    fun resizeMeasuresFromLeftCorner() {
        val door = OpeningPlacement.place(room(), Vec2(1.55f, 0f), OpeningType.DOOR, "u1")!!
        val sized = door.resized(fromLeft = 2.05f, width = 0.9f, bottom = 0f, top = 2.1f)
        assertEquals(2.05f, sized.distanceFromWallStart, tol)
        assertEquals(0.9f, sized.width, tol)
        assertEquals(1.05f, sized.distanceToWallEnd, tol)
        assertEquals(door.interiorNormal.y, sized.interiorNormal.y, tol)
        assertEquals(2.1f, sized.height, tol)
    }

    @Test
    fun flipSwapsRoomSideAndKeepsThePhysicalDoor() {
        val door = OpeningPlacement.place(room(), Vec2(1.55f, 0f), OpeningType.DOOR, "u1")!!
            .copy(swing = DoorSwing(Hinge.LEFT, inward = true))
        val flipped = door.flipped()
        assertEquals(-door.interiorNormal.y, flipped.interiorNormal.y, tol)
        assertEquals(door.width, flipped.width, tol)
        assertEquals(door.distanceToWallEnd, flipped.distanceFromWallStart, tol)
        assertEquals(DoorSwing(Hinge.RIGHT, inward = false), flipped.swing)
        // Same hinge jamb and the leaf still on the same side of the wall.
        assertEquals(door.leaves.single().hinge, flipped.leaves.single().hinge)
        assertEquals(door.leaves.single().open, flipped.leaves.single().open)
        assertEquals(door, flipped.flipped())
    }

    @Test
    fun retypeKeepsSillAndSwingOnlyWhereTheyApply() {
        val door = OpeningPlacement.place(room(), Vec2(1.55f, 0f), OpeningType.DOOR, "u1")!!
            .copy(swing = DoorSwing(Hinge.BOTH, inward = false))
        val window = door.withType(OpeningType.WINDOW).copy(bottom = 0.9f)
        assertNull(window.swing)
        assertEquals(0f, window.withType(OpeningType.DOOR).bottom, 0f)
        assertEquals(door.swing, door.withType(OpeningType.DOOR).swing)
    }

    @Test
    fun manualOpeningsNeverNeedChecking() {
        val low = Opening("a", OpeningType.DOOR, Vec2.ZERO, Vec2(1f, 0f), 0f, 2f, Vec2.ZERO, Vec2(3f, 0f), confidence = 0.4f)
        assertTrue(low.needsCheck)
        assertFalse(low.copy(manual = true).needsCheck)
        assertFalse(low.copy(confidence = 0.9f).needsCheck)
        assertEquals(0.5f, low.distanceTo(Vec2(0.5f, 0.5f)), tol)
        assertEquals(1f, low.distanceTo(Vec2(2f, 0f)), tol)
    }
}
