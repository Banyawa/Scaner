package com.banyawa.sitescanner.data

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import com.banyawa.sitescanner.R
import com.banyawa.sitescanner.core.export.ElevationDxf
import com.banyawa.sitescanner.core.export.FloorPlanDxf
import com.banyawa.sitescanner.core.export.MeasurementCsv
import com.banyawa.sitescanner.core.export.OpeningCsv
import com.banyawa.sitescanner.core.export.Ply
import com.banyawa.sitescanner.core.export.Pts
import com.banyawa.sitescanner.core.export.WallsObj
import com.banyawa.sitescanner.core.floorplan.OpeningTags
import com.banyawa.sitescanner.core.project.Project
import com.banyawa.sitescanner.core.project.ScanInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class ExportFormat(val extension: String, val mimeType: String, @StringRes val label: Int) {
    DXF_PLAN("dxf", "application/dxf", R.string.export_dxf),
    DXF_ELEVATIONS("dxf", "application/dxf", R.string.export_elevations_dxf),
    PLY("ply", "application/octet-stream", R.string.export_ply),
    PTS("pts", "text/plain", R.string.export_pts),
    OBJ_WALLS("obj", "text/plain", R.string.export_obj),
    CSV_MEASUREMENTS("csv", "text/csv", R.string.export_csv),
    CSV_OPENINGS("csv", "text/csv", R.string.export_openings_csv),
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
        val result = analysis.floorPlan(project.id, scan)
        val alignment = result.plan.alignment
        val elevations = when (format) {
            ExportFormat.DXF_PLAN, ExportFormat.DXF_ELEVATIONS -> analysis.elevations(project.id, scan)
            else -> emptyList()
        }
        val title = "${project.name} - ${scan.name}" + (project.pin?.let { " (${it.coordinates})" } ?: "")
        return withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val suffix = when (format) {
                ExportFormat.DXF_PLAN -> "plan"
                ExportFormat.DXF_ELEVATIONS -> "elevations"
                ExportFormat.OBJ_WALLS -> "walls"
                ExportFormat.CSV_MEASUREMENTS -> "measurements"
                ExportFormat.CSV_OPENINGS -> "doors_windows"
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
            }
            file
        }
    }

    private fun safeName(s: String) =
        s.trim().replace(Regex("[^\\p{L}\\p{M}\\p{N}._-]+"), "_").trim('_').ifEmpty { "scan" }.take(40)

    companion object {
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
