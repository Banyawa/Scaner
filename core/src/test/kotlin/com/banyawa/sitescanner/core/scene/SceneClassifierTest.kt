package com.banyawa.sitescanner.core.scene

import com.banyawa.sitescanner.core.export.FloorPlanDxf
import com.banyawa.sitescanner.core.export.ObjectCsv
import com.banyawa.sitescanner.core.floorplan.FloorPlan
import com.banyawa.sitescanner.core.floorplan.SiteAlignment
import com.banyawa.sitescanner.core.floorplan.WallSegment
import com.banyawa.sitescanner.core.geometry.Vec2
import com.banyawa.sitescanner.core.geometry.Vec3
import com.banyawa.sitescanner.core.mesh.TriangleMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

class SceneClassifierTest {
    private val width = 6f
    private val depth = 3f
    private val height = 2.8f

    /** The scan's heading and floor are arbitrary in ARCore's frame; the plan frame is what counts. */
    private val alignment = SiteAlignment(yawRad = Math.toRadians(30.0).toFloat(), floorY = -1.3f)

    private val plan = FloorPlan(
        walls = listOf(
            WallSegment(Vec2(0f, 0f), Vec2(width, 0f)),
            WallSegment(Vec2(width, 0f), Vec2(width, depth)),
            WallSegment(Vec2(width, depth), Vec2(0f, depth)),
            WallSegment(Vec2(0f, depth), Vec2(0f, 0f)),
        ),
        alignment = alignment,
        ceilingY = alignment.floorY + height,
    )

    /**
     * A 6 × 3 × 2.8 m room with a machine 0.5 m from the south wall, a table on legs, a beam
     * across the room under the ceiling and a partition the plan does not know about.
     */
    private fun scene(step: Float = 0.1f): SceneMeshBuilder {
        val b = SceneMeshBuilder(alignment, step)
        b.room(width, depth, height)
        b.box(Vec3(1.0f, 0.5f, 0f), Vec3(2.2f, 1.3f, 1.5f), MACHINE)
        // Table: a 30 mm slab on four 40 mm legs.
        b.box(Vec3(4.0f, 1.8f, 0.72f), Vec3(4.8f, 2.4f, 0.75f), TABLE, bottom = true)
        for (lx in listOf(4.05f, 4.71f)) for (ly in listOf(1.85f, 2.31f)) {
            b.box(Vec3(lx, ly, 0f), Vec3(lx + 0.04f, ly + 0.04f, 0.72f), TABLE, top = false)
        }
        // Beam 300 mm wide, 300 mm deep, across the room at x = 3.0..3.3.
        b.patch(Vec3(3.0f, 0f, 2.5f), Vec3(0.3f, 0f, 0f), Vec3(0f, depth, 0f), BEAM)
        b.patch(Vec3(3.0f, 0f, 2.5f), Vec3(0f, depth, 0f), Vec3(0f, 0f, 0.3f), BEAM)
        b.patch(Vec3(3.3f, 0f, 2.5f), Vec3(0f, depth, 0f), Vec3(0f, 0f, 0.3f), BEAM)
        // A floor-to-ceiling partition the plan extractor missed.
        b.patch(Vec3(5.0f, 0.5f, 0f), Vec3(0f, 2f, 0f), Vec3(0f, 0f, height), PARTITION)
        return b
    }

    private fun share(b: SceneMeshBuilder, layers: SceneLayers, tag: String, layer: SceneLayer): Float {
        val tris = b.triangles(tag)
        return tris.count { layers.layerOf(it) == layer } / tris.size.toFloat()
    }

    private fun assertShare(b: SceneMeshBuilder, layers: SceneLayers, tag: String, layer: SceneLayer, atLeast: Float = 0.95f) {
        val s = share(b, layers, tag, layer)
        assertTrue("$tag as $layer: ${"%.3f".format(s)}", s >= atLeast)
    }

