package com.banyawa.sitescanner.ui.floorplan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import com.banyawa.sitescanner.core.floorplan.DoorSwing
import com.banyawa.sitescanner.core.floorplan.FloorPlanResult
import com.banyawa.sitescanner.core.floorplan.Hinge
import com.banyawa.sitescanner.core.floorplan.Opening
import com.banyawa.sitescanner.core.floorplan.OpeningEdits
import com.banyawa.sitescanner.core.floorplan.OpeningPlacement
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
import kotlin.math.abs
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

        /**
         * The opening whose symbol (opening line, open leaf or tag at [tagOffset] into the
         * room) is nearest to [p], within [maxDistance]; plan metres.
         */
        fun openingAt(p: Vec2, maxDistance: Float, tagOffset: Float): Opening? = openings
            .map { o ->
                val leaf = o.leaves.minOfOrNull { segmentDistance(p, it.hinge, it.open) } ?: Float.MAX_VALUE
                val tag = p.distanceTo(o.midpoint + o.interiorNormal * tagOffset)
                o to minOf(o.distanceTo(p), leaf, tag)
            }
            .filter { it.second <= maxDistance }
            .minByOrNull { it.second }
            ?.first
    }
}

/** An opening open in the editor; [isNew] until it is first saved. */
private class OpeningDraft(val opening: Opening, val isNew: Boolean)

private fun segmentDistance(p: Vec2, a: Vec2, b: Vec2): Float {
    val ab = b - a
    val len2 = ab dot ab
    if (len2 < 1e-12f) return p.distanceTo(a)
    val t = (((p - a) dot ab) / len2).coerceIn(0f, 1f)
    return p.distanceTo(a + ab * t)
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

    /** Stores an edited opening, or adds it when it is new. */
    fun saveOpening(opening: Opening) = editOpenings { list ->
        if (list.any { it.id == opening.id }) list.map { if (it.id == opening.id) opening else it } else list + opening
    }

    fun delete(id: String) = editOpenings { list -> list.filterNot { it.id == id } }

    /** A new door on the wall nearest to [at] (plan metres), or null when no wall is that close. */
    fun draftAt(at: Vec2, maxDistance: Float): Opening? {
        val loaded = _state.value as? PlanState.Loaded ?: return null
        val plan = loaded.result.plan
        return OpeningPlacement.place(plan, at, OpeningType.DOOR, OpeningPlacement.newId(plan.openings), maxDistance)
    }

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
    var editing by remember { mutableStateOf<OpeningDraft?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val missedWall = stringResource(R.string.plan_add_miss)

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
                    if (tab == 0 && state is PlanState.Loaded) {
                        IconButton(onClick = { adding = !adding }) {
                            if (adding) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                            } else {
                                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.plan_add_opening))
                            }
                        }
                    }
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
                            PlanCanvas(
                                s,
                                resetToken,
                                onTap = { p, radius, tagOffset ->
                                    if (adding) {
                                        val draft = vm.draftAt(p, radius.coerceIn(MIN_WALL_PICK, MAX_WALL_PICK))
                                        if (draft != null) {
                                            adding = false
                                            editing = OpeningDraft(draft, isNew = true)
                                        } else {
                                            scope.launch { snackbar.showSnackbar(missedWall) }
                                        }
                                    } else {
                                        s.openingAt(p, radius.coerceAtLeast(MIN_WALL_PICK), tagOffset)?.let {
                                            editing = OpeningDraft(it, isNew = false)
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (adding) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                                ) {
                                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.plan_add_hint), style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
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
                            onEdit = { editing = OpeningDraft(it, isNew = false) },
                            onAdd = {
                                tab = 0
                                adding = true
                            },
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
    editing?.let { draft ->
        EditOpeningDialog(
            draft = draft,
            tag = loaded?.tags?.get(draft.opening.id).orEmpty(),
            roomHeight = loaded?.result?.plan?.roomHeight,
            onDismiss = { editing = null },
            onSave = {
                vm.saveOpening(it)
                editing = null
            },
            onDelete = {
                vm.delete(draft.opening.id)
                editing = null
            },
        )
    }
}

/** Taps this close to a wall (metres) pick it, whatever the zoom. */
private const val MIN_WALL_PICK = 0.05f
private const val MAX_WALL_PICK = 0.6f

/** Where door / window tags sit, into the room from the opening. */
private val TAG_OFFSET = 22.dp

/** Taps within this distance of a symbol hit it. */
private val TAP_RADIUS = 24.dp

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
private fun hingeLabel(hinge: Hinge?): String = stringResource(
    when (hinge) {
        Hinge.LEFT -> R.string.hinge_left
        Hinge.RIGHT -> R.string.hinge_right
        Hinge.BOTH -> R.string.hinge_both
        null -> R.string.hinge_none
    },
)

@Composable
private fun swingLabel(swing: DoorSwing): String =
    hingeLabel(swing.hinge) + " · " + stringResource(if (swing.inward) R.string.swing_in else R.string.swing_out)

@Composable
private fun OpeningList(
    state: PlanState.Loaded,
    onEdit: (Opening) -> Unit,
    onAdd: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                OutlinedButton(onClick = onAdd) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.plan_add_opening))
                }
                if (state.openingsEdited) {
                    OutlinedButton(onClick = onRestore) {
                        Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.openings_restore))
                    }
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
                    o.swing?.let {
                        append(" · ")
                        append(swingLabel(it))
                    }
                }
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (o.needsCheck) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = stringResource(R.string.opening_verify),
                    tint = MaterialTheme.colorScheme.error,
                )
            } else if (o.manual) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.opening_manual),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun mmText(m: Float) = LengthFormat.toMillimeters(m).toString()

