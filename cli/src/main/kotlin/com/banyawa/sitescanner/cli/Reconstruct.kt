package com.banyawa.sitescanner.cli

import com.banyawa.sitescanner.core.capture.Capture
import com.banyawa.sitescanner.core.capture.CaptureZip
import com.banyawa.sitescanner.core.capture.ProgressListener
import com.banyawa.sitescanner.core.capture.ReconstructionOptions
import com.banyawa.sitescanner.core.capture.Reconstructor
import com.banyawa.sitescanner.core.export.Glb
import com.banyawa.sitescanner.core.export.MeshFrames
import com.banyawa.sitescanner.core.export.MeshObj
import com.banyawa.sitescanner.core.export.MeshPly
import com.banyawa.sitescanner.core.export.Ply
import com.banyawa.sitescanner.core.floorplan.FloorPlanExtractor
import com.banyawa.sitescanner.core.floorplan.SiteAlignment
import com.banyawa.sitescanner.core.mesh.MeshTexturer
import java.io.File
import kotlin.system.exitProcess

/**
 * `java -jar reconstruct.jar <capture.zip | capture folder> <output folder>`
 *
 * Generates from a recorded walk-through: `points.ply` (site frame, Z up), `model.glb` /
 * `model.obj` (site frame, Y up), `model.ply`, preview PNGs of the model, and the camera
 * poses for PC photogrammetry tools.
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: reconstruct <capture.zip | capture folder> <output folder> [--voxel <metres>] [--max-frames <n>]")
        exitProcess(2)
    }
    var options = ReconstructionOptions()
    var i = 2
    while (i < args.size) {
        when (args[i]) {
            "--voxel" -> options = options.copy(surfaceVoxelM = args[++i].toFloat())
            "--max-frames" -> options = options.copy(maxFrames = args[++i].toInt())
            else -> {
                System.err.println("unknown option ${args[i]}")
                exitProcess(2)
            }
        }
        i++
    }

    val input = File(args[0])
    val outDir = File(args[1]).apply { mkdirs() }
    val captureDir = if (input.isDirectory) {
        input
    } else {
        File(outDir, "capture").also { dir ->
            println("Unpacking ${input.name}…")
            input.inputStream().use { CaptureZip.read(it, dir) }
        }
    }
    val capture = Capture.open(captureDir)
    println("${capture.frames.size} frames, ${capture.frames.count { it.hasDepth }} with depth, device: ${capture.manifest.device}")
    if (capture.frames.isEmpty()) {
        System.err.println("No frames in the capture")
        exitProcess(1)
    }

    val start = System.currentTimeMillis()
    var lastLine = ""
    val progress = ProgressListener { stage, done, total ->
        val line = when (stage) {
            "fuse" -> "Fusing frames $done / $total"
            "texture" -> "Texturing $done / $total"
            else -> "Extracting surface"
        }
        if (line != lastLine) {
            println(line)
            lastLine = line
        }
    }
    val result = Reconstructor(JvmImageDecoder, options).reconstruct(capture, progress)
    println("Points: ${result.cloud.size}, model: ${result.mesh.triangleCount} triangles, ${(System.currentTimeMillis() - start) / 1000} s")

    val alignment = runCatching { FloorPlanExtractor().extract(result.cloud, result.floorY).plan.alignment }
        .getOrDefault(SiteAlignment(floorY = result.floorY ?: 0f))
    Ply.write(result.cloud, File(outDir, "points.ply"), alignment)
    if (!result.mesh.isEmpty()) {
        File(outDir, "model.glb").outputStream().use { Glb.write(MeshFrames.siteYUp(result.mesh, alignment), it, captureDir.name) }
        File(outDir, "model.obj").bufferedWriter().use { MeshObj.write(MeshFrames.siteYUp(result.mesh, alignment), it) }
        MeshPly.write(result.mesh, File(outDir, "model.ply"))
    }
    MeshPreview.renderViews(result.mesh.takeUnless { it.isEmpty() }, result.cloud, outDir)
    if (!result.mesh.isEmpty()) {
        val textured = runCatching { MeshTexturer(JvmImageDecoder).texture(result.mesh, capture, progress) }
            .onFailure { System.err.println("Texturing skipped: ${it.message}") }
            .getOrNull()
        if (textured != null) {
            File(outDir, "model_textured.glb").outputStream().use {
                Glb.write(textured.withMesh(MeshFrames.siteYUp(textured.mesh, alignment)), it, captureDir.name, JvmImageEncoder)
            }
            File(outDir, "atlas.jpg").writeBytes(JvmImageEncoder.encodeJpeg(textured.atlas, 85))
            MeshPreview.renderViews(textured, outDir)
            println("Photo-textured model: model_textured.glb, ${textured.triangleCount} triangles, atlas ${textured.atlas.width}x${textured.atlas.height}")
        }
    }
    PoseExportsHook.write(capture, outDir)
    println("Written to ${outDir.path}")
}
