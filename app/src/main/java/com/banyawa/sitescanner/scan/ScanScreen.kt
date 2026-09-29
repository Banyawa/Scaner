package com.banyawa.sitescanner.scan

import android.opengl.GLSurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.banyawa.sitescanner.R
import com.banyawa.sitescanner.core.units.LengthFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val Overlay = Color.Black.copy(alpha = 0.55f)
private val Accent = Color(0xFFFFA000)
private val AccentPending = Color(0xFFFFE066)
private val ReticleOn = Color(0xFF4CFF7A)

@Composable
fun ScanScreen(
    state: ScanUiState,
    surfaceView: GLSurfaceView,
    onToggleRecording: () -> Unit,
    onAddPoint: () -> Unit,
    onUndo: () -> Unit,
    onSave: (String) -> Unit,
    onRetry: () -> Unit,
    onExit: () -> Unit,
) {
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    var showExitDialog by rememberSaveable { mutableStateOf(false) }
    val hasData = state.pointCount > 0 || state.measurementCount > 0

    val requestExit: () -> Unit = {
        if (hasData) {
            showExitDialog = true
        } else {
            onExit()
        }
    }
    BackHandler(enabled = !state.saving) { requestExit() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { surfaceView }, modifier = Modifier.fillMaxSize())

        LabelsOverlay(state.labels)
        Crosshair(active = state.reticleValid, modifier = Modifier.align(Alignment.Center))

        TopStatus(
            state = state,
            onBack = requestExit,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(8.dp),
        )

        BottomControls(
            state = state,
            hasData = hasData,
            onToggleRecording = onToggleRecording,
            onAddPoint = onAddPoint,
            onUndo = onUndo,
            onSave = { showSaveDialog = true },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp),
        )

        state.errorRes?.let { res ->
            ErrorOverlay(stringResource(res), state.errorDetail, state.canRetry, onRetry, onExit)
        }
        if (state.saving) SavingOverlay()
    }

    if (showSaveDialog) {
        SaveDialog(
            onConfirm = { name ->
                showSaveDialog = false
                onSave(name)
            },
            onDismiss = { showSaveDialog = false },
        )
    }
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text(stringResource(R.string.scan_discard_title)) },
            text = { Text(stringResource(R.string.scan_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    onExit()
                }) { Text(stringResource(R.string.action_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) { Text(stringResource(R.string.scan_keep_scanning)) }
            },
        )
    }
}

