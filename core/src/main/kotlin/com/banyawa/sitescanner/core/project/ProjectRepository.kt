package com.banyawa.sitescanner.core.project

import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * File-based project store:
 * ```
 * <root>/<projectId>/project.json
 * <root>/<projectId>/scan_<scanId>.ply
 * <root>/<projectId>/media/IMG_<date>_<time>.jpg, VID_<date>_<time>.mp4
 * ```
 * Plain files keep projects easy to back up or copy off the device.
 */
class ProjectRepository(private val rootDir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        rootDir.mkdirs()
    }

    fun projectDir(projectId: String) = File(rootDir, projectId)

    fun scanFile(projectId: String, scan: ScanInfo) = File(projectDir(projectId), scan.pointFile)

    /** Where [scan]'s surface mesh is (or goes, see [meshFileName]); null without a mesh. */
    fun meshFile(projectId: String, scan: ScanInfo): File? = scan.meshFile?.let { File(projectDir(projectId), it) }

    /** The mesh's photo texture, when it has one. */
    fun atlasFile(projectId: String, scan: ScanInfo): File? = scan.meshAtlas?.let { File(projectDir(projectId), it) }

    fun atlasFileName(scan: ScanInfo) = "scan_${scan.id}_atlas.jpg"

    /** The recorded walk-through of [scan]; null when none was kept. */
    fun captureDir(projectId: String, scan: ScanInfo): File? = scan.captureDir?.let { File(projectDir(projectId), it) }

    fun captureDirName(scan: ScanInfo) = "capture_${scan.id}"

    /** A folder for a recording whose scan does not exist yet; renamed to [captureDirName] on saving. */
    fun newCaptureDir(projectId: String): File = File(projectDir(projectId), "$TEMP_CAPTURE_PREFIX${newId()}")

    /** Deletes recordings of scans that were never saved (the app was left or died mid-scan). */
    fun dropAbandonedCaptures(projectId: String, olderThanMs: Long = 60 * 60 * 1000L) {
        val cutoff = clock() - olderThanMs
        projectDir(projectId).listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(TEMP_CAPTURE_PREFIX) && it.lastModified() < cutoff }
            ?.forEach { it.deleteRecursively() }
    }

    @Synchronized
    fun list(): List<Project> =
        (rootDir.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { read(File(it, PROJECT_FILE)) }
            .sortedByDescending { it.updatedAt }

    @Synchronized
    fun get(projectId: String): Project? = read(File(projectDir(projectId), PROJECT_FILE))

    @Synchronized
    fun create(name: String, location: String = "", notes: String = "", pin: GeoPin? = null): Project {
        val now = clock()
        val project = Project(
            id = newId(),
            name = name.trim(),
            location = location.trim(),
            notes = notes.trim(),
            createdAt = now,
            updatedAt = now,
            pin = pin,
        )
        write(project)
        return project
    }

    @Synchronized
    fun update(project: Project): Project {
        val updated = project.copy(updatedAt = clock())
        write(updated)
        return updated
    }

    @Synchronized
    fun delete(projectId: String): Boolean {
        val dir = projectDir(projectId)
        return dir.exists() && dir.deleteRecursively()
    }

    /** Creates a scan record whose point file name is derived from its id. */
    fun newScan(name: String, createdAt: Long = clock()): ScanInfo {
        val id = newId()
        return ScanInfo(id = id, name = name, createdAt = createdAt, pointFile = "scan_$id.ply")
    }

    fun meshFileName(scan: ScanInfo) = "scan_${scan.id}_mesh.ply"

    @Synchronized
    fun upsertScan(projectId: String, scan: ScanInfo): Project {
        val project = get(projectId) ?: error("Project $projectId not found")
        val scans = project.scans.filterNot { it.id == scan.id } + scan
        return update(project.copy(scans = scans.sortedBy { it.createdAt }))
    }

    @Synchronized
    fun deleteScan(projectId: String, scanId: String): Project? {
        val project = get(projectId) ?: return null
        val scan = project.scan(scanId) ?: return project
        scanFile(projectId, scan).delete()
        meshFile(projectId, scan)?.delete()
        atlasFile(projectId, scan)?.delete()
        captureDir(projectId, scan)?.deleteRecursively()
        return update(project.copy(scans = project.scans.filterNot { it.id == scanId }))
    }

    /** The project's photos and videos folder, created on demand. */
    fun mediaDir(projectId: String): File = File(projectDir(projectId), MEDIA_DIR).apply { mkdirs() }

    /** Where [item]'s photo or video is (or goes); [mediaDir] must exist before writing it. */
    fun mediaFile(projectId: String, item: MediaItem): File = File(File(projectDir(projectId), MEDIA_DIR), item.fileName)

    /**
     * A record for a new photo or video, named `IMG_yyyyMMdd_HHmmss.<extension>` (`VID_` for
     * videos) after [createdAt], with a counter appended while that name is taken. The caller
     * writes the file at [mediaFile], then [addMedia] keeps the record.
     */
    fun newMedia(projectId: String, kind: MediaKind, extension: String, createdAt: Long = clock()): MediaItem {
        val prefix = if (kind == MediaKind.PHOTO) "IMG" else "VID"
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(createdAt))
        val ext = extension.trim().trimStart('.').lowercase(Locale.US).ifEmpty { if (kind == MediaKind.PHOTO) "jpg" else "mp4" }
        val dir = mediaDir(projectId)
        var name = "${prefix}_$stamp.$ext"
        var n = 1
        while (File(dir, name).exists()) name = "${prefix}_${stamp}_${n++}.$ext"
        return MediaItem(id = newId(), fileName = name, kind = kind, createdAt = createdAt)
    }

    /** Adds [item] to the project, or replaces the record with the same id (e.g. an edited note). */
    @Synchronized
    fun addMedia(projectId: String, item: MediaItem): Project {
        val project = get(projectId) ?: error("Project $projectId not found")
        val media = project.media.filterNot { it.id == item.id } + item
        return update(project.copy(media = media.sortedBy { it.createdAt }))
    }

    /** Removes the record and deletes its file; null when the project does not exist. */
    @Synchronized
    fun removeMedia(projectId: String, mediaId: String): Project? {
        val project = get(projectId) ?: return null
        val item = project.mediaItem(mediaId) ?: return project
        mediaFile(projectId, item).delete()
        return update(project.copy(media = project.media.filterNot { it.id == mediaId }))
    }

    private fun read(file: File): Project? =
        try {
            if (file.isFile) json.decodeFromString(Project.serializer(), file.readText()) else null
        } catch (e: Exception) {
            null
        }

    private fun write(project: Project) {
        val dir = projectDir(project.id).apply { mkdirs() }
        val tmp = File(dir, "$PROJECT_FILE.tmp")
        tmp.writeText(json.encodeToString(Project.serializer(), project))
        val target = File(dir, PROJECT_FILE)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "Could not write ${target.path}" }
        }
    }

    private fun newId() = UUID.randomUUID().toString().replace("-", "").take(12)

    companion object {
        const val PROJECT_FILE = "project.json"
        const val MEDIA_DIR = "media"
        private const val TEMP_CAPTURE_PREFIX = "capture_tmp_"
    }
}
