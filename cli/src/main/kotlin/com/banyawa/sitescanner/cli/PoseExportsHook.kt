package com.banyawa.sitescanner.cli

import com.banyawa.sitescanner.core.capture.Capture
import java.io.File

/** Camera poses for PC photogrammetry tools; filled in once the exporters land in core. */
object PoseExportsHook {
    fun write(capture: Capture, outDir: File) = Unit
}
