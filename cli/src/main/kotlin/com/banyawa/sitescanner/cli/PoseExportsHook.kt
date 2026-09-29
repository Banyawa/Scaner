package com.banyawa.sitescanner.cli

import com.banyawa.sitescanner.core.capture.Capture
import com.banyawa.sitescanner.core.export.Colmap
import com.banyawa.sitescanner.core.export.Nerfstudio
import java.io.File

/** Camera poses for PC photogrammetry tools, each with its own copy of the images. */
object PoseExportsHook {
    fun write(capture: Capture, outDir: File) {
        runCatching {
            val n = Colmap.write(capture, File(outDir, "colmap"))
            Nerfstudio.write(capture, File(outDir, "nerfstudio"))
            println("Camera poses for COLMAP / Postshot / nerfstudio: $n images in colmap/ and nerfstudio/")
        }.onFailure { System.err.println("Pose export skipped: ${it.message}") }
    }
}
