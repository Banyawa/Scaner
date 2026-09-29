package com.banyawa.sitescanner.data

import android.util.Log
import com.banyawa.sitescanner.core.capture.Capture
import com.banyawa.sitescanner.core.export.Colmap
import com.banyawa.sitescanner.core.export.Nerfstudio
import java.io.File

/**
 * Extra files added to a shared recording: the camera poses in the formats PC
 * photogrammetry tools read (COLMAP text model, nerfstudio transforms.json), placed at the
 * zip's root and pointing at the recording's own `frames/` folder, so the unzipped folder
 * is a ready dataset without a second copy of the images.
 */
object PoseExportFiles {
    fun forCapture(captureDir: File): Map<String, ByteArray> {
        val temp = File(captureDir.parentFile, "${captureDir.name}_poses").apply {
            deleteRecursively()
            mkdirs()
        }
        return try {
            val capture = Capture.open(captureDir)
            Colmap.write(capture, temp, copyImages = false, imageDir = Capture.FRAME_DIR)
            Nerfstudio.write(capture, temp, copyImages = false, imageDir = Capture.FRAME_DIR)
            temp.walkTopDown().filter { it.isFile }
                .associate { it.relativeTo(temp).path.replace(File.separatorChar, '/') to it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "Camera poses not added to the recording", e)
            emptyMap()
        } finally {
            temp.deleteRecursively()
        }
    }

    private const val TAG = "PoseExportFiles"
}
