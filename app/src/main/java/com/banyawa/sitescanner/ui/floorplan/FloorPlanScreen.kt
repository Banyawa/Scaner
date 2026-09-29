package com.banyawa.sitescanner.ui.floorplan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.banyawa.sitescanner.R
import com.banyawa.sitescanner.SiteScannerApp
import com.banyawa.sitescanner.app
import com.banyawa.sitescanner.core.floorplan.FloorPlanResult
import com.banyawa.sitescanner.core.geometry.Bounds2
import com.banyawa.sitescanner.core.geometry.Vec2
import com.banyawa.sitescanner.core.project.Project
import com.banyawa.sitescanner.core.project.ScanInfo
import com.banyawa.sitescanner.core.units.LengthFormat
import com.banyawa.sitescanner.data.ExportEvent
import com.banyawa.sitescanner.data.ExportFormat
import com.banyawa.sitescanner.data.ExportManager
import com.banyawa.sitescanner.ui.theme.PlanColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A measurement projected into plan coordinates. */
class PlanMeasurement(val start: Vec2, val end: Vec2, val text: String)

sealed interface PlanState {
    data object Loading : PlanState
    data object Missing : PlanState
    class Loaded(
        val project: Project,
        val scan: ScanInfo,
        val result: FloorPlanResult,
        val slicePoints: List<Offset>,
        val measurements: List<PlanMeasurement>,
    ) : PlanState
}

class FloorPlanViewModel(private val projectId: String, private val scanId: String, private val app: SiteScannerApp) : ViewModel() {
    private val exporter = ExportManager(app, app.analysis)

    private val _state = MutableStateFlow<PlanState>(PlanState.Loading)
    val state: StateFlow<PlanState> = _state.asStateFlow()

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    private val _events = MutableSharedFlow<ExportEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ExportEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            val project = withContext(Dispatchers.IO) { app.repository.get(projectId) }
            val scan = project?.scan(scanId)
            if (project == null || scan == null) {
                _state.value = PlanState.Missing
                return@launch
            }
            val result = app.analysis.floorPlan(projectId, scan)
            val (slice, measurements) = withContext(Dispatchers.Default) {
                val step = max(1, result.sliceSize / MAX_SLICE_POINTS)
                val pts = ArrayList<Offset>(result.sliceSize / step + 1)
                var i = 0
                while (i < result.sliceSize) {
                    pts += Offset(result.slice[i * 2], result.slice[i * 2 + 1])
                    i += step
                }
                val alignment = result.plan.alignment
                val ms = scan.measurements.map { m ->
                    val a = alignment.toSite(m.start)
                    val b = alignment.toSite(m.end)
                    val text = listOf(m.label.trim(), LengthFormat.format(m.lengthM)).filter { it.isNotEmpty() }.joinToString(" ")
                    PlanMeasurement(Vec2(a.x, a.y), Vec2(b.x, b.y), text)
                }
                pts to ms
            }
            _state.value = PlanState.Loaded(project, scan, result, slice, measurements)
        }
    }

    fun export(format: ExportFormat) {
        val loaded = _state.value as? PlanState.Loaded ?: return
        if (_exporting.value) return
        viewModelScope.launch {
            _exporting.value = true
            try {
                _events.emit(ExportEvent.Share(exporter.export(loaded.project, loaded.scan, format), format))
            } catch (e: Exception) {
                _events.emit(ExportEvent.Failed(e.message ?: e.javaClass.simpleName))
            } finally {
                _exporting.value = false
            }
        }
    }

    companion object {
        private const val MAX_SLICE_POINTS = 40_000
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FloorPlanScreen(projectId: String, scanId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: FloorPlanViewModel = viewModel { FloorPlanViewModel(projectId, scanId, context.app) }
    val state by vm.state.collectAsStateWithLifecycle()
    val exporting by vm.exporting.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val exportFailed = stringResource(R.string.export_failed)
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is ExportEvent.Share -> ExportManager.share(context, event.file, event.format)
                is ExportEvent.Failed -> snackbar.showSnackbar("$exportFailed: ${event.message}")
            }
        }
    }
    var resetToken by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_floor_plan)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { resetToken++ }) {
                        Icon(Icons.Filled.CenterFocusStrong, contentDescription = stringResource(R.string.viewer_reset))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (val s = state) {
                PlanState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.size(12.dp))
                        Text(stringResource(R.string.plan_analyzing))
                    }
                }
                PlanState.Missing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.scan_missing))
                }
                is PlanState.Loaded -> {
                    PlanSummary(s)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        PlanCanvas(s, resetToken, Modifier.fillMaxSize())
                        if (s.result.plan.walls.isEmpty()) {
                            Text(
                                stringResource(R.string.plan_no_walls),
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(24.dp)
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                                    .padding(12.dp),
                            )
                        }
                    }
                    ExportBar(enabled = !exporting, onExport = vm::export)
                }
            }
        }
    }
}

@Composable
private fun PlanSummary(s: PlanState.Loaded) {
    val plan = s.result.plan
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SummaryItem(stringResource(R.string.plan_walls), plan.walls.size.toString())
            SummaryItem(stringResource(R.string.plan_total_length), LengthFormat.format(plan.totalWallLength))
            SummaryItem(
                stringResource(R.string.plan_room_height),
                plan.roomHeight?.let { LengthFormat.format(it) } ?: "–",
            )
        }
    }
}