    @Test
    fun roomSurfacesAndObjectsAreSortedOut() {
        val b = scene()
        val mesh = b.build()
        val layers = SceneClassifier.classify(mesh, plan)
        assertEquals(mesh.triangleCount, layers.triangleCount)

        assertShare(b, layers, SceneMeshBuilder.FLOOR, SceneLayer.FLOOR)
        assertShare(b, layers, SceneMeshBuilder.CEILING, SceneLayer.CEILING)
        assertShare(b, layers, SceneMeshBuilder.WALL, SceneLayer.WALL)
        assertShare(b, layers, PARTITION, SceneLayer.WALL)
        // The beam is overhead: its underside is in the ceiling band, its sides are object surface.
        assertEquals(0, b.triangles(BEAM).count { layers.layerOf(it) == SceneLayer.FLOOR })
        assertTrue(b.triangles(BEAM).count { layers.layerOf(it) == SceneLayer.CEILING || layers.layerOf(it) == SceneLayer.OBJECT } >= b.triangles(BEAM).size * 0.9)
        assertShare(b, layers, MACHINE, SceneLayer.OBJECT)
        assertShare(b, layers, TABLE, SceneLayer.OBJECT)

        assertEquals("objects: ${layers.objects}", 2, layers.objects.size)
        val machine = layers.objects[0]
        val table = layers.objects[1]
        assertEquals(1, machine.id)
        assertEquals(2, table.id)

        assertEquals(1.2f, machine.box.length, 0.06f)
        assertEquals(0.8f, machine.box.width, 0.06f)
        assertEquals(1.5f, machine.height, 0.05f)
        assertEquals(0f, machine.bottom, 0.05f)
        assertEquals(1.5f, machine.top, 0.05f)
        assertEquals(1.6f, machine.box.centre.x, 0.05f)
        assertEquals(0.9f, machine.box.centre.y, 0.05f)
        assertTrue("machine yaw ${machine.box.yawRad}", abs(sin(machine.box.yawRad)) < 0.05f)
        assertEquals(0.5f, machine.wallClearance!!, 0.06f)
        assertEquals(0.96f, machine.footprintArea, 0.1f)

        assertEquals(0.8f, table.box.length, 0.06f)
        assertEquals(0.6f, table.box.width, 0.06f)
        assertEquals(0.75f, table.height, 0.05f)
        assertEquals(0.75f, table.top, 0.05f)
        assertEquals(4.4f, table.box.centre.x, 0.05f)
        assertEquals(2.1f, table.box.centre.y, 0.05f)
        assertEquals(0.6f, table.wallClearance!!, 0.06f)

        for (o in layers.objects) {
            assertTrue("footprint is a polygon", o.footprint.size >= 3)
            assertTrue("footprint counter-clockwise", signedArea(o.footprint) > 0f)
            assertTrue("triangles counted", o.triangleCount > 60)
        }

        val ceiling = assertNotNullReturn(layers.ceiling)
        assertEquals(2.8f, ceiling.typicalHeight, 0.06f)
        assertEquals(2.5f, ceiling.minHeight, 0.06f)
        val minAt = assertNotNullReturn(ceiling.minAt)
        assertTrue("lowest point under the beam: $minAt", minAt.x > 2.9f && minAt.x < 3.4f)
        assertEquals(2.5f, ceiling.heightAt(Vec2(3.15f, 1.5f)), 0.06f)
        assertEquals(2.8f, ceiling.heightAt(Vec2(1.0f, 2.0f)), 0.06f)
        assertTrue(ceiling.heightAt(Vec2(-1f, 1f)).isNaN())

        val floorArea = width * depth
        assertEquals(floorArea, layers.area(SceneLayer.FLOOR), floorArea * 0.1f)
        // The ceiling proper plus the beam's underside.
        assertEquals(floorArea + 0.3f * depth, layers.area(SceneLayer.CEILING), floorArea * 0.1f)
        val wallArea = 2 * (width + depth) * height + 2f * height
        assertEquals(wallArea, layers.area(SceneLayer.WALL), wallArea * 0.1f)
        assertTrue(layers.area(SceneLayer.OBJECT) > 5f)
        assertEquals(layers.triangleCount, SceneLayer.entries.sumOf { layers.count(it) })

        val csv = ObjectCsv.toString(layers.objects).trim().lines()
        assertEquals(3, csv.size)
        assertEquals(ObjectCsv.HEADER, csv[0])
        val m1 = csv[1].split(',')
        assertEquals(11, m1.size)
        assertEquals("M1", m1[0])
        assertEquals(1200f, m1[1].toFloat(), 60f)
        assertEquals(800f, m1[2].toFloat(), 60f)
        assertEquals(1500f, m1[3].toFloat(), 50f)
        assertEquals(500f, m1[10].toFloat(), 60f)
        assertTrue(csv[2].startsWith("M2,"))

        val dxf = FloorPlanDxf.build(plan, objects = layers.objects, ceiling = layers.ceiling, options = FloorPlanDxf.Options(title = "Scene")).toString()
        File("build/test-output").mkdirs()
        File("build/test-output/scene.dxf").writeText(dxf)
        for (layer in listOf(FloorPlanDxf.LAYER_OBJECTS, FloorPlanDxf.LAYER_OBJECT_TAGS, FloorPlanDxf.LAYER_CEILING)) {
            assertTrue("missing layer $layer", dxf.contains("\n$layer\n"))
        }
        assertTrue(dxf.contains("\nM1\n") && dxf.contains("\nM2\n"))
        assertTrue(dxf.contains("\nPOLYLINE\n") && dxf.contains("\nSEQEND\n"))
        assertTrue(dxf.contains("OBJECT SCHEDULE"))
        assertTrue(dxf.contains("Clear height: typical 2800 mm"))
        assertTrue("low point called out", Regex("\nmin 2[45]\\d\\d mm\n").containsMatchIn(dxf))
        assertEquals(0, dxf.lines().dropLastWhile { it.isEmpty() }.size % 2)
        val withGrid = FloorPlanDxf.build(plan, objects = layers.objects, ceiling = layers.ceiling, options = FloorPlanDxf.Options(ceilingGrid = true)).toString()
        assertTrue("grid heights written", withGrid.split("\nTEXT\n").size > dxf.split("\nTEXT\n").size + 10)
    }

