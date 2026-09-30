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

        assertNull(repo.meshFile(p.id, scan))
        val meshed = scan.copy(
            name = "Renamed", meshFile = repo.meshFileName(scan), meshTriangles = 10, meshAtlas = repo.atlasFileName(scan),
            captureDir = repo.captureDirName(scan), captureFrames = 3,
        )
        repo.meshFile(p.id, meshed)!!.writeText("ply")
        val atlas = repo.atlasFile(p.id, meshed)!!.apply { writeText("jpg") }
        val capture = repo.captureDir(p.id, meshed)!!.apply { mkdirs() }
        File(capture, "frames.jsonl").writeText("{}")
        repo.upsertScan(p.id, meshed)
        assertEquals(listOf("Renamed"), repo.get(p.id)!!.scans.map { it.name })
        assertTrue(repo.get(p.id)!!.scans[0].hasMesh)
        assertTrue(repo.get(p.id)!!.scans[0].hasCapture)

        val file = repo.scanFile(p.id, scan)
        val mesh = repo.meshFile(p.id, meshed)!!
        assertTrue(file.exists() && mesh.exists() && capture.isDirectory)
        repo.deleteScan(p.id, scan.id)
        assertFalse(file.exists())
        assertFalse(mesh.exists())
        assertFalse(atlas.exists())
        assertFalse(capture.exists())
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
    fun sitePinSurvivesReloadAndOldProjectsHaveNone() {
        val repo = repo()
        val pin = GeoPin(13.756331, 100.501765, accuracyM = 8f)
        val p = repo.create("Site", "Bangkok", pin = pin)
        assertEquals(pin, ProjectRepository(tmp.root).get(p.id)!!.pin)
        assertEquals("13.756331, 100.501765", pin.coordinates)

        val moved = repo.update(p.copy(pin = GeoPin(13.7, 100.5)))
        assertNull(ProjectRepository(tmp.root).get(moved.id)!!.pin!!.accuracyM)

        // A project saved before pins existed still loads.
        File(tmp.root, "old").mkdirs()
        File(tmp.root, "old/project.json").writeText("""{"id":"old","name":"Old","createdAt":1,"updatedAt":1}""")
        assertNull(repo.get("old")!!.pin)
    }

    @Test
    fun mediaIsAddedRemovedWithItsFileAndDeletedWithTheProject() {
        val repo = repo()
        val p = repo.create("P")
        val photo = repo.newMedia(p.id, MediaKind.PHOTO, "jpg")
        assertEquals(File(repo.projectDir(p.id), "media"), repo.mediaDir(p.id))
        assertTrue(repo.mediaDir(p.id).isDirectory)
        assertTrue(photo.fileName, Regex("IMG_\\d{8}_\\d{6}\\.jpg").matches(photo.fileName))
        assertEquals(now, photo.createdAt)
        val photoFile = repo.mediaFile(p.id, photo).apply { writeText("jpg") }
        assertEquals(File(repo.mediaDir(p.id), photo.fileName), photoFile)

        now += 5
        val video = repo.newMedia(p.id, MediaKind.VIDEO, ".MP4").copy(note = "leak under the sink")
        assertTrue(video.fileName, Regex("VID_\\d{8}_\\d{6}\\.mp4").matches(video.fileName))
        repo.mediaFile(p.id, video).writeText("mp4")
        repo.addMedia(p.id, photo)
        val updated = repo.addMedia(p.id, video)
        assertEquals(listOf(photo, video), updated.media)
        assertEquals(now, updated.updatedAt)
        assertEquals(listOf(photo, video), ProjectRepository(tmp.root).get(p.id)!!.media)
        assertEquals("leak under the sink", repo.get(p.id)!!.mediaItem(video.id)!!.note)

        // Adding a record again replaces it, keeping the order by time.
        val noted = photo.copy(note = "north wall")
        assertEquals(listOf(noted, video), repo.addMedia(p.id, noted).media)

        assertEquals(listOf(video), repo.removeMedia(p.id, photo.id)!!.media)
        assertFalse(photoFile.exists())
        assertTrue(repo.mediaFile(p.id, video).exists())
        assertEquals(listOf(video), repo.removeMedia(p.id, "missing")!!.media)
        assertNull(repo.removeMedia("missing", video.id))

        val dir = repo.mediaDir(p.id)
        assertTrue(repo.delete(p.id))
        assertFalse(dir.exists())
    }

    @Test
    fun mediaNamesDoNotCollideAndOldProjectsHaveNone() {
        val repo = repo()
        val p = repo.create("P")
        // Two photos in the same second get different names; a default extension fills in for none.
        val first = repo.newMedia(p.id, MediaKind.PHOTO, "jpg")
        repo.mediaFile(p.id, first).writeText("1")
        val second = repo.newMedia(p.id, MediaKind.PHOTO, "jpg")
        assertEquals(first.fileName.removeSuffix(".jpg") + "_1.jpg", second.fileName)
        repo.mediaFile(p.id, second).writeText("2")
        assertEquals(first.fileName.removeSuffix(".jpg") + "_2.jpg", repo.newMedia(p.id, MediaKind.PHOTO, "jpg").fileName)
        assertTrue(repo.newMedia(p.id, MediaKind.VIDEO, "").fileName.endsWith(".mp4"))

        // A project saved before media existed still loads, without any.
        File(tmp.root, "old").mkdirs()
        File(tmp.root, "old/project.json").writeText("""{"id":"old","name":"Old","createdAt":1,"updatedAt":1,"scans":[]}""")
        assertTrue(repo.get("old")!!.media.isEmpty())
        assertFalse(repo.mediaFile("old", first).exists())
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
