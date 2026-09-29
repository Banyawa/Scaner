package com.banyawa.sitescanner.ui.project

import android.content.Context
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.banyawa.sitescanner.R
import com.banyawa.sitescanner.SiteScannerApp
import com.banyawa.sitescanner.app
import com.banyawa.sitescanner.core.project.CaptureMode
import com.banyawa.sitescanner.core.project.Measurement
import com.banyawa.sitescanner.core.project.Project
import com.banyawa.sitescanner.core.project.ScanInfo
import com.banyawa.sitescanner.core.units.LengthFormat
import com.banyawa.sitescanner.data.ExportEvent
import com.banyawa.sitescanner.data.ExportFormat
import com.banyawa.sitescanner.data.ExportManager
import com.banyawa.sitescanner.scan.ScanActivity
import com.banyawa.sitescanner.ui.common.EmptyState
import com.banyawa.sitescanner.ui.common.formatCount
import com.banyawa.sitescanner.ui.common.formatDateTime
import com.banyawa.sitescanner.ui.common.formatDuration
import com.banyawa.sitescanner.ui.projects.ProjectDialog
import com.google.ar.core.ArCoreApk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProjectDetailViewModel(private val projectId: String, private val app: SiteScannerApp) : ViewModel() {
    private val repository = app.repository
    private val exporter = ExportManager(app, app.analysis)

    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    private val _events = MutableSharedFlow<ExportEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ExportEvent> = _events.asSharedFlow()

    fun refresh() {
        viewModelScope.launch {
            _project.value = withContext(Dispatchers.IO) { repository.get(projectId) }
            _loaded.value = true
        }
    }

    fun updateDetails(name: String, location: String, notes: String) {
        val current = _project.value ?: return
        viewModelScope.launch {
            _project.value = withContext(Dispatchers.IO) {
                repository.update(current.copy(name = name.trim(), location = location.trim(), notes = notes.trim()))
            }
        }
    }

    fun deleteScan(scan: ScanInfo) {
        viewModelScope.launch {
            _project.value = withContext(Dispatchers.IO) { repository.deleteScan(projectId, scan.id) }
        }
    }

    fun renameMeasurement(scan: ScanInfo, measurement: Measurement, label: String) {
        val updated = scan.copy(
            measurements = scan.measurements.map { if (it.id == measurement.id) it.copy(label = label.trim()) else it },
        )
        viewModelScope.launch {
            _project.value = withContext(Dispatchers.IO) { repository.upsertScan(projectId, updated) }
        }
    }

    fun export(scan: ScanInfo, format: ExportFormat) {
        val project = _project.value ?: return
        if (_exporting.value) return
        viewModelScope.launch {
            _exporting.value = true
            try {
                val file = exporter.export(project, scan, format)
                _events.emit(ExportEvent.Share(file, format))
            } catch (e: Exception) {
                _events.emit(ExportEvent.Failed(e.message ?: e.javaClass.simpleName))
            } finally {
                _exporting.value = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    projectId: String,
    onBack: () -> Unit,
    onOpenViewer: (String) -> Unit,
    onOpenPlan: (String) -> Unit,
) {
    val context = LocalContext.current
    val vm: ProjectDetailViewModel = viewModel { ProjectDetailViewModel(projectId, context.app) }
    val project by vm.project.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val exporting by vm.exporting.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    val exportFailed = stringResource(R.string.export_failed)
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is ExportEvent.Share -> ExportManager.share(context, event.file, event.format)
                is ExportEvent.Failed -> snackbar.showSnackbar("$exportFailed: ${event.message}")
            }
        }
    }
    val arSupported = rememberArSupport(context)

    var editing by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ScanInfo?>(null) }
    var renaming by remember { mutableStateOf<Pair<ScanInfo, Measurement>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { editing = true }, enabled = project != null) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.project_edit))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { context.startActivity(ScanActivity.newIntent(context, projectId)) },
                icon = { Icon(Icons.Filled.DocumentScanner, contentDescription = null) },
                text = { Text(stringResource(R.string.scan_new)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val p = project
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (exporting) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            when {
                p == null && !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                p == null -> EmptyState(Icons.Filled.Warning, stringResource(R.string.project_missing), "")
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { ProjectHeader(p, arSupported) }
                    if (p.scans.isEmpty()) {
                        item {
                            EmptyState(
                                icon = Icons.Filled.ViewInAr,
                                title = stringResource(R.string.scans_empty_title),
                                body = stringResource(R.string.scans_empty_body),
                                modifier = Modifier.height(320.dp),
                            )
                        }
                    }
                    items(p.scans.asReversed(), key = { it.id }) { scan ->
                        ScanCard(
                            scan = scan,
                            exporting = exporting,
                            onView = { onOpenViewer(scan.id) },
                            onPlan = { onOpenPlan(scan.id) },
                            onExport = { vm.export(scan, it) },
                            onDelete = { pendingDelete = scan },
                            onRenameMeasurement = { renaming = scan to it },
                        )
                    }
                }
            }
        }
    }

    val current = project
    if (editing && current != null) {
        ProjectDialog(
            title = stringResource(R.string.project_edit),
            initialName = current.name,
            initialLocation = current.location,
            initialNotes = current.notes,
            onDismiss = { editing = false },
            onConfirm = { name, location, notes ->
                editing = false
                vm.updateDetails(name, location, notes)
            },
        )
    }

    pendingDelete?.let { scan ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.scan_delete_title)) },
            text = { Text(stringResource(R.string.scan_delete_message, scan.name)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteScan(scan)
                    pendingDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    renaming?.let { (scan, measurement) ->
        var label by rememberSaveable(measurement.id) { mutableStateOf(measurement.label) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.measurement_label_title, LengthFormat.format(measurement.lengthM))) },
            text = {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.measurement_label_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameMeasurement(scan, measurement, label)
                    renaming = null
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** null while ARCore availability is still being determined. */
@Composable
private fun rememberArSupport(context: Context): Boolean? {
    val state = produceState<Boolean?>(initialValue = null, context) {
        val arCore = ArCoreApk.getInstance()
        var availability = arCore.checkAvailability(context)
        var attempts = 0
        while (availability.isTransient && attempts < 25) {
            delay(200)
            availability = arCore.checkAvailability(context)
            attempts++
        }
        value = availability.isSupported
    }
    return state.value
}

@Composable
private fun ProjectHeader(project: Project, arSupported: Boolean?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (project.location.isNotBlank()) {
            Text(project.location, style = MaterialTheme.typography.titleSmall)
        }
        if (project.notes.isNotBlank()) {
            Text(project.notes, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            stringResource(R.string.project_created, formatDateTime(project.createdAt)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (arSupported == false) {
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ar_not_supported), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ScanCard(
    scan: ScanInfo,
    exporting: Boolean,
    onView: () -> Unit,
    onPlan: () -> Unit,
    onExport: (ExportFormat) -> Unit,
    onDelete: () -> Unit,
    onRenameMeasurement: (Measurement) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(scan.name, style = MaterialTheme.typography.titleMedium)
                    val mode = stringResource(
                        if (scan.captureMode == CaptureMode.RAW_DEPTH) R.string.scan_mode_depth else R.string.scan_mode_features,
                    )
                    Text(
                        "${formatDateTime(scan.createdAt)} · $mode",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.scan_summary, formatCount(scan.pointCount), scan.measurements.size, formatDuration(scan.durationSec)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                }
            }

            if (scan.measurements.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                scan.measurements.forEachIndexed { i, m ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onRenameMeasurement(m) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Straighten, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            m.label.ifBlank { stringResource(R.string.measurement_default_label, i + 1) },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(LengthFormat.format(m.lengthM), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }

            if (scan.pointCount == 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.scan_no_points),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onView, enabled = scan.pointCount > 0) {
                    Icon(Icons.Filled.ViewInAr, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_view_3d))
                }
                OutlinedButton(onClick = onPlan, enabled = scan.pointCount > 0) {
                    Icon(Icons.Filled.Architecture, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_floor_plan))
                }
                Box {
                    OutlinedButton(onClick = { menuOpen = true }, enabled = !exporting) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_export))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        ExportFormat.entries.forEach { format ->
                            DropdownMenuItem(
                                text = { Text(stringResource(format.label)) },
                                onClick = {
                                    menuOpen = false
                                    onExport(format)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
