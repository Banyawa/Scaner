package com.banyawa.sitescanner.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.banyawa.sitescanner.R
import com.banyawa.sitescanner.SiteScannerApp
import com.banyawa.sitescanner.app
import com.banyawa.sitescanner.core.floorplan.FloorPlan
import com.banyawa.sitescanner.core.floorplan.Opening
import com.banyawa.sitescanner.core.floorplan.OpeningType
import com.banyawa.sitescanner.core.geometry.Vec2
import com.banyawa.sitescanner.core.pointcloud.ColorMaps
import com.banyawa.sitescanner.core.pointcloud.PointCloud
import com.banyawa.sitescanner.core.project.ScanInfo
import com.banyawa.sitescanner.data.LoadedMesh
import com.banyawa.sitescanner.ui.common.formatCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ViewerState {
    data object Loading : ViewerState
    class Loaded(
        val scan: ScanInfo,
        val cloud: PointCloud,
        val heightColors: ByteArray,
        val openings: OpeningFrames = OpeningFrames.EMPTY,
        /** Surface model, when the scan has one; photo-textured when it has a texture. */
        val mesh: LoadedMesh? = null,
    ) : ViewerState
    data object Missing : ViewerState
}

class ViewerViewModel(projectId: String, scanId: String, private val app: SiteScannerApp) : ViewModel() {
    private val _state = MutableStateFlow<ViewerState>(ViewerState.Loading)
    val state: StateFlow<ViewerState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val scan = withContext(Dispatchers.IO) { app.repository.get(projectId)?.scan(scanId) }
            if (scan == null) {
                _state.value = ViewerState.Missing
                return@launch
            }
            // Phones render a few million points comfortably; decimate beyond that.
            val cloud = app.analysis.cloud(projectId, scan).decimated(MAX_DISPLAY_POINTS)
            val heights = withContext(Dispatchers.Default) { ColorMaps.byHeight(cloud) }
            val mesh = runCatching { app.analysis.mesh(projectId, scan) }.getOrNull()
            _state.value = ViewerState.Loaded(scan, cloud, heights, mesh = mesh)

            // Door / window outlines follow once the (slower) floor-plan analysis is done.
            val frames = runCatching {
                val plan = app.analysis.floorPlan(projectId, scan).plan
                withContext(Dispatchers.Default) { openingFrames(plan) }
            }.getOrNull() ?: return@launch
            _state.value = ViewerState.Loaded(scan, cloud, heights, frames, mesh)
        }
    }

    /** Opening outlines, plus each door leaf standing open at 90° and its swing on the floor. */
    private fun openingFrames(plan: FloorPlan): OpeningFrames {
        fun outline(list: List<Opening>): FloatArray {
            val out = ArrayList<Float>()
            fun segment(p: Vec2, hp: Float, q: Vec2, hq: Float) {
                val a = plan.alignment.toWorld(p, hp)
                val b = plan.alignment.toWorld(q, hq)
                out += a.x; out += a.y; out += a.z
                out += b.x; out += b.y; out += b.z
            }
            for (o in list) {
                segment(o.start, o.bottom, o.end, o.bottom)
                segment(o.end, o.bottom, o.end, o.top)
                segment(o.end, o.top, o.start, o.top)
                segment(o.start, o.top, o.start, o.bottom)
                for (leaf in o.leaves) {
                    segment(leaf.hinge, o.top, leaf.open, o.top)
                    segment(leaf.open, 0f, leaf.open, o.top)
                    segment(leaf.hinge, FLOOR_LIFT, leaf.open, FLOOR_LIFT)
                    var prev = leaf.closed
                    for (k in 1..ARC_SEGMENTS) {
                        val p = leaf.arcPoint(k / ARC_SEGMENTS.toFloat())
                        segment(prev, FLOOR_LIFT, p, FLOOR_LIFT)
                        prev = p
                    }
                }
            }
            return out.toFloatArray()
        }
        val (windows, doors) = plan.openings.partition { it.type == OpeningType.WINDOW }
        return OpeningFrames(outline(doors), outline(windows))
    }

    companion object {
        private const val MAX_DISPLAY_POINTS = 2_500_000
        private const val ARC_SEGMENTS = 16

        /** Swing arcs float just above the floor so the floor points do not hide them. */
        private const val FLOOR_LIFT = 0.01f
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(projectId: String, scanId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: ViewerViewModel = viewModel { ViewerViewModel(projectId, scanId, context.app) }
    val state by vm.state.collectAsStateWithLifecycle()
    var heightColors by rememberSaveable { mutableStateOf(false) }
    var showPoints by rememberSaveable { mutableStateOf(false) }
    val view = remember { PointCloudView(context) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val loaded = state as? ViewerState.Loaded
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(loaded?.scan?.name ?: stringResource(R.string.action_view_3d)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (loaded?.mesh != null) {
                        IconButton(onClick = { showPoints = !showPoints }) {
                            Icon(
                                if (showPoints) Icons.Filled.ViewInAr else Icons.Filled.Grain,
                                contentDescription = stringResource(if (showPoints) R.string.viewer_show_model else R.string.viewer_show_points),
                            )
                        }
                    }
                    IconButton(onClick = { heightColors = !heightColors }) {
                        Icon(Icons.Filled.Palette, contentDescription = stringResource(R.string.viewer_toggle_colors))
                    }
                    IconButton(onClick = { view.resetView() }) {
                        Icon(Icons.Filled.CenterFocusStrong, contentDescription = stringResource(R.string.viewer_reset))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                ViewerState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                ViewerState.Missing -> Text(stringResource(R.string.scan_missing), Modifier.align(Alignment.Center))
                is ViewerState.Loaded -> {
                    AndroidView(
                        factory = { view },
                        update = {
                            // Height colours are a point-cloud view.
                            val mesh = s.mesh.takeUnless { showPoints || heightColors }
                            it.setContent(s.cloud, if (heightColors) s.heightColors else s.cloud.rgb, s.scan.measurements, s.openings, mesh)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    Text(
                        if (s.mesh != null && !showPoints && !heightColors) {
                            stringResource(
                                if (s.mesh.textured != null) R.string.viewer_info_textured else R.string.viewer_info_model,
                                formatCount(s.mesh.mesh.triangleCount),
                            )
                        } else {
                            stringResource(R.string.viewer_info, formatCount(s.cloud.size))
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}
