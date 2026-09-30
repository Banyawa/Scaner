package com.banyawa.sitescanner.ui.project

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
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
import com.banyawa.sitescanner.core.project.GeoPin
import com.banyawa.sitescanner.core.project.Measurement
import com.banyawa.sitescanner.core.project.MediaItem
import com.banyawa.sitescanner.core.project.MediaKind
import com.banyawa.sitescanner.core.project.Project
import com.banyawa.sitescanner.core.project.ScanInfo
import com.banyawa.sitescanner.core.units.LengthFormat
import com.banyawa.sitescanner.data.ExportEvent
import com.banyawa.sitescanner.data.ExportFormat
import com.banyawa.sitescanner.data.ExportManager
import com.banyawa.sitescanner.scan.ScanActivity
import com.banyawa.sitescanner.scan.ScanGuidePrefs
import com.banyawa.sitescanner.ui.common.EmptyState
import com.banyawa.sitescanner.ui.common.formatCount
import com.banyawa.sitescanner.ui.common.formatDateTime
import com.banyawa.sitescanner.ui.common.formatDuration
import com.banyawa.sitescanner.ui.projects.ProjectDialog
import com.banyawa.sitescanner.ui.projects.openInMaps
import com.google.ar.core.ArCoreApk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

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

    /** One-off notices for the snackbar, as string resources. */
    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    /** Progress (0..1) of the 3D models being generated, by scan id. */
    val building: StateFlow<Map<String, Float>> = app.modelBuilder.progress

    init {
        // A model finishing in the background changes the scan on disk: show it.
        viewModelScope.launch {
            building.map { it.keys }.distinctUntilChanged().drop(1).collect { refresh() }
        }
    }

    /** Generates the scan's model from its recording again (after an update, or a failed first try). */
    fun rebuild(scan: ScanInfo) {
        if (building.value.containsKey(scan.id)) return
        viewModelScope.launch {
            runCatching { app.modelBuilder.build(projectId, scan) }
                .onFailure { _events.emit(ExportEvent.Failed(it.message ?: it.javaClass.simpleName)) }
            _project.value = withContext(Dispatchers.IO) { repository.get(projectId) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _project.value = withContext(Dispatchers.IO) { repository.get(projectId) }
            _loaded.value = true
        }
    }

    fun updateDetails(name: String, location: String, notes: String, pin: GeoPin?) {
        val current = _project.value ?: return
        viewModelScope.launch {
            _project.value = withContext(Dispatchers.IO) {
                repository.update(current.copy(name = name.trim(), location = location.trim(), notes = notes.trim(), pin = pin))
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

    fun notify(@StringRes message: Int) {
        _messages.tryEmit(message)
    }

    fun mediaFile(item: MediaItem): File = repository.mediaFile(projectId, item)

    /** A record and file for the camera app to write to; [captureFinished] keeps or drops it. */
    fun newCapture(kind: MediaKind): MediaItem =
        repository.newMedia(projectId, kind, if (kind == MediaKind.PHOTO) "jpg" else "mp4")

    /** Keeps the photo / video the camera app wrote, or drops the empty file when it was cancelled. */
    fun captureFinished(item: MediaItem, saved: Boolean) {
        viewModelScope.launch {
            _project.value = withContext(Dispatchers.IO) {
                val file = repository.mediaFile(projectId, item)
                if (saved && file.length() > 0) {
                    repository.addMedia(projectId, item)
                } else {
                    file.delete()
                    repository.get(projectId)
                }
            }
        }
    }

    /** Copies photos and videos picked from the gallery into the project's media folder. */
    fun importMedia(uris: List<Uri>) {
        viewModelScope.launch {
            var failed = 0
            _project.value = withContext(Dispatchers.IO) {
                val resolver = app.contentResolver
                for (uri in uris) {
                    val mime = resolver.getType(uri)
                    val kind = if (mime?.startsWith("video/") == true) MediaKind.VIDEO else MediaKind.PHOTO
                    val extension = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }.orEmpty()
                    val item = repository.newMedia(projectId, kind, extension)
                    val file = repository.mediaFile(projectId, item)
                    try {
                        val input = resolver.openInputStream(uri) ?: throw IOException("Cannot read $uri")
                        input.use { source -> file.outputStream().use { source.copyTo(it) } }
                        repository.addMedia(projectId, item)
                    } catch (e: Exception) {
                        file.delete()
                        failed++
                    }
                }
                repository.get(projectId)
            }
            if (failed > 0) _messages.emit(R.string.media_import_failed)
        }
    }

    fun removeMedia(item: MediaItem) {
        viewModelScope.launch {
            _project.value = withContext(Dispatchers.IO) { repository.removeMedia(projectId, item.id) }
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
    val building by vm.building.collectAsStateWithLifecycle()
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
    LaunchedEffect(vm) {
        vm.messages.collect { snackbar.showSnackbar(context.getString(it)) }
    }
    val arSupported = rememberArSupport(context)

    var editing by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ScanInfo?>(null) }
    var pendingMediaDelete by remember { mutableStateOf<MediaItem?>(null) }
    // The scanning briefing, until the user has had enough of it.
    var briefing by rememberSaveable { mutableStateOf(false) }
    val startScan = { context.startActivity(ScanActivity.newIntent(context, projectId)) }
    var renaming by remember { mutableStateOf<Pair<ScanInfo, Measurement>?>(null) }

    // The photo / video the camera app is writing; survives rotation and the process being killed meanwhile.
    var pendingCapture by rememberSaveable(stateSaver = PendingMediaSaver) { mutableStateOf<MediaItem?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        pendingCapture?.let { vm.captureFinished(it, saved) }
        pendingCapture = null
    }
    val captureVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { saved ->
        pendingCapture?.let { vm.captureFinished(it, saved) }
        pendingCapture = null
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) vm.importMedia(uris)
    }
    fun startCapture(kind: MediaKind) {
        val item = vm.newCapture(kind)
        val uri = mediaUri(context, vm.mediaFile(item))
        pendingCapture = item
        try {
            if (kind == MediaKind.PHOTO) takePicture.launch(uri) else captureVideo.launch(uri)
        } catch (e: ActivityNotFoundException) {
            pendingCapture = null
            vm.notify(R.string.media_no_camera_app)
        }
    }
    // With CAMERA declared in the manifest, the camera intent only works once the app holds the permission.
    var wantedCapture by remember { mutableStateOf<MediaKind?>(null) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val kind = wantedCapture
        wantedCapture = null
        if (!granted) vm.notify(R.string.media_camera_permission) else if (kind != null) startCapture(kind)
    }
    fun capture(kind: MediaKind) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCapture(kind)
        } else {
            wantedCapture = kind
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

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
            // Photo, video and import beside the scan button, smaller than it.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MediaFab(Icons.Filled.PhotoCamera, stringResource(R.string.media_take_photo)) { capture(MediaKind.PHOTO) }
                MediaFab(Icons.Filled.Videocam, stringResource(R.string.media_record_video)) { capture(MediaKind.VIDEO) }
                MediaFab(Icons.Filled.PhotoLibrary, stringResource(R.string.media_import)) {
                    val request = PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        .build()
                    pickMedia.launch(request)
                }
                ExtendedFloatingActionButton(
                    onClick = {
                        if (ScanGuidePrefs.briefingWanted(context)) {
                            briefing = true
                        } else {
                            startScan()
                        }
                    },
                    icon = { Icon(Icons.Filled.DocumentScanner, contentDescription = null) },
                    text = { Text(stringResource(R.string.scan_new)) },
                )
            }
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
                    item { MediaHeader(count = p.media.size) }
                    items(p.media.asReversed().chunked(MEDIA_COLUMNS), key = { row -> row.first().id }) { row ->
                        MediaRow(
                            items = row,
                            columns = MEDIA_COLUMNS,
                            fileOf = { vm.mediaFile(it) },
                            onOpen = { item -> if (!openMedia(context, item, vm.mediaFile(item))) vm.notify(R.string.media_no_viewer_app) },
                            onShare = { item -> shareMedia(context, item, vm.mediaFile(item)) },
                            onDelete = { pendingMediaDelete = it },
                        )
                    }
                    item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
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
                            building = building[scan.id],
                            onRebuild = { vm.rebuild(scan) },
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
            initialPin = current.pin,
            onDismiss = { editing = false },
            onConfirm = { name, location, notes, pin ->
                editing = false
                vm.updateDetails(name, location, notes, pin)
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

    if (briefing) {
        ScanBriefingDialog(
            onStart = { dontShowAgain ->
                if (dontShowAgain) ScanGuidePrefs.setBriefingWanted(context, false)
                briefing = false
                startScan()
            },
            onDismiss = { briefing = false },
        )
    }

    pendingMediaDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingMediaDelete = null },
            title = {
                Text(stringResource(if (item.kind == MediaKind.VIDEO) R.string.media_delete_video_title else R.string.media_delete_photo_title))
            },
            text = { Text(stringResource(R.string.media_delete_message, item.fileName)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeMedia(item)
                    pendingMediaDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { pendingMediaDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
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

/** Keeps the capture in progress across rotation and the process being killed behind the camera app. */
private val PendingMediaSaver = listSaver<MediaItem?, Any>(
    save = { item -> item?.let { listOf(it.id, it.fileName, it.kind.name, it.createdAt) } ?: emptyList() },
    restore = { v -> if (v.isEmpty()) null else MediaItem(v[0] as String, v[1] as String, MediaKind.valueOf(v[2] as String), v[3] as Long) },
)

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
        project.pin?.let { pin ->
            val context = LocalContext.current
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Place, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                Text(pin.coordinates, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { openInMaps(context, pin, project.name) }) { Text(stringResource(R.string.pin_open_map)) }
            }
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
    building: Float?,
    onRebuild: () -> Unit,
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
                    if (scan.hasMesh) {
                        Text(
                            stringResource(
                                if (scan.meshAtlas != null) R.string.scan_model_textured else R.string.scan_model_summary,
                                formatCount(scan.meshTriangles),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
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

            if (building != null) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.scan_building_model, (building * 100).roundToInt()), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { building }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            } else if (scan.pointCount == 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(if (scan.hasCapture) R.string.scan_no_model_yet else R.string.scan_no_points),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (scan.hasCapture) {
                Text(
                    stringResource(R.string.scan_recording_summary, scan.captureFrames),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                if (scan.hasCapture) {
                    OutlinedButton(onClick = onRebuild, enabled = building == null) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_generate_model))
                    }
                }
                Box {
                    OutlinedButton(onClick = { menuOpen = true }, enabled = !exporting) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_export))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        ExportFormat.entries.filter { (!it.needsMesh || scan.hasMesh) && (!it.needsCapture || scan.hasCapture) }.forEach { format ->
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
