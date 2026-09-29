package com.banyawa.sitescanner.core.pointcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthYieldTest {
    @Test
    fun sparseOnlyAfterAFullWindow() {
        val y = DepthYield(window = 4, minFraction = 0.05f)
        assertNull(y.fraction())
        repeat(3) { y.add(10, 14_400) }
        assertFalse("too early to judge", y.tooSparse())
        y.add(10, 14_400)
        assertTrue(y.tooSparse())
    }

    @Test
    fun goodFramesKeepRawDepth() {
        val y = DepthYield(window = 4, minFraction = 0.05f)
        repeat(10) { y.add(3_000, 14_400) }
        assertFalse(y.tooSparse())
        assertEquals(3_000f / 14_400, y.fraction()!!, 1e-6f)
    }

    @Test
    fun judgesTheLatestFramesOnly() {
        val y = DepthYield(window = 4, minFraction = 0.05f)
        repeat(4) { y.add(0, 14_400) }
        assertTrue(y.tooSparse())
        // Moving on to a textured area: the old empty frames drop out of the window.
        repeat(4) { y.add(2_000, 14_400) }
        assertFalse(y.tooSparse())
        assertEquals(8, y.frames)
    }
}
