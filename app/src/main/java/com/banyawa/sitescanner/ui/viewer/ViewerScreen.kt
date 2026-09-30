package com.banyawa.sitescanner.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import com.banyawa.sitescanner.core.scene.SceneLayer
import com.banyawa.sitescanner.core.scene.SceneLayers
import com.banyawa.sitescanner.core.scene.SceneObject
import com.banyawa.sitescanner.data.LoadedMesh
import com.banyawa.sitescanner.gl.MeshRenderer
import com.banyawa.sitescanner.ui.common.formatArea
import com.banyawa.sitescanner.ui.common.formatCount
import com.banyawa.sitescanner.ui.common.formatMetres
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

sealed interface ViewerState {
    data object Loading : ViewerState
    class Loaded(
        val scan: ScanInfo,
        val cloud: PointCloud,
        val heightColors: ByteArray,
        val openings: OpeningFrames = OpeningFrames.EMPTY,
        /** Surface model, when the scan has one; photo-textured when it has a texture. */
        val mesh: LoadedMesh? = null,
        /** The model sorted into floor / ceiling / walls / objects; null without a model or until worked out. */
        val scene: SceneLayers? = null,
        /** [scene] ready to draw: the layer of each triangle and the object boxes. */
        val overlay: SceneOverlay = SceneOverlay.EMPTY,
    ) : ViewerState
    data object Missing : ViewerState
}

class ViewerViewModel(projectId: String, scanId: String, private val app: SiteScannerApp) : ViewModel() {
    private val _state = MutableStateFlow<ViewerState>(ViewerState.Loading)
    val state: StateFlow<ViewerState> = _state.asStateFlow()

    /** Layers of the model drawn, [MeshRenderer.layerBit] per layer; all to begin with, then as the user left them. */
    private val _visibleLayers = MutableStateFlow(MeshRenderer.ALL_LAYERS)
    val visibleLayers: StateFlow<Int> = _visibleLayers.asStateFlow()

    private val _showBoxes = MutableStateFlow(true)
    val showBoxes: StateFlow<Boolean> = _showBoxes.asStateFlow()

    /** The object whose box is highlighted, by id. */
    private val _selectedObject = MutableStateFlow<Int?>(null)
    val selectedObject: StateFlow<Int?> = _selectedObject.asStateFlow()

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
            val plan = runCatching { app.analysis.floorPlan(projectId, scan).plan }.getOrNull() ?: return@launch
            val frames = withContext(Dispatchers.Default) { openingFrames(plan) }
            _state.value = ViewerState.Loaded(scan, cloud, heights, frames, mesh)

