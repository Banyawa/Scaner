package com.banyawa.sitescanner.scan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.banyawa.sitescanner.R
import com.banyawa.sitescanner.SiteScannerApp
import com.banyawa.sitescanner.core.capture.Manifest as CaptureManifest
import com.banyawa.sitescanner.core.export.Ply
import com.banyawa.sitescanner.core.project.CaptureMode
import com.banyawa.sitescanner.core.project.ProjectRepository
import com.banyawa.sitescanner.core.project.ScanInfo
import com.banyawa.sitescanner.ui.theme.SiteScannerTheme
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-screen AR scanning for one project. Owns the ARCore session lifecycle; the GL
 * work happens in [ScanRenderer] and the UI in [ScanScreen].
 */
class ScanActivity : ComponentActivity() {
    private val controller = ScanController()
    private lateinit var surfaceView: GLSurfaceView
    private lateinit var rotationHelper: DisplayRotationHelper
    private lateinit var renderer: ScanRenderer
    private lateinit var projectId: String

    private var session: Session? = null
    private var installRequested = false
    private var permissionRequested = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) showError(R.string.error_camera_permission, canRetry = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectId = intent.getStringExtra(EXTRA_PROJECT_ID) ?: run {
            finish()
            return
        }
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread { (application as SiteScannerApp).repository.dropAbandonedCaptures(projectId) }.start()

        rotationHelper = DisplayRotationHelper(this)
        renderer = ScanRenderer(controller, rotationHelper)
        surfaceView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        setContent {
            SiteScannerTheme(darkTheme = true) {
                val state by controller.state.collectAsStateWithLifecycle()
                ScanScreen(
                    state = state,
                    surfaceView = surfaceView,
                    onToggleRecording = ::toggleRecording,
                    onAddPoint = { controller.post(ScanAction.AddPoint) },
                    onUndo = { controller.post(ScanAction.Undo) },
                    onSave = ::save,
                    onRetry = ::retry,
                    onExit = ::finish,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startAr()
    }

    override fun onPause() {
        super.onPause()
        controller.setRecording(false)
        if (::surfaceView.isInitialized) {
            rotationHelper.onPause()
            surfaceView.onPause()
        }
        session?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::renderer.isInitialized) renderer.session = null
        session?.close()
        session = null
        controller.shutdown()
        // Left without saving: the recording is of no use to anyone.
        controller.recorder?.let { recorder ->
            controller.recorder = null
            Thread {
                runCatching { recorder.close(CaptureManifest()) }
                recorder.dir.deleteRecursively()
            }.start()
        }
    }

    private fun startAr() {
        if (session == null && !createSession()) return
        val current = session ?: return
        try {
            current.resume()
        } catch (e: CameraNotAvailableException) {
            showError(R.string.error_camera_unavailable, canRetry = true)
            renderer.session = null
            current.close()
            session = null
            return
        }
        // Hand the session to the GL thread only once it is running.
        renderer.session = current
        surfaceView.onResume()
        rotationHelper.onResume()
    }

    private fun retry() {
        permissionRequested = false
        controller.updateState { it.copy(errorRes = null, errorDetail = null, canRetry = false) }
        startAr()
    }

    private fun createSession(): Boolean {
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    return false
                }
                else -> Unit
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                if (!permissionRequested) {
                    permissionRequested = true
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
                return false
            }

            val newSession = Session(this)
            val depth = newSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            val config = newSession.config
            config.setDepthMode(if (depth) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED)
            config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL)
            config.setFocusMode(Config.FocusMode.AUTO)
            config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE)
            config.setLightEstimationMode(Config.LightEstimationMode.DISABLED)
            newSession.configure(config)

            session = newSession
            renderer.depthEnabled = depth
            controller.updateState { it.copy(arReady = true, depthSupported = depth, errorRes = null) }
            return true
        } catch (e: UnavailableUserDeclinedInstallationException) {
            showError(R.string.error_arcore_declined, canRetry = true)
        } catch (e: UnavailableDeviceNotCompatibleException) {
            showError(R.string.error_device_not_supported)
        } catch (e: UnavailableArcoreNotInstalledException) {
            showError(R.string.error_arcore_update, canRetry = true)
        } catch (e: UnavailableApkTooOldException) {
            showError(R.string.error_arcore_update, canRetry = true)
        } catch (e: UnavailableSdkTooOldException) {
            showError(R.string.error_app_update)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AR session", e)
            showError(R.string.error_ar_generic, detail = e.message)
        }
        return false
    }

    private fun showError(messageRes: Int, canRetry: Boolean = false, detail: String? = null) {
        controller.updateState { it.copy(errorRes = messageRes, errorDetail = detail, canRetry = canRetry) }
    }

    /** Recording starts writing the walk-through to disk the first time it is switched on. */
    private fun toggleRecording() {
        val on = !controller.recording
        if (on && controller.recorder == null) {
            val dir = (application as SiteScannerApp).repository.newCaptureDir(projectId)
            controller.recorder = try {
                CaptureRecorder(dir)
            } catch (e: Exception) {
                Log.e(TAG, "Recording to $dir failed", e)
                null
            }
        }
        controller.setRecording(on)
    }

    private fun save(name: String) {
        controller.setRecording(false)
        controller.updateState { it.copy(saving = true) }
        controller.post(ScanAction.Finish { result -> runOnUiThread { persist(name, result) } })
    }

    /**
     * Saves the live points right away, then generates the point cloud and colour 3D model
     * from the recording (all frames, both passes) while the overlay shows the progress.
     * A failed or empty generation leaves the live points in place.
     */
    private fun persist(name: String, result: SessionResult) {
        val app = application as SiteScannerApp
        val repository = app.repository
        val depth = renderer.depthEnabled && controller.depthFrames > 0
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    controller.awaitIdle()
                    val filtered = controller.integrator.snapshot(minWeight = if (depth) MIN_WEIGHT_DEPTH else MIN_WEIGHT_FEATURES)
                    val all = controller.integrator.voxelCount
                    // Low-confidence depth everywhere (dim light, plain walls) would filter out
                    // nearly everything; keeping every point beats saving an empty scan.
                    val cloud = if (filtered.size < all * MIN_KEPT_FRACTION) controller.integrator.snapshot() else filtered
                    Log.i(TAG, "Saving ${cloud.size} of $all points (${filtered.size} above the confidence threshold)")
                    var scan = repository.newScan(name).copy(
                        pointCount = cloud.size,
                        floorY = result.floorY,
                        captureMode = if (depth) CaptureMode.RAW_DEPTH else CaptureMode.FEATURE_POINTS,
                        durationSec = controller.elapsedSec(),
                        measurements = result.measurements,
                    )
                    Ply.write(cloud, repository.scanFile(projectId, scan))
                    scan = withRecording(repository, scan, result)
                    repository.upsertScan(projectId, scan)
                    scan
                }
            }
            val scan = outcome.getOrElse { e ->
                Log.e(TAG, "Saving scan failed", e)
                controller.updateState { it.copy(saving = false) }
                Toast.makeText(this@ScanActivity, getString(R.string.error_save_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                return@launch
            }
            if (scan.hasCapture) {
                controller.updateState { it.copy(buildProgress = 0f) }
                val watching = launch {
                    app.modelBuilder.progress.collect { p -> p[scan.id]?.let { f -> controller.updateState { it.copy(buildProgress = f) } } }
                }
                try {
                    app.modelBuilder.build(projectId, scan)
                } catch (t: Throwable) {
                    Log.e(TAG, "Generating the model failed; keeping the live points", t)
                } finally {
                    watching.cancel()
                }
            }
            setResult(RESULT_OK)
            finish()
        }
    }

    /** Closes the recording and files it under the scan; a recording without frames is dropped. */
    private fun withRecording(repository: ProjectRepository, scan: ScanInfo, result: SessionResult): ScanInfo {
        val recorder = controller.recorder ?: return scan
        controller.recorder = null
        return try {
            recorder.close(
                CaptureManifest(
                    device = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})",
                    durationSec = controller.elapsedSec(),
                    floorY = result.floorY,
                    depthSupported = renderer.depthEnabled,
                    notes = controller.state.value.diagnostics + " · anchors corrected ${result.correctedPoses.size} keyframes",
                ),
                result.correctedPoses,
            )
            Log.i(TAG, "Recording closed: ${recorder.frameCount} frames, ${recorder.correctedFrames} poses corrected from anchors")
            val target = File(recorder.dir.parentFile, repository.captureDirName(scan))
            if (recorder.frameCount == 0 || !recorder.dir.renameTo(target)) {
                recorder.dir.deleteRecursively()
                scan
            } else {
                scan.copy(captureDir = target.name, captureFrames = recorder.frameCount)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Recording not kept", t)
            scan
        }
    }

    companion object {
        private const val TAG = "ScanActivity"
        private const val EXTRA_PROJECT_ID = "projectId"

        /** Voxels seen only once with low confidence are dropped from the saved cloud. */
        private const val MIN_WEIGHT_DEPTH = 0.5f
        private const val MIN_WEIGHT_FEATURES = 0.2f

        /** Below this share of points passing the confidence filter, all points are kept. */
        private const val MIN_KEPT_FRACTION = 0.2f

        fun newIntent(context: Context, projectId: String): Intent =
            Intent(context, ScanActivity::class.java).putExtra(EXTRA_PROJECT_ID, projectId)
    }
}