    @Test
    fun withoutPlanWallsTheFullHeightSurfacesAreWalls() {
        val b = scene()
        val mesh = b.build()
        val layers = SceneClassifier.classify(mesh, plan.copy(walls = emptyList()))
        assertShare(b, layers, SceneMeshBuilder.FLOOR, SceneLayer.FLOOR)
        assertShare(b, layers, SceneMeshBuilder.CEILING, SceneLayer.CEILING)
        assertShare(b, layers, SceneMeshBuilder.WALL, SceneLayer.WALL)
        assertShare(b, layers, PARTITION, SceneLayer.WALL)
        assertShare(b, layers, MACHINE, SceneLayer.OBJECT)
        assertEquals(2, layers.objects.size)
        for (o in layers.objects) assertNull(o.wallClearance)
        assertEquals(1.2f, layers.objects[0].box.length, 0.06f)
        val ceiling = assertNotNullReturn(layers.ceiling)
        assertEquals(2.5f, ceiling.minHeight, 0.06f)
        assertEquals(2.8f, ceiling.typicalHeight, 0.06f)
    }

    @Test
    fun ceilingHeightIsEstimatedWhenThePlanHasNone() {
        val b = scene()
        val layers = SceneClassifier.classify(b.build(), plan.copy(walls = emptyList(), ceilingY = null))
        assertShare(b, layers, SceneMeshBuilder.CEILING, SceneLayer.CEILING)
        assertShare(b, layers, SceneMeshBuilder.WALL, SceneLayer.WALL)
        assertShare(b, layers, SceneMeshBuilder.FLOOR, SceneLayer.FLOOR)
        assertEquals(2, layers.objects.size)
        assertEquals(2.8f, assertNotNullReturn(layers.ceiling).typicalHeight, 0.06f)
    }

