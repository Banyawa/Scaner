package com.banyawa.sitescanner.data

import java.io.File

/**
 * Extra files added to a shared recording: camera poses in the formats PC photogrammetry
 * tools read. Filled in once the exporters land in core.
 */
object PoseExportFiles {
    fun forCapture(captureDir: File): Map<String, ByteArray> = emptyMap()
}
