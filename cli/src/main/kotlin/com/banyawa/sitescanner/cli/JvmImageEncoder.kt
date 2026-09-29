package com.banyawa.sitescanner.cli

import com.banyawa.sitescanner.core.capture.ImageEncoder
import com.banyawa.sitescanner.core.pointcloud.RgbImage
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.awt.image.DirectColorModel
import java.awt.image.Raster
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** JPEG encoding on the desktop JVM (the app uses Android's Bitmap.compress instead). */
object JvmImageEncoder : ImageEncoder {
    override fun encodeJpeg(image: RgbImage, quality: Int): ByteArray {
        val w = image.width
        val h = image.height
        // Wrap the pixels instead of copying them: a 4096² atlas is 64 MB already.
        val model = DirectColorModel(24, 0xFF0000, 0x00FF00, 0x0000FF)
        val raster = Raster.createPackedRaster(DataBufferInt(image.pixels, w * h), w, h, w, model.masks, null)
        val buffered = BufferedImage(model, raster, false, null)
        val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull() ?: throw IOException("No JPEG encoder")
        val out = ByteArrayOutputStream(w * h / 4)
        try {
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality.coerceIn(1, 100) / 100f
            }
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.write(null, IIOImage(buffered, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }
}