@Composable
private fun LabelsOverlay(labels: List<ScreenLabel>) {
    Box(Modifier.fillMaxSize()) {
        for (label in labels) {
            key(label.id) {
                Text(
                    text = label.text,
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                            layout(0, 0) {
                                placeable.place(
                                    (label.x - placeable.width / 2f).roundToInt(),
                                    (label.y - placeable.height / 2f).roundToInt(),
                                )
                            }
                        }
                        .background(if (label.pending) AccentPending else Accent, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun Crosshair(active: Boolean, modifier: Modifier = Modifier) {
    val color = if (active) ReticleOn else Color.White.copy(alpha = 0.8f)
    Canvas(modifier.size(56.dp)) {
        val r = size.minDimension / 2f
        drawCircle(color, radius = r * 0.55f, style = Stroke(width = 2.dp.toPx()))
        drawCircle(color, radius = 2.5.dp.toPx())
        val gap = r * 0.7f
        val stroke = 2.dp.toPx()
        drawLine(color, Offset(center.x - r, center.y), Offset(center.x - gap, center.y), stroke)
        drawLine(color, Offset(center.x + gap, center.y), Offset(center.x + r, center.y), stroke)
        drawLine(color, Offset(center.x, center.y - r), Offset(center.x, center.y - gap), stroke)
        drawLine(color, Offset(center.x, center.y + gap), Offset(center.x, center.y + r), stroke)
    }
}

@Composable
private fun TopStatus(state: ScanUiState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val status = when {
        !state.arReady -> stringResource(R.string.scan_status_starting)
        state.hint == TrackingHint.NONE ->
            stringResource(if (state.recording) R.string.scan_status_recording else R.string.scan_status_ready)
        state.hint == TrackingHint.MOVE_SLOWLY -> stringResource(R.string.scan_hint_move_slowly)
        state.hint == TrackingHint.MORE_LIGHT -> stringResource(R.string.scan_hint_more_light)
        state.hint == TrackingHint.MORE_TEXTURE -> stringResource(R.string.scan_hint_more_texture)
        state.hint == TrackingHint.CAMERA_UNAVAILABLE -> stringResource(R.string.error_camera_unavailable)
        else -> stringResource(R.string.scan_hint_initializing)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Overlay, RoundedCornerShape(16.dp))
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = Color.White)
        }
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(status, color = Color.White, style = MaterialTheme.typography.titleSmall)
            val mode = stringResource(if (state.depthSupported) R.string.scan_mode_depth else R.string.scan_mode_features)
            val points = NumberFormat.getIntegerInstance().format(state.pointCount)
            Text(
                stringResource(R.string.scan_stats, mode, points, formatDuration(state.elapsedSec)),
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.diagnostics.isNotEmpty()) {
                Text(state.diagnostics, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (state.recording) {
            Box(Modifier.size(12.dp).background(Color.Red, RoundedCornerShape(6.dp)))
        }
    }
}

@Composable
private fun BottomControls(
    state: ScanUiState,
    hasData: Boolean,
    onToggleRecording: () -> Unit,
    onAddPoint: () -> Unit,
    onUndo: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val message = when {
            state.storageFull -> stringResource(R.string.scan_storage_full)
            state.pendingStart && state.liveDistanceM != null ->
                stringResource(R.string.scan_live_distance, LengthFormat.format(state.liveDistanceM))
            state.pendingStart -> stringResource(R.string.scan_aim_second_point)
            !state.depthSupported -> stringResource(R.string.scan_no_depth_warning)
            !state.recording && state.pointCount == 0 && state.tracking -> stringResource(R.string.scan_tip_start)
            else -> null
        }
        if (message != null) {
            Text(
                message,
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .background(Overlay, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Spacer(Modifier.height(12.dp))
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundAction(
                icon = Icons.AutoMirrored.Filled.Undo,
                label = stringResource(R.string.scan_undo),
                enabled = state.pendingStart || state.measurementCount > 0,
                onClick = onUndo,
            )
            RoundAction(
                icon = Icons.Filled.Straighten,
                label = stringResource(if (state.pendingStart) R.string.scan_measure_end else R.string.scan_measure_start),
                enabled = state.tracking && state.reticleValid,
                onClick = onAddPoint,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LargeFloatingActionButton(
                    onClick = { if (state.arReady) onToggleRecording() },
                    containerColor = if (state.recording) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp),
                ) {
                    Icon(
                        if (state.recording) Icons.Filled.Pause else Icons.Filled.FiberManualRecord,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Text(
                    stringResource(if (state.recording) R.string.scan_pause else R.string.scan_record),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            RoundAction(
                icon = Icons.Filled.Check,
                label = stringResource(R.string.action_save),
                enabled = state.arReady && hasData && !state.saving,
                onClick = onSave,
            )
        }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(52.dp)) {
            Icon(icon, contentDescription = label)
        }
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SaveDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val defaultName = stringResource(
        R.string.scan_default_name,
        remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date()) },
    )
    var name by rememberSaveable { mutableStateOf(defaultName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scan_save_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.scan_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.ifBlank { defaultName }) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ErrorOverlay(message: String, detail: String?, canRetry: Boolean, onRetry: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = Accent, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(16.dp))
            Text(message, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(detail, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
                if (canRetry) Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

@Composable
private fun SavingOverlay() {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.scan_saving), color = Color.White)
        }
    }
}

private fun formatDuration(sec: Int) = String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)