            // Then the model's layers and the object boxes, which need the model and the plan both.
            if (mesh == null) return@launch
            val scene = runCatching { app.analysis.sceneLayers(projectId, scan) }.getOrNull() ?: return@launch
            val overlay = withContext(Dispatchers.Default) { sceneOverlay(scene, plan) }
            _state.value = ViewerState.Loaded(scan, cloud, heights, frames, mesh, scene, overlay)
        }
    }

    fun toggleLayer(layer: SceneLayer) {
        _visibleLayers.value = _visibleLayers.value xor MeshRenderer.layerBit(layer)
    }

    fun setShowBoxes(show: Boolean) {
        _showBoxes.value = show
    }

    /** Highlights the object with [id]; choosing the highlighted one again clears it. */
    fun select(id: Int?) {
        _selectedObject.value = if (id == _selectedObject.value) null else id
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

    /** Each object's box as its 12 edges from its bottom to its top, with the label at the top centre. */
    private fun sceneOverlay(scene: SceneLayers, plan: FloorPlan): SceneOverlay {
        val boxes = scene.objects.map { o ->
            val corners = o.box.corners()
            val out = FloatArray(12 * 6)
            var n = 0
            fun segment(p: Vec2, hp: Float, q: Vec2, hq: Float) {
                val a = plan.alignment.toWorld(p, hp)
                val b = plan.alignment.toWorld(q, hq)
                out[n++] = a.x; out[n++] = a.y; out[n++] = a.z
                out[n++] = b.x; out[n++] = b.y; out[n++] = b.z
            }
            for (i in 0 until 4) {
                val p = corners[i]
                val q = corners[(i + 1) % 4]
                segment(p, o.bottom, q, o.bottom)
                segment(p, o.top, q, o.top)
                segment(p, o.bottom, p, o.top)
            }
            ObjectBox(o.id, out, plan.alignment.toWorld(o.box.centre, o.top))
        }
        return SceneOverlay(scene.triangleLayer, boxes)
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
    val visibleLayers by vm.visibleLayers.collectAsStateWithLifecycle()
    val showBoxes by vm.showBoxes.collectAsStateWithLifecycle()
    val selected by vm.selectedObject.collectAsStateWithLifecycle()
    var heightColors by rememberSaveable { mutableStateOf(false) }
    var showPoints by rememberSaveable { mutableStateOf(false) }
    var objectsExpanded by rememberSaveable { mutableStateOf(false) }
    val view = remember { PointCloudView(context) }
    // Where the object labels go, as the GL thread works it out with every frame.
    val objectLabels = remember { mutableStateOf<List<ObjectLabel>>(emptyList()) }
    DisposableEffect(view) {
        view.setObjectLabelListener { labels ->
            view.post { if (labels != objectLabels.value) objectLabels.value = labels }
        }
        onDispose { view.setObjectLabelListener(null) }
    }

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
                    val scene = s.scene
                    // Height colours are a point-cloud view; the layer chips only apply to the model.
                    val meshShown = s.mesh != null && !showPoints && !heightColors
                    val boxesShown = showBoxes && (visibleLayers and MeshRenderer.layerBit(SceneLayer.OBJECT)) != 0
                    Column(Modifier.fillMaxSize()) {
                        if (scene != null) {
                            LayerChips(
                                visibleLayers = visibleLayers,
                                layersApply = meshShown,
                                showBoxes = showBoxes,
                                onToggleLayer = vm::toggleLayer,
                                onShowBoxes = vm::setShowBoxes,
                            )
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            AndroidView(
                                factory = { view },
                                update = {
                                    val mesh = s.mesh.takeUnless { showPoints || heightColors }
                                    it.setContent(
                                        s.cloud,
                                        if (heightColors) s.heightColors else s.cloud.rgb,
                                        s.scan.measurements,
                                        s.openings,
                                        mesh,
                                        s.overlay,
                                    )
                                    it.setVisibleLayers(visibleLayers)
                                    it.setObjectBoxes(showBoxes, selected)
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (scene != null && boxesShown) {
                                ObjectLabelsOverlay(
                                    labels = objectLabels.value,
                                    objects = scene.objects,
                                    selected = selected,
                                    onTap = { id ->
                                        vm.select(id)
                                        objectsExpanded = true
                                    },
                                )
                            }
                            Text(
                                if (meshShown && s.mesh != null) {
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
                        if (scene != null) {
                            ObjectsPanel(
                                scene = scene,
                                selected = selected,
                                expanded = objectsExpanded,
                                onExpand = { objectsExpanded = it },
                                onSelect = vm::select,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Floor / ceiling / walls / objects chips that show or hide each layer of the model, and the object boxes. */
@Composable
private fun LayerChips(
    visibleLayers: Int,
    layersApply: Boolean,
    showBoxes: Boolean,
    onToggleLayer: (SceneLayer) -> Unit,
    onShowBoxes: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (layer in SceneLayer.entries) {
            FilterChip(
                selected = (visibleLayers and MeshRenderer.layerBit(layer)) != 0,
                enabled = layersApply,
                onClick = { onToggleLayer(layer) },
                label = { Text(layerLabel(layer)) },
            )
        }
        FilterChip(
            selected = showBoxes,
            enabled = (visibleLayers and MeshRenderer.layerBit(SceneLayer.OBJECT)) != 0,
            onClick = { onShowBoxes(!showBoxes) },
            label = { Text(stringResource(R.string.scene_object_boxes)) },
        )
    }
}

@Composable
private fun layerLabel(layer: SceneLayer): String = stringResource(
    when (layer) {
        SceneLayer.FLOOR -> R.string.scene_layer_floor
        SceneLayer.CEILING -> R.string.scene_layer_ceiling
        SceneLayer.WALL -> R.string.scene_layer_walls
        SceneLayer.OBJECT -> R.string.scene_layer_objects
    },
)

/** The object's tag, "M1", as on the plan and in the exports. */
private fun objectTag(o: SceneObject): String = "M${o.id}"

/** "1.20 × 0.80 × 1.50 m": the box's length, width and height. */
private fun objectSize(o: SceneObject): String =
    "${formatMetres(o.box.length)} × ${formatMetres(o.box.width)} × ${formatMetres(o.height)} m"

/** Semi-transparent label over the model; the highlighted object's label is amber. */
private val LabelBackground = Color(0xCC1B2733)
private val LabelHighlight = Color(0xEEFFB300)

/** "M1  1.20 × 0.80 × 1.50 m" above each box; tapping one highlights that object. */
@Composable
private fun ObjectLabelsOverlay(labels: List<ObjectLabel>, objects: List<SceneObject>, selected: Int?, onTap: (Int) -> Unit) {
    val byId = remember(objects) { objects.associateBy { it.id } }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        for (label in labels) {
            val o = byId[label.id] ?: continue
            key(label.id) {
                val chosen = label.id == selected
                Text(
                    text = "${objectTag(o)}  ${objectSize(o)}",
                    color = if (chosen) Color.Black else Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                            layout(0, 0) {
                                placeable.place(
                                    (label.x - placeable.width / 2f).roundToInt(),
                                    (label.y - placeable.height).roundToInt(),
                                )
                            }
                        }
                        .background(if (chosen) LabelHighlight else LabelBackground, RoundedCornerShape(6.dp))
                        .clickable { onTap(label.id) }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** The objects found, under a line summing up the scene; tapping a row highlights that object's box. */
@Composable
private fun ObjectsPanel(
    scene: SceneLayers,
    selected: Int?,
    expanded: Boolean,
    onExpand: (Boolean) -> Unit,
    onSelect: (Int?) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selected, expanded) {
        val index = scene.objects.indexOfFirst { it.id == selected }
        if (expanded && index >= 0) listState.animateScrollToItem(index)
    }
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable { onExpand(!expanded) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.scene_objects_title, scene.objects.size), style = MaterialTheme.typography.titleSmall)
                    Text(
                        sceneSummary(scene),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                    contentDescription = stringResource(if (expanded) R.string.scene_objects_collapse else R.string.scene_objects_expand),
                )
            }
            if (expanded) {
                if (scene.objects.isEmpty()) {
                    Text(
                        stringResource(R.string.scene_objects_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                        items(scene.objects, key = { it.id }) { o ->
                            ObjectRow(o, chosen = o.id == selected, onClick = { onSelect(o.id) })
                        }
                    }
                }
            }
        }
    }
}

/** Floor area, the typical ceiling height and the lowest point overhead, whichever are known. */
@Composable
private fun sceneSummary(scene: SceneLayers): String {
    val parts = ArrayList<String>()
    parts += stringResource(R.string.scene_floor_area, formatArea(scene.area(SceneLayer.FLOOR)))
    val ceiling = scene.ceiling
    if (ceiling != null) {
        if (!ceiling.typicalHeight.isNaN()) {
            parts += stringResource(R.string.scene_ceiling_height, "${formatMetres(ceiling.typicalHeight)} m")
        }
        if (!ceiling.minHeight.isNaN()) {
            parts += stringResource(R.string.scene_min_clearance, "${formatMetres(ceiling.minHeight)} m")
        }
    }
    return parts.joinToString(" · ")
}

/** "M1 · 1.20 × 0.80 × 1.50 m · 0.50 m from wall", with the heights and footprint when chosen. */
@Composable
private fun ObjectRow(o: SceneObject, chosen: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (chosen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        val line = buildString {
            append(objectTag(o))
            append(" · ")
            append(objectSize(o))
            o.wallClearance?.let {
                append(" · ")
                append(stringResource(R.string.scene_wall_clearance, "${formatMetres(it)} m"))
            }
        }
        Text(line, style = MaterialTheme.typography.bodyMedium, fontWeight = if (chosen) FontWeight.Bold else null)
        if (chosen) {
            Text(
                stringResource(
                    R.string.scene_object_detail,
                    "${formatMetres(o.bottom)} m",
                    "${formatMetres(o.top)} m",
                    formatArea(o.footprintArea),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
