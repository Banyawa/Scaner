package com.banyawa.sitescanner.ui.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.banyawa.sitescanner.core.pointcloud.PointCloud
import com.banyawa.sitescanner.core.project.Measurement
import com.banyawa.sitescanner.core.viewer.OrbitCamera
import com.banyawa.sitescanner.data.LoadedMesh
import com.banyawa.sitescanner.gl.LineRenderer
import com.banyawa.sitescanner.gl.MeshRenderer
import com.banyawa.sitescanner.gl.PointRenderer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Offline point-cloud viewer. One finger orbits, two fingers pan and pinch to zoom.
 * All camera changes are queued onto the GL thread.
 */
@SuppressLint("ViewConstructor")
class PointCloudView(context: Context) : GLSurfaceView(context) {
    private val renderer = ViewerRenderer()

    private var lastX = 0f
    private var lastY = 0f
    private var mode = Mode.NONE

    private enum class Mode { NONE, ROTATE, PAN }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = 1f / detector.scaleFactor
                queueEvent { renderer.camera.zoom(factor) }
                requestRender()
                return true
            }
        },
    )

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    private var shownCloud: PointCloud? = null
    private var shownColors: ByteArray? = null
    private var shownMeasurements: List<Measurement>? = null
    private var shownOpenings: OpeningFrames? = null
    private var shownMesh: LoadedMesh? = null

    /**
     * Shows [mesh] (the surface model, photo-textured when it has a texture) instead of
     * the points when it is not null.
     */
    fun setContent(cloud: PointCloud, colors: ByteArray, measurements: List<Measurement>, openings: OpeningFrames, mesh: LoadedMesh?) {
        if (cloud === shownCloud && colors === shownColors && measurements == shownMeasurements &&
            openings === shownOpenings && mesh === shownMesh
        ) return
        val refit = cloud !== shownCloud
        shownCloud = cloud
        shownColors = colors
        shownMeasurements = measurements
        shownOpenings = openings
        shownMesh = mesh
        queueEvent { renderer.setContent(cloud, colors, measurements, openings, mesh, refit) }
        requestRender()
    }

    fun resetView() {
        queueEvent { renderer.resetView() }
        requestRender()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = Mode.ROTATE
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                mode = Mode.PAN
                lastX = focusX(event)
                lastY = focusY(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.ROTATE && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    val w = width
                    queueEvent { renderer.camera.rotate(dx, dy, w) }
                } else if (mode == Mode.PAN && event.pointerCount >= 2) {
                    val fx = focusX(event)
                    val fy = focusY(event)
                    val dx = fx - lastX
                    val dy = fy - lastY
                    lastX = fx
                    lastY = fy
                    val h = height
                    queueEvent { renderer.camera.pan(dx, dy, h) }
                }
            }
            // Lifting a finger would make the remaining one jump; wait for a fresh gesture.
            MotionEvent.ACTION_POINTER_UP -> mode = Mode.NONE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = Mode.NONE
        }
        requestRender()
        return true
    }

    private fun focusX(e: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until e.pointerCount) sum += e.getX(i)
        return sum / e.pointerCount
    }

    private fun focusY(e: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until e.pointerCount) sum += e.getY(i)
        return sum / e.pointerCount
    }
}

/** Door and window outlines as GL_LINES vertex pairs in ARCore world coordinates. */
class OpeningFrames(val doors: FloatArray, val windows: FloatArray) {
    companion object {
        val EMPTY = OpeningFrames(FloatArray(0), FloatArray(0))
    }
}

private class ViewerRenderer : GLSurfaceView.Renderer {
    val camera = OrbitCamera()
    private val points = PointRenderer()
    private val lines = LineRenderer()
    private val surface = MeshRenderer()

    private var cloud: PointCloud? = null
    private var colors: ByteArray? = null
    private var mesh: LoadedMesh? = null
    private var meshUploaded: LoadedMesh? = null
    private var measurementLines = FloatArray(0)
    private var openings = OpeningFrames.EMPTY
    private var uploaded = false
    private var width = 1
    private var height = 1

    fun setContent(
        cloud: PointCloud,
        colors: ByteArray,
        measurements: List<Measurement>,
        openings: OpeningFrames,
        mesh: LoadedMesh?,
        refit: Boolean,
    ) {
        this.cloud = cloud
        this.colors = colors
        this.openings = openings
        this.mesh = mesh
        measurementLines = FloatArray(measurements.size * 6).also { arr ->
            measurements.forEachIndexed { i, m ->
                arr[i * 6] = m.start.x; arr[i * 6 + 1] = m.start.y; arr[i * 6 + 2] = m.start.z
                arr[i * 6 + 3] = m.end.x; arr[i * 6 + 4] = m.end.y; arr[i * 6 + 5] = m.end.z
            }
        }
        uploaded = false
        if (refit) resetView()
    }

    fun resetView() {
        cloud?.bounds()?.let { camera.fit(it) }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.11f, 0.12f, 0.14f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        points.createOnGlThread()
        lines.createOnGlThread()
        surface.createOnGlThread()
        uploaded = false
        meshUploaded = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        this.width = width
        this.height = height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val c = cloud ?: return
        if (!uploaded) {
            points.upload(c, colors ?: c.rgb)
            uploaded = true
        }
        val viewProj = camera.viewProjection(width.toFloat() / height.coerceAtLeast(1))
        val m = mesh
        if (m != null) {
            if (meshUploaded !== m) {
                val textured = m.textured
                if (textured != null) surface.upload(textured.mesh, textured.uv, textured.atlas) else surface.upload(m.mesh)
                meshUploaded = m
            }
            // Headlight: lit from where the viewer stands.
            val toEye = (camera.eye() - camera.target).normalized()
            surface.draw(viewProj, floatArrayOf(toEye.x, toEye.y, toEye.z))
        } else {
            points.draw(viewProj, POINT_SIZE_PX)
        }
        lines.draw(viewProj, openings.doors, DOOR_COLOR, GLES20.GL_LINES, lineWidth = 4f)
        lines.draw(viewProj, openings.windows, WINDOW_COLOR, GLES20.GL_LINES, lineWidth = 4f)
        if (measurementLines.isNotEmpty()) {
            lines.draw(viewProj, measurementLines, MEASURE_COLOR, GLES20.GL_LINES, lineWidth = 5f)
            lines.draw(viewProj, measurementLines, ENDPOINT_COLOR, GLES20.GL_POINTS, pointSize = 12f)
        }
    }

    companion object {
        private const val POINT_SIZE_PX = 4f
        private val MEASURE_COLOR = floatArrayOf(1f, 0.6f, 0f, 1f)
        private val ENDPOINT_COLOR = floatArrayOf(1f, 1f, 1f, 1f)
        private val DOOR_COLOR = floatArrayOf(0.2f, 0.9f, 0.75f, 1f)
        private val WINDOW_COLOR = floatArrayOf(0.35f, 0.6f, 1f, 1f)
    }
}
