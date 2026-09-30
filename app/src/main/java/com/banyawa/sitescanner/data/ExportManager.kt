package com.banyawa.sitescanner.data

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import com.banyawa.sitescanner.R
import com.banyawa.sitescanner.core.capture.Capture
import com.banyawa.sitescanner.core.capture.CaptureFrame
import com.banyawa.sitescanner.core.capture.CaptureZip
import com.banyawa.sitescanner.core.export.ElevationDxf
import com.banyawa.sitescanner.core.export.FloorPlanDxf
import com.banyawa.sitescanner.core.export.Glb
import com.banyawa.sitescanner.core.export.MeasurementCsv
import com.banyawa.sitescanner.core.export.MeshFrames
import com.banyawa.sitescanner.core.export.MeshObj
import com.banyawa.sitescanner.core.export.MeshPly
import com.banyawa.sitescanner.core.export.ObjectCsv
import com.banyawa.sitescanner.core.export.OpeningCsv
import com.banyawa.sitescanner.core.export.Ply
import com.banyawa.sitescanner.core.export.Pts
import com.banyawa.sitescanner.core.export.WallsObj
import com.banyawa.sitescanner.core.floorplan.OpeningTags
import com.banyawa.sitescanner.core.project.Project
import com.banyawa.sitescanner.core.project.ScanInfo
import com.banyawa.sitescanner.core.scene.SceneLayers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** [needsMesh]: only for scans with a colour surface model; [needsCapture]: only with a recording. */
enum class ExportFormat(
    val extension: String,
    val mimeType: String,
    @StringRes val label: Int,
    val needsMesh: Boolean = false,
    val needsCapture: Boolean = false,
) {
    GLB_MODEL("glb", "model/gltf-binary", R.string.export_model_glb, needsMesh = true),
    OBJ_MODEL("obj", "text/plain", R.string.export_model_obj, needsMesh = true),
    PLY_MODEL("ply", "application/octet-stream", R.string.export_model_ply, needsMesh = true),
    DXF_PLAN("dxf", "application/dxf", R.string.export_dxf),
    DXF_ELEVATIONS("dxf", "application/dxf", R.string.export_elevations_dxf),
    PLY("ply", "application/octet-stream", R.string.export_ply),
    PTS("pts", "text/plain", R.string.export_pts),
    OBJ_WALLS("obj", "text/plain", R.string.export_obj),
    CSV_MEASUREMENTS("csv", "text/csv", R.string.export_csv),
    CSV_OPENINGS("csv", "text/csv", R.string.export_openings_csv),
    CSV_OBJECTS("csv", "text/csv", R.string.export_objects_csv, needsMesh = true),
    CAPTURE_ZIP("zip", "application/zip", R.string.export_capture_zip, needsCapture = true),
    CAPTURE_ZIP_SMALL("zip", "application/zip", R.string.export_capture_zip_small, needsCapture = true),
}

/**
 * Writes exports into the cache directory and hands them to other apps (LINE, Gmail,
 * Google Drive, ...) through a FileProvider share sheet.
 *
 * Every export uses the same site frame (Z up, floor at 0, walls axis-aligned, derived
 * from the floor plan), so files from one scan overlay exactly in CAD.
 */
class ExportManager(private val context: Context, private val analysis: ScanAnalysis) {

