package com.banyawa.sitescanner.ui.floorplan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextMeasurer
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
import com.banyawa.sitescanner.core.floorplan.Opening
import com.banyawa.sitescanner.core.floorplan.OpeningEdits
import com.banyawa.sitescanner.core.floorplan.OpeningTags
import com.banyawa.sitescanner.core.floorplan.OpeningType
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
    ) : PlanState {
        val openings: List<Opening> get() = result.plan.openings
        val tags: Map<String, String> by lazy { OpeningTags.assign(openings) }
        val openingsEdited: Boolean get() = scan.openingEdits != null
    }
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

    fun changeType(id: String, type: OpeningType) = editOpenings { list ->
        list.map { if (it.id == id) it.copy(type = type, bottom = if (type == OpeningType.WINDOW) it.bottom else 0f) else it }
    }

    fun delete(id: String) = editOpenings { list -> list.filterNot { it.id == id } }

    /** Drops the user's edits and goes back to what was detected. */
    fun restoreDetected() {
        val loaded = _state.value as? PlanState.Loaded ?: return
        viewModelScope.launch {
            val scan = loaded.scan.copy(openingEdits = null)
            val result = app.analysis.floorPlan(projectId, scan)
            save(loaded, scan, result)
        }
    }

    private fun editOpenings(transform: (List<Opening>) -> List<Opening>) {
        val loaded = _state.value as? PlanState.Loaded ?: return
        val plan = loaded.result.plan
        val edited = transform(plan.openings)
        val scan = loaded.scan.copy(openingEdits = OpeningEdits(plan.alignment, edited))
        viewModelScope.launch {
            save(loaded, scan, FloorPlanResult(plan.copy(openings = edited), loaded.result.slice))
        }
    }

    private suspend fun save(loaded: PlanState.Loaded, scan: ScanInfo, result: FloorPlanResult) {
        val project = withContext(Dispatchers.IO) { app.repository.upsertScan(projectId, scan) }
        _state.value = PlanState.Loaded(project, scan, result, loaded.slicePoints, loaded.measurements)
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
    var resetToken by remember { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Opening?>(null) }

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
                    if (tab == 0) {
                        IconButton(onClick = { resetToken++ }) {
                            Icon(Icons.Filled.CenterFocusStrong, contentDescription = stringResource(R.string.viewer_reset))
                        }
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
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.plan_tab_plan)) })
                        Tab(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            text = { Text(stringResource(R.string.plan_tab_openings, s.openings.size)) },
                        )
                    }
                    if (tab == 0) {
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
                    } else {
                        OpeningList(
                            state = s,
                            onEdit = { editing = it },
                            onRestore = vm::restoreDetected,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                    ExportBar(
                        enabled = !exporting,
                        formats = if (tab == 0) PLAN_FORMATS else OPENING_FORMATS,
                        onExport = vm::export,
                    )
                }
            }
        }
    }

    val loaded = state as? PlanState.Loaded
    editing?.let { opening ->
        EditOpeningDialog(
            opening = opening,
            tag = loaded?.tags?.get(opening.id).orEmpty(),
            onDismiss = { editing = null },
            onType = {
                vm.changeType(opening.id, it)
                editing = null
            },
            onDelete = {
                vm.delete(opening.id)
                editing = null
            },
        )
    }
}

private val PLAN_FORMATS = listOf(ExportFormat.DXF_PLAN, ExportFormat.OBJ_WALLS, ExportFormat.PTS, ExportFormat.PLY)
private val OPENING_FORMATS = listOf(ExportFormat.CSV_OPENINGS, ExportFormat.DXF_PLAN)

@Composable
private fun PlanSummary(s: PlanState.Loaded) {
    val plan = s.result.plan
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SummaryItem(stringResource(R.string.plan_walls), plan.walls.size.toString())
            SummaryItem(stringResource(R.string.plan_total_length), LengthFormat.format(plan.totalWallLength))
            SummaryItem(
                stringResource(R.string.plan_room_height),
                plan.roomHeight?.let { LengthFormat.format(it) } ?: "–",
            )
            SummaryItem(stringResource(R.string.plan_doors), s.openings.count { it.type != OpeningType.WINDOW }.toString())
            SummaryItem(stringResource(R.string.plan_windows), s.openings.count { it.type == OpeningType.WINDOW }.toString())
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
private fun ExportBar(enabled: Boolean, formats: List<ExportFormat>, onExport: (ExportFormat) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(4.dp))
        for (format in formats) {
            AssistChip(enabled = enabled, onClick = { onExport(format) }, label = { Text(stringResource(format.label)) })
        }
    }
}

// --- openings list -----------------------------------------------------------------------

@Composable
fun openingTypeLabel(type: OpeningType): String = stringResource(
    when (type) {
        OpeningType.DOOR -> R.string.opening_door
        OpeningType.WINDOW -> R.string.opening_window
        OpeningType.OPENING -> R.string.opening_opening
    },
)

private fun openingColor(type: OpeningType) = if (type == OpeningType.WINDOW) PlanColors.Window else PlanColors.Door

@Composable
private fun OpeningList(state: PlanState.Loaded, onEdit: (Opening) -> Unit, onRestore: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.openings_note), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (state.openingsEdited) {
            item {
                OutlinedButton(onClick = onRestore) {
                    Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.openings_restore))
                }
            }
        }
        if (state.openings.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.openings_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(state.openings, key = { it.id }) { o ->
            OpeningRow(o, state.tags[o.id].orEmpty(), onClick = { onEdit(o) })
        }
    }
}

