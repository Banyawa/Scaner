package com.banyawa.sitescanner.cli

import com.banyawa.sitescanner.core.capture.ImageDecoder
import com.banyawa.sitescanner.core.pointcloud.ColorImage
import com.banyawa.sitescanner.core.pointcloud.RgbImage
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.imageio.ImageIO

/** JPEG / PNG decoding on the desktop JVM (the app uses Android's BitmapFactory instead). */
object JvmImageDecoder : ImageDecoder {
    override fun decode(bytes: ByteArray): ColorImage {
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: throw IOException("Not an image")
        val w = image.width
        val h = image.height
        return RgbImage(w, h, image.getRGB(0, 0, w, h, null, 0, w))
    }
}