@Composable
private fun SummaryItem(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun ExportBar(enabled: Boolean, onExport: (ExportFormat) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(4.dp))
        for (format in listOf(ExportFormat.DXF_PLAN, ExportFormat.OBJ_WALLS, ExportFormat.PTS, ExportFormat.PLY)) {
            AssistChip(enabled = enabled, onClick = { onExport(format) }, label = { Text(stringResource(format.label)) })
        }
    }
}

/** Plan drawing: world (metres, Y north) to screen: x' = ox + x·s, y' = oy − y·s. */
@Composable
private fun PlanCanvas(s: PlanState.Loaded, resetToken: Int, modifier: Modifier = Modifier) {
    val plan = s.result.plan
    val bounds = remember(s) {
        val wallBounds = plan.bounds()
        val sliceBounds = Bounds2.of(s.slicePoints.map { Vec2(it.x, it.y) })
        when {
            wallBounds != null && sliceBounds != null -> wallBounds.union(sliceBounds)
            else -> wallBounds ?: sliceBounds ?: Bounds2(Vec2(-2f, -2f), Vec2(2f, 2f))
        }
    }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(0f) }
    var ox by remember { mutableFloatStateOf(0f) }
    var oy by remember { mutableFloatStateOf(0f) }

    fun fit() {
        if (canvasSize.width == 0 || canvasSize.height == 0) return
        val w = max(bounds.width, 0.5f)
        val h = max(bounds.height, 0.5f)
        scale = min(canvasSize.width / w, canvasSize.height / h) * 0.82f
        ox = canvasSize.width / 2f - bounds.center.x * scale
        oy = canvasSize.height / 2f + bounds.center.y * scale
    }
    LaunchedEffect(canvasSize, resetToken) { fit() }

    val textMeasurer = rememberTextMeasurer()
    val dimStyle = TextStyle(color = PlanColors.Dimension, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    val measureStyle = TextStyle(color = PlanColors.Measurement, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

    Canvas(
        modifier
            .background(PlanColors.Background)
            .onSizeChanged { canvasSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(5f, 5000f)
                    val k = newScale / scale
                    ox = centroid.x - (centroid.x - ox) * k + pan.x
                    oy = centroid.y - (centroid.y - oy) * k + pan.y
                    scale = newScale
                }
            },
    ) {
        if (scale <= 0f) return@Canvas
        drawGrid(ox, oy, scale)

        withTransform({
            translate(ox, oy)
            scale(scale, -scale, pivot = Offset.Zero)
        }) {
            if (s.slicePoints.isNotEmpty()) {
                drawPoints(s.slicePoints, PointMode.Points, PlanColors.Slice, strokeWidth = 2.5f / scale, cap = StrokeCap.Round)
            }
            for (w in plan.walls) {
                drawLine(
                    PlanColors.Wall,
                    Offset(w.start.x, w.start.y),
                    Offset(w.end.x, w.end.y),
                    strokeWidth = 4.dp.toPx() / scale,
                    cap = StrokeCap.Square,
                )
            }
            for (m in s.measurements) {
                drawLine(
                    PlanColors.Measurement,
                    Offset(m.start.x, m.start.y),
                    Offset(m.end.x, m.end.y),
                    strokeWidth = 2.dp.toPx() / scale,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx() / scale, 5.dp.toPx() / scale)),
                )
            }
        }

        fun toScreen(p: Vec2) = Offset(ox + p.x * scale, oy - p.y * scale)
        val centroid = plan.walls.map { it.midpoint }.let { mids ->
            if (mids.isEmpty()) Vec2.ZERO else mids.reduce { a, b -> a + b } * (1f / mids.size)
        }
        for (w in plan.walls) {
            // Label on the outside of the wall.
            var normal = w.direction.perp()
            if ((w.midpoint - centroid) dot normal < 0f) normal = -normal
            val anchor = toScreen(w.midpoint + normal * (18.dp.toPx() / scale))
            drawLabel(textMeasurer, LengthFormat.toMillimeters(w.length).toString(), anchor, dimStyle)
        }
        for (m in s.measurements) {
            drawLabel(textMeasurer, m.text, toScreen(Vec2.lerp(m.start, m.end, 0.5f)), measureStyle)
        }
    }
}

private fun DrawScope.drawGrid(ox: Float, oy: Float, scale: Float) {
    // 1 m grid, or 5 m when zoomed far out.
    val step = if (scale < 12f) 5f else 1f
    val left = (0 - ox) / scale
    val right = (size.width - ox) / scale
    val top = (oy - 0) / scale
    val bottom = (oy - size.height) / scale
    var x = floor(left / step) * step
    while (x <= right) {
        val sx = ox + x * scale
        drawLine(PlanColors.Grid, Offset(sx, 0f), Offset(sx, size.height), strokeWidth = 1f)
        x += step
    }
    var y = floor(bottom / step) * step
    while (y <= top) {
        val sy = oy - y * scale
        drawLine(PlanColors.Grid, Offset(0f, sy), Offset(size.width, sy), strokeWidth = 1f)
        y += step
    }
}

private fun DrawScope.drawLabel(
    measurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
    center: Offset,
    style: TextStyle,
) {
    val layout = measurer.measure(text, style)
    val topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f)
    drawRect(
        PlanColors.Background.copy(alpha = 0.85f),
        topLeft = topLeft - Offset(3f, 1f),
        size = androidx.compose.ui.geometry.Size(layout.size.width + 6f, layout.size.height + 2f),
    )
    drawText(layout, topLeft = topLeft)
}
