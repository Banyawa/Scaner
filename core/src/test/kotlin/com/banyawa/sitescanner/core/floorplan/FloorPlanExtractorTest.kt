package com.banyawa.sitescanner.core.floorplan

import com.banyawa.sitescanner.core.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2

class FloorPlanExtractorTest {
    private val extractor = FloorPlanExtractor()

    @Test
    fun levelsFindFloorAndCeiling() {
        val cloud = SyntheticRoom.rectangle(4f, 3f)
        val levels = assertNotNullReturn(LevelEstimator.estimate(cloud))
        assertEquals(-1.4f, levels.floorY, 0.02f)
        assertEquals(1.2f, levels.ceilingY!!, 0.02f)
    }

    @Test
    fun rotatedRectangleGivesFourAxisAlignedWallsWithCorrectLengths() {
        val result = extractor.extract(SyntheticRoom.rectangle(4f, 3f, yawDeg = 20f))
        val plan = result.plan
        assertEquals("walls: ${plan.walls}", 4, plan.walls.size)
        assertEquals(20.0, Math.toDegrees(plan.alignment.yawRad.toDouble()), 0.3)
        assertEquals(2.6f, plan.roomHeight!!, 0.03f)

        val lengths = plan.walls.map { it.length }.sorted()
        assertEquals(3f, lengths[0], 0.03f)
        assertEquals(3f, lengths[1], 0.03f)
        assertEquals(4f, lengths[2], 0.03f)
        assertEquals(4f, lengths[3], 0.03f)
        for (w in plan.walls) {
            val d = w.direction
            assertTrue("wall not axis aligned: $w", abs(d.x) < 0.01f || abs(d.y) < 0.01f)
        }
        // Corners are closed: every endpoint coincides with another wall's endpoint.
        val ends = plan.walls.flatMap { listOf(it.start, it.end) }
        for (e in ends) {
            assertTrue("open corner at $e", ends.count { it.distanceTo(e) < 0.01f } >= 2)
        }
    }

    @Test
    fun doorOpeningSplitsWall() {
        val door = SyntheticRoom.Opening(edge = 0, from = 1.5f, to = 2.4f)
        val plan = extractor.extract(SyntheticRoom.rectangle(4f, 3f, yawDeg = -35f, openings = listOf(door))).plan
        assertEquals("walls: ${plan.walls}", 5, plan.walls.size)
        val lengths = plan.walls.map { it.length }.sorted()
        assertEquals(1.5f, lengths[0], 0.04f)
        assertEquals(1.6f, lengths[1], 0.04f)
    }

    @Test
    fun obliqueWallIsDetected() {
        val outline = listOf(Vec2(0f, 0f), Vec2(4f, 0f), Vec2(4f, 2f), Vec2(3f, 3f), Vec2(0f, 3f))
        val plan = extractor.extract(SyntheticRoom.polygon(outline, yawDeg = 10f)).plan
        assertEquals("walls: ${plan.walls}", 5, plan.walls.size)
        val oblique = plan.walls.filter {
            val deg = Math.toDegrees(atan2(it.direction.y, it.direction.x).toDouble())
            val m = ((deg % 90) + 90) % 90
            m in 20.0..70.0
        }
        assertEquals(1, oblique.size)
        assertEquals(1.414f, oblique[0].length, 0.05f)
    }

    @Test
    fun alignmentMapsArWorldToSiteFrame() {
        val a = SiteAlignment(yawRad = Math.toRadians(90.0).toFloat(), floorY = -1f)
        val site = a.toSite(com.banyawa.sitescanner.core.geometry.Vec3(1f, 0.5f, 0f))
        assertEquals(0f, site.x, 1e-6f)
        assertEquals(-1f, site.y, 1e-6f)
        assertEquals(1.5f, site.z, 1e-6f)
        // ARCore -Z (forward at session start) is plan +Y (north) without rotation.
        val north = SiteAlignment.IDENTITY.toPlan(0f, -2f)
        assertEquals(Vec2(0f, 2f), north)
    }

    @Test
    fun emptyCloudGivesEmptyPlan() {
        val result = extractor.extract(com.banyawa.sitescanner.core.pointcloud.PointCloud.EMPTY)
        assertTrue(result.plan.walls.isEmpty())
    }

    private fun <T> assertNotNullReturn(v: T?): T {
        assertNotNull(v)
        return v!!
    }
}
