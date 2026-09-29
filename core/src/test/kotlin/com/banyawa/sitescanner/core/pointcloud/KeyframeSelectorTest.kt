package com.banyawa.sitescanner.core.pointcloud

import com.banyawa.sitescanner.core.geometry.Mat4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class KeyframeSelectorTest {
    private fun poseAt(x: Float, yawDeg: Float = 0f): FloatArray {
        val a = Math.toRadians(yawDeg.toDouble())
        return Mat4.identity().also {
            it[0] = cos(a).toFloat(); it[8] = sin(a).toFloat()
            it[2] = -sin(a).toFloat(); it[10] = cos(a).toFloat()
            it[12] = x
        }
    }

    @Test
    fun capturesOnMotionRotationAndTimeout() {
        val sel = KeyframeSelector(minTranslationM = 0.05f, minRotationDeg = 5f, minIntervalMs = 100, maxIntervalMs = 1000)
        assertTrue(sel.shouldCapture(poseAt(0f), 0))
        assertFalse("too soon", sel.shouldCapture(poseAt(1f), 50))
        assertFalse("not moved", sel.shouldCapture(poseAt(0.01f), 200))
        assertTrue("moved", sel.shouldCapture(poseAt(0.1f), 300))
        assertTrue("rotated", sel.shouldCapture(poseAt(0.1f, 10f), 400))
        assertFalse(sel.shouldCapture(poseAt(0.1f, 10f), 600))
        assertTrue("timeout", sel.shouldCapture(poseAt(0.1f, 10f), 1500))
    }

    @Test
    fun rotationAngle() {
        assertEquals(30f, KeyframeSelector.rotationDegBetween(poseAt(0f), poseAt(0f, 30f)), 1e-3f)
    }
}
