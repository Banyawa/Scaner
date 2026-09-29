package com.banyawa.sitescanner.scan

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Surface
import com.google.ar.core.Session

/** Keeps ARCore's display geometry in sync with the surface size and screen rotation. */
class DisplayRotationHelper(private val activity: Activity) : DisplayManager.DisplayListener {
    private val displayManager = activity.getSystemService(DisplayManager::class.java)

    @Volatile
    private var changed = false
    private var width = 0
    private var height = 0

    fun onResume() {
        displayManager.registerDisplayListener(this, null)
        changed = true
    }

    fun onPause() = displayManager.unregisterDisplayListener(this)

    fun onSurfaceChanged(width: Int, height: Int) {
        this.width = width
        this.height = height
        changed = true
    }

    fun updateSessionIfNeeded(session: Session) {
        if (!changed || width == 0 || height == 0) return
        session.setDisplayGeometry(rotation(), width, height)
        changed = false
    }

    private fun rotation(): Int {
        val display: Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        }
        return display?.rotation ?: Surface.ROTATION_0
    }

    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit
    override fun onDisplayChanged(displayId: Int) {
        changed = true
    }
}