    suspend fun export(project: Project, scan: ScanInfo, format: ExportFormat): File {
        if (format == ExportFormat.CAPTURE_ZIP || format == ExportFormat.CAPTURE_ZIP_SMALL) {
            return exportCapture(project, scan, small = format == ExportFormat.CAPTURE_ZIP_SMALL)
        }
        val result = analysis.floorPlan(project.id, scan)
        val alignment = result.plan.alignment
        val elevations = when (format) {
            ExportFormat.DXF_PLAN, ExportFormat.DXF_ELEVATIONS -> analysis.elevations(project.id, scan)
            else -> emptyList()
        }
        // Objects and the ceiling map come from the surface model; the plan drawing does without when there is none.
        val scene: SceneLayers? = when (format) {
            ExportFormat.DXF_PLAN -> runCatching { analysis.sceneLayers(project.id, scan) }.getOrNull()
            ExportFormat.CSV_OBJECTS -> analysis.sceneLayers(project.id, scan)
                ?: throw IllegalStateException(context.getString(R.string.export_no_model))
            else -> null
        }
        val title ="${project.name} - ${scan.name}" + (project.pin?.let { " (${it.coordinates})" } ?: "")
        return withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val suffix = when (format) {
                ExportFormat.DXF_PLAN -> "plan"
                ExportFormat.DXF_ELEVATIONS -> "elevations"
                ExportFormat.OBJ_WALLS -> "walls"
                ExportFormat.CSV_MEASUREMENTS -> "measurements"
                ExportFormat.CSV_OPENINGS -> "doors_windows"
                ExportFormat.CSV_OBJECTS -> "objects"
                ExportFormat.GLB_MODEL, ExportFormat.OBJ_MODEL, ExportFormat.PLY_MODEL -> "model"
                else -> "points"
            }
            val file = File(dir, "${safeName(project.name)}_${safeName(scan.name)}_$suffix.${format.extension}")
            when (format) {
                ExportFormat.DXF_PLAN -> file.bufferedWriter().use { out ->
                    FloorPlanDxf.build(
                        result.plan,
                        result.slice,
                        scan.measurements,
                        FloorPlanDxf.Options(title = title),
                        elevations,
                        objects = scene?.objects.orEmpty(),
                        ceiling = scene?.ceiling,
                    ).write(out)
                }
                ExportFormat.DXF_ELEVATIONS -> file.bufferedWriter().use { out ->
                    ElevationDxf.build(elevations, OpeningTags.assign(result.plan.openings), ElevationDxf.Options(title = title)).write(out)
                }
                ExportFormat.PLY -> Ply.write(analysis.cloud(project.id, scan), file, alignment)
                ExportFormat.PTS -> file.bufferedWriter().use { Pts.write(analysis.cloud(project.id, scan), it, alignment) }
                ExportFormat.OBJ_WALLS -> file.bufferedWriter().use { WallsObj.write(result.plan, it, scan.measurements) }
                ExportFormat.CSV_MEASUREMENTS -> file.bufferedWriter().use {
                    MeasurementCsv.write(scan.measurements, alignment, it, scan.name)
                }
                ExportFormat.CSV_OPENINGS -> file.bufferedWriter().use {
                    OpeningCsv.write(result.plan.openings, it, scan.name)
                }
                ExportFormat.CSV_OBJECTS -> file.writeText(ObjectCsv.toString(scene?.objects.orEmpty()))
                ExportFormat.GLB_MODEL -> {
                    val model = mesh(project, scan)
                    val textured = model.textured
                    file.outputStream().use {
                        // The photo texture travels inside the GLB, so viewers show the model as photographed.
                        if (textured != null) {
                            Glb.write(textured.withMesh(MeshFrames.siteYUp(textured.mesh, alignment)), it, title, AndroidImageEncoder)
                        } else {
                            Glb.write(MeshFrames.siteYUp(model.mesh, alignment), it, title)
                        }
                    }
                }
                ExportFormat.OBJ_MODEL -> file.bufferedWriter().use { MeshObj.write(MeshFrames.siteYUp(mesh(project, scan).mesh, alignment), it) }
                ExportFormat.PLY_MODEL -> file.outputStream().use {
                    MeshPly.write(MeshFrames.siteZUp(mesh(project, scan).mesh, alignment), it, "frame site Z-up metres")
                }
            }
            file
        }
    }

    /**
     * The recorded walk-through as one zip: the frames as recorded plus the camera poses in
     * the formats PC photogrammetry tools read (COLMAP, nerfstudio), for a photo-quality model.
     */
    private suspend fun exportCapture(project: Project, scan: ScanInfo, small: Boolean): File = withContext(Dispatchers.IO) {
        val captureDir = analysis.repository.captureDir(project.id, scan)?.takeIf { it.isDirectory }
            ?: throw IllegalStateException(context.getString(R.string.export_no_capture))
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.deleteRecursively() }
        val capture = Capture.open(captureDir)
        // The small zip keeps every k-th frame so it stays under what chat apps and GitHub take.
        val frames = if (small) thinToBudget(capture, SMALL_ZIP_BYTES) else capture.frames
        val kept = frames.flatMap { listOfNotNull(it.image, it.depth, it.confidence, it.smoothDepth) }.toHashSet()
        val extra = PoseExportFiles.forCapture(captureDir, frames) + (Capture.FRAMES to Capture.encodeFrames(frames).toByteArray())
        val suffix = if (small) "recording_small" else "recording"
        val file = File(dir, "${safeName(project.name)}_${safeName(scan.name)}_$suffix.zip")
        file.outputStream().use { out ->
            CaptureZip.write(captureDir, out, extra) { path -> !path.startsWith("${Capture.FRAME_DIR}/") || path in kept }
        }
        file
    }

    /** Every k-th frame, k chosen so their files add up to about [budget] bytes. */
    private fun thinToBudget(capture: Capture, budget: Long): List<CaptureFrame> {
        val sizes = capture.frames.map { f ->
            listOfNotNull(f.image, f.depth, f.confidence, f.smoothDepth).sumOf { File(capture.dir, it).length() }
        }
        val total = sizes.sum()
        if (total <= budget) return capture.frames
        val step = ((total + budget - 1) / budget).toInt().coerceAtLeast(2)
        return capture.frames.filterIndexed { i, _ -> i % step == 0 }
    }

    private suspend fun mesh(project: Project, scan: ScanInfo) =
        analysis.mesh(project.id, scan) ?: throw IllegalStateException(context.getString(R.string.export_no_model))

    private fun safeName(s: String) =
        s.trim().replace(Regex("[^\\p{L}\\p{M}\\p{N}._-]+"), "_").trim('_').ifEmpty { "scan" }.take(40)

    companion object {
        /** GitHub's web upload takes files up to 25 MB; chat apps around that too. */
        private const val SMALL_ZIP_BYTES = 20L * 1024 * 1024

        /** Opens the system share sheet; pass an Activity context. */
        fun share(context: Context, file: File, format: ExportFormat) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType(format.mimeType)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, file.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val chooser = Intent.createChooser(send, context.getString(R.string.export_share_title))
            if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        }
    }
}

/** One-shot UI events emitted by screens that export. */
sealed interface ExportEvent {
    class Share(val file: File, val format: ExportFormat) : ExportEvent
    class Failed(val message: String) : ExportEvent
}
