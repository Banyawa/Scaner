package com.banyawa.sitescanner.core

import com.banyawa.sitescanner.core.geometry.Bounds3
import com.banyawa.sitescanner.core.geometry.Vec3
import com.banyawa.sitescanner.core.pointcloud.ColorMaps
import com.banyawa.sitescanner.core.pointcloud.PointCloud
import com.banyawa.sitescanner.core.units.LengthFormat
import com.banyawa.sitescanner.core.units.LengthUnit
import com.banyawa.sitescanner.core.viewer.OrbitCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiscTest {
    @Test
    fun lengthFormatting() {
        assertEquals("1235 mm", LengthFormat.format(1.2345f))
        assertEquals("123.5 cm", LengthFormat.format(1.2345f, LengthUnit.CENTIMETER))
        assertEquals("1.235 m", LengthFormat.format(1.2345f, LengthUnit.METER))
        assertEquals(0L, LengthFormat.toMillimeters(0.0004f))
    }

    @Test
    fun orbitCameraFitsAndKeepsDistanceWhileRotating() {
        val cam = OrbitCamera()
        cam.fit(Bounds3(Vec3(-2f, 0f, -1f), Vec3(2f, 3f, 1f)))
        assertEquals(Vec3(0f, 1.5f, 0f), cam.target)
        val d = cam.eye().distanceTo(cam.target)
        cam.rotate(300f, 120f, 1000)
        assertEquals(d, cam.eye().distanceTo(cam.target), 1e-4f)
        cam.pan(100f, 0f, 1000)
        assertTrue(cam.target.distanceTo(Vec3(0f, 1.5f, 0f)) > 0f)
        cam.zoom(1e-6f)
        assertTrue(cam.distance > 0f)
    }

    @Test
    fun heightColoursRampFromBlueToRed() {
        val n = 100
        val cloud = PointCloud(FloatArray(n * 3) { if (it % 3 == 1) (it / 3).toFloat() else 0f }, ByteArray(n * 3))
        val rgb = ColorMaps.byHeight(cloud)
        val low = rgb[2].toInt() and 0xFF // blue channel of lowest point
        val high = rgb[(n - 1) * 3].toInt() and 0xFF // red channel of highest point
        assertTrue(low > 150)
        assertTrue(high > 200)
    }
}