@Composable
private fun OpeningRow(o: Opening, tag: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).background(openingColor(o.type), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(tag, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${openingTypeLabel(o.type)}  ${LengthFormat.toMillimeters(o.width)} × ${LengthFormat.toMillimeters(o.height)} mm",
                    style = MaterialTheme.typography.titleSmall,
                )
                val detail = buildString {
                    if (o.type == OpeningType.WINDOW) {
                        append(stringResource(R.string.opening_sill, LengthFormat.format(o.bottom)))
                        append(" · ")
                    }
                    append(stringResource(R.string.opening_head, LengthFormat.format(o.top)))
                    append(" · ")
                    append(stringResource(R.string.opening_from_left, LengthFormat.format(o.distanceFromWallStart)))
                }
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (o.confidence < LOW_CONFIDENCE) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = stringResource(R.string.opening_verify),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun EditOpeningDialog(
    opening: Opening,
    tag: String,
    onDismiss: () -> Unit,
    onType: (OpeningType) -> Unit,
    onDelete: () -> Unit,
) {
    var type by remember(opening.id) { mutableStateOf(opening.type) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$tag · ${LengthFormat.toMillimeters(opening.width)} × ${LengthFormat.toMillimeters(opening.height)} mm") },
        text = {
            Column {
                if (opening.confidence < LOW_CONFIDENCE) {
                    Text(
                        stringResource(R.string.opening_verify),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.size(8.dp))
                }
                for (t in OpeningType.entries) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = type == t, onClick = { type = t }, role = Role.RadioButton)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = type == t, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(openingTypeLabel(t))
                    }
                }
                Spacer(Modifier.size(8.dp))
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.opening_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (type != opening.type) onType(type) else onDismiss() }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val LOW_CONFIDENCE = 0.7f

// --- plan drawing ------------------------------------------------------------------------

/** Plan drawing: world (metres, Y north) to screen: x' = ox + x·s, y' = oy − y·s. */
@Composable
private fun PlanCanvas(s: PlanState.Loaded, resetToken: Int, modifier: Modifier = Modifier) {
    val plan = s.result.plan
    val bounds = remember(s.result) {
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
        scale = min(canvasSize.width / w, canvasSize.height / h) * 0.8f
        ox = canvasSize.width / 2f - bounds.center.x * scale
        oy = canvasSize.height / 2f + bounds.center.y * scale
    }
    LaunchedEffect(canvasSize, resetToken) { fit() }

    val textMeasurer = rememberTextMeasurer()
    val dimStyle = TextStyle(color = PlanColors.Dimension, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    val measureStyle = TextStyle(color = PlanColors.Measurement, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    val doorStyle = TextStyle(color = PlanColors.Door, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    val windowStyle = TextStyle(color = PlanColors.Window, fontSize = 13.sp, fontWeight = FontWeight.Bold)

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
            for (o in s.openings) drawOpening(o, scale)
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
        for (o in s.openings) {
            // Tag on the room side.
            val anchor = toScreen(o.midpoint + o.interiorNormal * (22.dp.toPx() / scale))
            drawLabel(textMeasurer, s.tags[o.id].orEmpty(), anchor, if (o.type == OpeningType.WINDOW) windowStyle else doorStyle)
        }
        for (m in s.measurements) {
            drawLabel(textMeasurer, m.text, toScreen(Vec2.lerp(m.start, m.end, 0.5f)), measureStyle)
        }
    }
}

/** Drawn in plan metres (inside the plan transform). */
private fun DrawScope.drawOpening(o: Opening, scale: Float) {
    val n = o.interiorNormal
    val a = o.start
    val b = o.end
    fun off(p: Vec2) = Offset(p.x, p.y)
    if (o.type == OpeningType.WINDOW) {
        val half = 0.07f
        val path = Path().apply {
            moveTo(a.x - n.x * half, a.y - n.y * half)
            lineTo(b.x - n.x * half, b.y - n.y * half)
            lineTo(b.x + n.x * half, b.y + n.y * half)
            lineTo(a.x + n.x * half, a.y + n.y * half)
            close()
        }
        drawPath(path, PlanColors.WindowFill)
        drawPath(path, PlanColors.Window, style = Stroke(width = 1.5.dp.toPx() / scale))
        drawLine(PlanColors.Window, off(a), off(b), strokeWidth = 1.5.dp.toPx() / scale)
    } else {
        // Clear the wall line, then mark the jambs and the threshold.
        drawLine(PlanColors.Background, off(a), off(b), strokeWidth = 6.dp.toPx() / scale)
        val jamb = n * 0.1f
        drawLine(PlanColors.Door, off(a - jamb), off(a + jamb), strokeWidth = 2.5.dp.toPx() / scale)
        drawLine(PlanColors.Door, off(b - jamb), off(b + jamb), strokeWidth = 2.5.dp.toPx() / scale)
        drawLine(
            PlanColors.Door,
            off(a),
            off(b),
            strokeWidth = 1.5.dp.toPx() / scale,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx() / scale, 4.dp.toPx() / scale)),
        )
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

private fun DrawScope.drawLabel(measurer: TextMeasurer, text: String, center: Offset, style: TextStyle) {
    if (text.isEmpty()) return
    val layout = measurer.measure(text, style)
    val topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f)
    drawRect(
        PlanColors.Background.copy(alpha = 0.85f),
        topLeft = topLeft - Offset(3f, 1f),
        size = Size(layout.size.width + 6f, layout.size.height + 2f),
    )
    drawText(layout, topLeft = topLeft)
}
