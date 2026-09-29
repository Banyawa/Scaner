package com.banyawa.sitescanner.core.project

import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * File-based project store:
 * ```
 * <root>/<projectId>/project.json
 * <root>/<projectId>/scan_<scanId>.ply
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

    @Synchronized
    fun list(): List<Project> =
        (rootDir.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { read(File(it, PROJECT_FILE)) }
            .sortedByDescending { it.updatedAt }

    @Synchronized
    fun get(projectId: String): Project? = read(File(projectDir(projectId), PROJECT_FILE))

    @Synchronized
    fun create(name: String, location: String = "", notes: String = ""): Project {
        val now = clock()
        val project = Project(
            id = newId(),
            name = name.trim(),
            location = location.trim(),
            notes = notes.trim(),
            createdAt = now,
            updatedAt = now,
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
        return update(project.copy(scans = project.scans.filterNot { it.id == scanId }))
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
    }
}