/**
 * Edits an opening, or sets up one placed by hand: type, clear sizes in millimetres (as
 * measured with a tape), door swing and which side of the wall the room is on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditOpeningDialog(
    draft: OpeningDraft,
    tag: String,
    roomHeight: Float?,
    onDismiss: () -> Unit,
    onSave: (Opening) -> Unit,
    onDelete: () -> Unit,
) {
    // Type, swing and room side; the sizes stay in the text fields until saved.
    var current by remember(draft) { mutableStateOf(draft.opening) }
    var fromLeft by remember(draft) { mutableStateOf(mmText(draft.opening.distanceFromWallStart)) }
    var width by remember(draft) { mutableStateOf(mmText(draft.opening.width)) }
    var height by remember(draft) { mutableStateOf(mmText(draft.opening.height)) }
    var sill by remember(draft) { mutableStateOf(mmText(draft.opening.bottom)) }

    val wallMm = LengthFormat.toMillimeters(current.wallLength)
    val isWindow = current.type == OpeningType.WINDOW
    val fromLeftMm = fromLeft.toLongOrNull()
    val widthMm = width.toLongOrNull()
    val heightMm = height.toLongOrNull()
    val sillMm = if (isWindow) sill.toLongOrNull() else 0L
    val error: String? = when {
        fromLeftMm == null || widthMm == null || heightMm == null || sillMm == null -> stringResource(R.string.opening_error_number)
        widthMm < MIN_SIZE_MM || heightMm < MIN_SIZE_MM -> stringResource(R.string.opening_error_size)
        fromLeftMm + widthMm > wallMm + WALL_SLACK_MM ->
            stringResource(R.string.opening_error_beyond, LengthFormat.format(current.wallLength))
        else -> null
    }
    val result: Opening? = run {
        if (error != null || fromLeftMm == null || widthMm == null || heightMm == null || sillMm == null) return@run null
        val typed = fromLeft != mmText(current.distanceFromWallStart) || width != mmText(current.width) ||
            height != mmText(current.height) || (isWindow && sill != mmText(current.bottom))
        if (!draft.isNew && !typed) return@run current
        val bottom = sillMm / 1000f
        current.resized(fromLeftMm / 1000f, widthMm / 1000f, bottom, bottom + heightMm / 1000f).copy(manual = true)
    }

    fun changeType(t: OpeningType) {
        if (t == current.type) return
        var next = current.withType(t)
        if (draft.isNew) {
            // A fresh opening takes the new type's usual size, centred where it was placed.
            val size = OpeningPlacement.defaultSize(t, roomHeight)
            val w = min(size.width, next.wallLength)
            val centre = next.distanceFromWallStart + next.width / 2f
            next = next.resized((centre - w / 2f).coerceIn(0f, next.wallLength - w), w, size.bottom, size.top)
            fromLeft = mmText(next.distanceFromWallStart)
            width = mmText(next.width)
        }
        height = mmText(next.height)
        sill = mmText(next.bottom)
        current = next
    }

    fun swapRoomSide() {
        val next = current.flipped()
        // "From left" now counts from the other corner.
        val typed = fromLeft != mmText(current.distanceFromWallStart) || width != mmText(current.width)
        fromLeft = if (typed && fromLeftMm != null && widthMm != null) {
            (wallMm - fromLeftMm - widthMm).coerceAtLeast(0L).toString()
        } else {
            mmText(next.distanceFromWallStart)
        }
        current = next
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (draft.isNew) stringResource(R.string.plan_add_opening) else "$tag · ${openingTypeLabel(draft.opening.type)}")
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (current.needsCheck) {
                    Text(
                        stringResource(R.string.opening_verify),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in OpeningType.entries) {
                        FilterChip(selected = current.type == t, onClick = { changeType(t) }, label = { Text(openingTypeLabel(t)) })
                    }
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(R.string.opening_wall_length, LengthFormat.format(current.wallLength)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MmField(fromLeft, { fromLeft = it }, stringResource(R.string.opening_field_from_left), Modifier.weight(1f))
                    MmField(width, { width = it }, stringResource(R.string.opening_field_width), Modifier.weight(1f))
                }
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MmField(height, { height = it }, stringResource(R.string.opening_field_height), Modifier.weight(1f))
                    if (isWindow) {
                        MmField(sill, { sill = it }, stringResource(R.string.opening_field_sill), Modifier.weight(1f))
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (current.type == OpeningType.DOOR) {
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.opening_swing), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (h in listOf(Hinge.LEFT, Hinge.RIGHT, Hinge.BOTH, null)) {
                            FilterChip(
                                selected = current.swing?.hinge == h,
                                onClick = { current = current.copy(swing = h?.let { DoorSwing(it, current.swing?.inward ?: true) }) },
                                label = { Text(hingeLabel(h)) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (inward in listOf(true, false)) {
                            FilterChip(
                                selected = current.swing?.inward == inward,
                                enabled = current.swing != null,
                                onClick = { current.swing?.let { current = current.copy(swing = it.copy(inward = inward)) } },
                                label = { Text(stringResource(if (inward) R.string.swing_in else R.string.swing_out)) },
                            )
                        }
                    }
                }
                Spacer(Modifier.size(12.dp))
                OpeningPreview(current.type, current.swing, Modifier.fillMaxWidth().height(140.dp))
                TextButton(onClick = { swapRoomSide() }) {
                    Icon(Icons.Filled.SwapVert, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.opening_flip))
                }
                if (!draft.isNew) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.opening_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = result != null, onClick = { result?.let(onSave) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun MmField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onValueChange(v.filter { it.isDigit() }.take(MAX_MM_DIGITS)) },
        label = { Text(label, maxLines = 1) },
        isError = value.toLongOrNull() == null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/**
 * The opening seen in plan from inside the room: the wall across the middle, the room
 * below, left and right as the user sees them facing the wall.
 */
