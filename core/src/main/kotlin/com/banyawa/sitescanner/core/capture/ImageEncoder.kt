package com.banyawa.sitescanner.core.capture

import com.banyawa.sitescanner.core.pointcloud.RgbImage

/**
 * Compresses an image to JPEG, for the texture atlas of a photo-textured model. Core has
 * no image codec of its own: Android (Bitmap.compress) and the desktop JVM (ImageIO) each
 * bring theirs, as with [ImageDecoder].
 */
fun interface ImageEncoder {
    /** [image] as a baseline JPEG; [quality] 0..100 as the platform encoders take it. */
    fun encodeJpeg(image: RgbImage, quality: Int): ByteArray
}
