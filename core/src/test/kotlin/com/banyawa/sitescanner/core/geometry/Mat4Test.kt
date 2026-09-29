package com.banyawa.sitescanner.core.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mat4Test {
    @Test
    fun lookAtPutsTargetOnNegativeZ() {
        val view = Mat4.lookAt(Vec3(3f, 2f, 5f), Vec3(1f, 1f, 1f), Vec3.UP)
        val t = Mat4.transformPoint(view, Vec3(1f, 1f, 1f))
        val expectedDistance = Vec3(3f, 2f, 5f).distanceTo(Vec3(1f, 1f, 1f))
        assertEquals(0f, t.x, 1e-5f)
        assertEquals(0f, t.y, 1e-5f)
        assertEquals(-expectedDistance, t.z, 1e-5f)
    }

    @Test
    fun multiplyByIdentityIsNoOp() {
        val m = Mat4.lookAt(Vec3(1f, 2f, 3f), Vec3.ZERO, Vec3.UP)
        val r = Mat4.multiply(Mat4.identity(), m)
        for (i in 0 until 16) assertEquals(m[i], r[i], 1e-6f)
    }

    @Test
    fun projectCentreAndBehind() {
        val view = Mat4.lookAt(Vec3(0f, 0f, 5f), Vec3.ZERO, Vec3.UP)
        val vp = Mat4.multiply(Mat4.perspective(60f, 2f, 0.1f, 100f), view)
        val centre = Mat4.projectToScreen(vp, Vec3.ZERO, 200, 100)!!
        assertEquals(100f, centre.x, 1e-3f)
        assertEquals(50f, centre.y, 1e-3f)
        // A point above the target appears above the centre (smaller y).
        val above = Mat4.projectToScreen(vp, Vec3(0f, 1f, 0f), 200, 100)!!
        assertTrue(above.y < 50f)
        assertNull(Mat4.projectToScreen(vp, Vec3(0f, 0f, 10f), 200, 100))
    }
}