@Composable
private fun OpeningPreview(type: OpeningType, swing: DoorSwing?, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val room = stringResource(R.string.opening_preview_room)
    val left = stringResource(R.string.opening_preview_left)
    val right = stringResource(R.string.opening_preview_right)
    val labelStyle = TextStyle(color = PlanColors.Dimension, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Canvas(modifier.background(PlanColors.Background, RoundedCornerShape(8.dp))) {
        val wallY = size.height / 2f
        val margin = 12.dp.toPx()
        val w = min(size.width * 0.34f, wallY - margin)
        val x0 = size.width / 2f - w / 2f
        val x1 = x0 + w
        val wallStroke = 4.dp.toPx()
        drawLine(PlanColors.Wall, Offset(margin, wallY), Offset(x0, wallY), wallStroke)
        drawLine(PlanColors.Wall, Offset(x1, wallY), Offset(size.width - margin, wallY), wallStroke)
        val color = openingColor(type)
        val line = 2.dp.toPx()
        if (type == OpeningType.WINDOW) {
            val half = 5.dp.toPx()
            drawRect(PlanColors.WindowFill, topLeft = Offset(x0, wallY - half), size = Size(w, 2 * half))
            drawRect(color, topLeft = Offset(x0, wallY - half), size = Size(w, 2 * half), style = Stroke(width = line))
        } else {
            val jamb = 7.dp.toPx()
            drawLine(color, Offset(x0, wallY - jamb), Offset(x0, wallY + jamb), line)
            drawLine(color, Offset(x1, wallY - jamb), Offset(x1, wallY + jamb), line)
            if (swing == null) {
                drawLine(
                    color,
                    Offset(x0, wallY),
                    Offset(x1, wallY),
                    line,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                )
            } else {
                // Screen y grows downwards, into the room.
                fun leaf(hingeX: Float, closedX: Float) {
                    val r = abs(closedX - hingeX)
                    val openY = wallY + if (swing.inward) r else -r
                    drawLine(color, Offset(hingeX, wallY), Offset(hingeX, openY), line)
                    val closedAngle = if (closedX > hingeX) 0f else 180f
                    var sweep = (if (swing.inward) 90f else 270f) - closedAngle
                    if (sweep > 180f) sweep -= 360f
                    if (sweep <= -180f) sweep += 360f
                    drawArc(
                        color,
                        startAngle = closedAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(hingeX - r, wallY - r),
                        size = Size(2 * r, 2 * r),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
                when (swing.hinge) {
                    Hinge.LEFT -> leaf(x0, x1)
                    Hinge.RIGHT -> leaf(x1, x0)
                    Hinge.BOTH -> {
                        leaf(x0, (x0 + x1) / 2f)
                        leaf(x1, (x0 + x1) / 2f)
                    }
                }
            }
        }
        val labelY = wallY - 14.dp.toPx()
        drawLabel(measurer, left, Offset(margin + (x0 - margin) / 2f, labelY), labelStyle)
        drawLabel(measurer, right, Offset(x1 + (size.width - margin - x1) / 2f, labelY), labelStyle)
        drawLabel(measurer, room, Offset(margin + (x0 - margin) / 2f, size.height - 16.dp.toPx()), labelStyle)
    }
}

private const val MIN_SIZE_MM = 100L

/** Openings may reach this far past the measured wall ends (the wall length is itself scanned). */
private const val WALL_SLACK_MM = 50L
private const val MAX_MM_DIGITS = 6

// --- plan drawing ------------------------------------------------------------------------

/**
 * Plan drawing: world (metres, Y north) to screen: x' = ox + x·s, y' = oy − y·s.
 * [onTap] gets the plan point, the tap radius and the tag offset, both in metres at the
 * current zoom.
 */
@Composable
private fun PlanCanvas(
    s: PlanState.Loaded,
    resetToken: Int,
    onTap: (point: Vec2, radius: Float, tagOffset: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
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
    val tapHandler by rememberUpdatedState(onTap)

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
            }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    if (scale > 0f) {
                        tapHandler(Vec2((pos.x - ox) / scale, (oy - pos.y) / scale), TAP_RADIUS.toPx() / scale, TAG_OFFSET.toPx() / scale)
                    }
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
            val anchor = toScreen(o.midpoint + o.interiorNormal * (TAG_OFFSET.toPx() / scale))
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
        // Clear the wall line, then mark the jambs and either the leaf with its swing or,
        // when the swing is unknown, the threshold.
        drawLine(PlanColors.Background, off(a), off(b), strokeWidth = 6.dp.toPx() / scale)
        val jamb = n * 0.1f
        drawLine(PlanColors.Door, off(a - jamb), off(a + jamb), strokeWidth = 2.5.dp.toPx() / scale)
        drawLine(PlanColors.Door, off(b - jamb), off(b + jamb), strokeWidth = 2.5.dp.toPx() / scale)
        val leaves = o.leaves
        if (leaves.isEmpty()) {
            drawLine(
                PlanColors.Door,
                off(a),
                off(b),
                strokeWidth = 1.5.dp.toPx() / scale,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx() / scale, 4.dp.toPx() / scale)),
            )
        }
        for (leaf in leaves) {
            drawLine(PlanColors.Door, off(leaf.hinge), off(leaf.open), strokeWidth = 2.dp.toPx() / scale)
            val arc = Path().apply {
                moveTo(leaf.closed.x, leaf.closed.y)
                for (k in 1..ARC_STEPS) {
                    val p = leaf.arcPoint(k / ARC_STEPS.toFloat())
                    lineTo(p.x, p.y)
                }
            }
            drawPath(arc, PlanColors.Door, style = Stroke(width = 1.dp.toPx() / scale))
        }
    }
}

private const val ARC_STEPS = 24

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