    @Test
    fun emptyMeshGivesEmptyLayers() {
        val layers = SceneClassifier.classify(TriangleMesh.EMPTY, plan.copy(walls = emptyList()))
        assertEquals(0, layers.triangleCount)
        assertTrue(layers.objects.isEmpty())
        assertNull(layers.ceiling)
        for (layer in SceneLayer.entries) assertEquals(0f, layers.area(layer), 0f)
        assertEquals("", ObjectCsv.toString(emptyList()).lines()[1])
        val dxf = FloorPlanDxf.build(plan.copy(walls = emptyList()), objects = layers.objects, ceiling = layers.ceiling).toString()
        assertTrue(!dxf.contains("\n${FloorPlanDxf.LAYER_OBJECTS}\n"))
    }

    @Test
    fun footprintsAndBoxes() {
        // A rotated rectangle with points inside: the hull is its four corners, the box its size and angle.
        val corners = listOf(Vec2(0f, 0f), Vec2(2f, 0f), Vec2(2f, 1f), Vec2(0f, 1f)).map { it.rotated(0.5f) + Vec2(3f, 4f) }
        val inside = (1..20).map { Vec2(0.09f * it, 0.5f - 0.01f * it).rotated(0.5f) + Vec2(3f, 4f) }
        val pts = (corners + inside).shuffled(java.util.Random(3))
        val xy = FloatArray(pts.size * 2)
        for ((i, p) in pts.withIndex()) {
            xy[i * 2] = p.x
            xy[i * 2 + 1] = p.y
        }
        val hull = Footprints.hull(xy, pts.size)
        assertEquals(4, hull.size)
        assertTrue(signedArea(hull) > 0f)
        assertEquals(2f, SceneObject.polygonArea(hull), 1e-3f)
        val box = Footprints.box(hull)
        assertEquals(2f, box.length, 1e-3f)
        assertEquals(1f, box.width, 1e-3f)
        assertEquals(0.5f, box.yawRad, 1e-3f)
        assertEquals(3f + Vec2(1f, 0.5f).rotated(0.5f).x, box.centre.x, 1e-3f)
        assertEquals(4f + Vec2(1f, 0.5f).rotated(0.5f).y, box.centre.y, 1e-3f)
        for (c in box.corners()) assertTrue(hull.any { it.distanceTo(c) < 1e-3f })

        // Degenerate inputs do not break: a single point, two points.
        assertEquals(1, Footprints.hull(floatArrayOf(1f, 1f, 1f, 1f), 2).size)
        val two = Footprints.hull(floatArrayOf(0f, 0f, 0f, 3f), 2)
        assertEquals(2, two.size)
        val thin = Footprints.box(two)
        assertEquals(3f, thin.length, 1e-4f)
        assertEquals(0f, thin.width, 1e-4f)
    }

    @Test
    fun aLargeSceneIsClassifiedQuickly() {
        val b = scene(step = 0.025f)
        val mesh = b.build()
        assertTrue("triangles: ${mesh.triangleCount}", mesh.triangleCount > 250_000)
        // A factory floor's worth of wall segments: each wall in ten pieces.
        val pieces = plan.copy(
            walls = plan.walls.flatMap { w ->
                (0 until 10).map { i -> WallSegment(Vec2.lerp(w.start, w.end, i / 10f), Vec2.lerp(w.start, w.end, (i + 1) / 10f)) }
            },
        )
        val start = System.nanoTime()
        val layers = SceneClassifier.classify(mesh, pieces)
        val ms = (System.nanoTime() - start) / 1_000_000
        println("PERF scene: ${mesh.triangleCount} triangles, ${pieces.walls.size} walls: $ms ms")
        assertEquals(2, layers.objects.size)
        assertShare(b, layers, SceneMeshBuilder.WALL, SceneLayer.WALL)
        assertTrue("took $ms ms", ms < 5_000)
    }

    private fun signedArea(polygon: List<Vec2>): Float {
        var sum = 0f
        for (i in polygon.indices) sum += polygon[i] cross polygon[(i + 1) % polygon.size]
        return sum / 2f
    }

    private fun <T> assertNotNullReturn(v: T?): T {
        assertNotNull(v)
        return v!!
    }

    private companion object {
        const val MACHINE = "machine"
        const val TABLE = "table"
        const val BEAM = "beam"
        const val PARTITION = "partition"
    }
}
