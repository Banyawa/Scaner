package com.banyawa.sitescanner.core.project

import com.banyawa.sitescanner.core.floorplan.Opening
import com.banyawa.sitescanner.core.floorplan.OpeningEdits
import com.banyawa.sitescanner.core.floorplan.OpeningType
import com.banyawa.sitescanner.core.floorplan.SiteAlignment
import com.banyawa.sitescanner.core.geometry.Vec2
import com.banyawa.sitescanner.core.geometry.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000L
    private fun repo() = ProjectRepository(tmp.root) { now }

    @Test
    fun createListAndReload() {
        val repo = repo()
        val a = repo.create("  คอนโด ชั้น 5 ", "Bangkok")
        now += 10
        val b = repo.create("Warehouse")
        assertEquals("คอนโด ชั้น 5", a.name)
        assertEquals(listOf(b.id, a.id), repo.list().map { it.id })
        assertEquals(a, ProjectRepository(tmp.root).get(a.id))
    }

    @Test
    fun scansAreAddedUpdatedAndDeleted() {
        val repo = repo()
        val p = repo.create("P")
        val scan = repo.newScan("Scan 1").copy(
            pointCount = 3,
            measurements = listOf(Measurement("m", "w", Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f))),
        )
        repo.scanFile(p.id, scan).writeText("ply")
        now += 5
        val updated = repo.upsertScan(p.id, scan)
        assertEquals(1, updated.scans.size)
        assertEquals(now, updated.updatedAt)
        assertEquals(1f, repo.get(p.id)!!.scans[0].measurements[0].lengthM, 1e-6f)

        repo.upsertScan(p.id, scan.copy(name = "Renamed"))
        assertEquals(listOf("Renamed"), repo.get(p.id)!!.scans.map { it.name })

        val file = repo.scanFile(p.id, scan)
        assertTrue(file.exists())
        repo.deleteScan(p.id, scan.id)
        assertFalse(file.exists())
        assertTrue(repo.get(p.id)!!.scans.isEmpty())

        assertTrue(repo.delete(p.id))
        assertNull(repo.get(p.id))
    }

    @Test
    fun openingEditsSurviveReload() {
        val repo = repo()
        val p = repo.create("P")
        val alignment = SiteAlignment(yawRad = 0.4f, floorY = -1.3f)
        val window = Opening("op1", OpeningType.WINDOW, Vec2(1f, 0f), Vec2(0f, 0f), 0.9f, 2f, Vec2(3f, 0f), Vec2(-1f, 0f), 0.8f)
        val scan = repo.newScan("S").copy(openingEdits = OpeningEdits(alignment, listOf(window)))
        repo.upsertScan(p.id, scan)

        val loaded = ProjectRepository(tmp.root).get(p.id)!!.scans.single().openingEdits!!
        assertEquals(listOf(window), loaded.openings)
        // Transient rotation terms are rebuilt after deserialisation.
        assertEquals(alignment.toPlan(1f, 2f), loaded.alignment.toPlan(1f, 2f))
        assertNull(repo.newScan("T").openingEdits)
    }

    @Test
    fun corruptProjectIsSkipped() {
        val repo = repo()
        repo.create("ok")
        File(tmp.root, "broken").mkdirs()
        File(tmp.root, "broken/project.json").writeText("{not json")
        assertEquals(1, repo.list().size)
    }
}
