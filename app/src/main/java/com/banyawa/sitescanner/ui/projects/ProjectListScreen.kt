package com.banyawa.sitescanner.ui.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.banyawa.sitescanner.R
import com.banyawa.sitescanner.app
import com.banyawa.sitescanner.core.project.GeoPin
import com.banyawa.sitescanner.core.project.Project
import com.banyawa.sitescanner.core.project.ProjectRepository
import com.banyawa.sitescanner.ui.common.EmptyState
import com.banyawa.sitescanner.ui.common.formatDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProjectListViewModel(private val repository: ProjectRepository) : ViewModel() {
    /** null while loading. */
    private val _projects = MutableStateFlow<List<Project>?>(null)
    val projects: StateFlow<List<Project>?> = _projects.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _projects.value = withContext(Dispatchers.IO) { repository.list() }
        }
    }

    fun create(name: String, location: String, notes: String, pin: GeoPin?, onCreated: (Project) -> Unit) {
        viewModelScope.launch {
            val project = withContext(Dispatchers.IO) { repository.create(name, location, notes, pin) }
            refresh()
            onCreated(project)
        }
    }

    fun delete(project: Project) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.delete(project.id) }
            refresh()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(onOpenProject: (String) -> Unit) {
    val context = LocalContext.current
    val vm: ProjectListViewModel = viewModel { ProjectListViewModel(context.app.repository) }
    val projects by vm.projects.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    var showCreate by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Project?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.project_new)) },
            )
        },
    ) { padding ->
        val list = projects
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> EmptyState(
                icon = Icons.Filled.Apartment,
                title = stringResource(R.string.projects_empty_title),
                body = stringResource(R.string.projects_empty_body),
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(list, key = { it.id }) { project ->
                    ProjectCard(project, onClick = { onOpenProject(project.id) }, onDelete = { pendingDelete = project })
                }
            }
        }
    }

    if (showCreate) {
        ProjectDialog(
            title = stringResource(R.string.project_new),
            autoPin = true,
            onDismiss = { showCreate = false },
            onConfirm = { name, location, notes, pin ->
                showCreate = false
                vm.create(name, location, notes, pin) { onOpenProject(it.id) }
            },
        )
    }

    pendingDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.project_delete_title)) },
            text = { Text(stringResource(R.string.project_delete_message, project.name)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(project)
                    pendingDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun ProjectCard(project: Project, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium)
                (project.location.takeIf { it.isNotBlank() } ?: project.pin?.coordinates)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    listOfNotNull(
                        pluralStringResource(R.plurals.scan_count, project.scans.size, project.scans.size),
                        project.media.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.media_count, it, it) },
                        formatDateTime(project.updatedAt),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
            }
        }
    }
}

/**
 * Create / edit project details. With [autoPin] the site is pinned where the phone is as
 * soon as the dialog opens; the address found there fills the location unless typed over.
 */
@Composable
fun ProjectDialog(
    title: String,
    initialName: String = "",
    initialLocation: String = "",
    initialNotes: String = "",
    initialPin: GeoPin? = null,
    autoPin: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (name: String, location: String, notes: String, pin: GeoPin?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var location by rememberSaveable { mutableStateOf(initialLocation) }
    var notes by rememberSaveable { mutableStateOf(initialNotes) }
    var pin by rememberSaveable(saver = GeoPinStateSaver) { mutableStateOf(initialPin) }
    // The last address filled in for a pin: replaced when the pin moves, kept once edited.
    var foundAddress by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.project_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text(stringResource(R.string.project_location)) },
                    singleLine = true,
                )
                SitePinField(
                    pin = pin,
                    onPinChange = { pin = it },
                    autoLocate = autoPin,
                    onAddress = { address ->
                        if (location.isBlank() || location == foundAddress) {
                            location = address
                            foundAddress = address
                        }
                    },
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.project_notes)) },
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name, location, notes, pin) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
