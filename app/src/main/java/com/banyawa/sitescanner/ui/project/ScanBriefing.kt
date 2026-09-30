package com.banyawa.sitescanner.ui.project

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.banyawa.sitescanner.R

/**
 * The briefing before a scan: how to walk a room so the model comes out well. Shown until
 * the user ticks "don't show again" ([com.banyawa.sitescanner.scan.ScanGuidePrefs]); the
 * scan screen's coach repeats the steps live.
 */
@Composable
fun ScanBriefingDialog(onStart: (dontShowAgain: Boolean) -> Unit, onDismiss: () -> Unit) {
    var dontShow by rememberSaveable { mutableStateOf(false) }
    val tips = stringArrayResource(R.array.scan_briefing_tips)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.DocumentScanner, contentDescription = null) },
        title = { Text(stringResource(R.string.scan_briefing_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tips.forEachIndexed { i, tip ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
                        Text(tip, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { dontShow = !dontShow }) {
                    Checkbox(checked = dontShow, onCheckedChange = { dontShow = it })
                    Text(stringResource(R.string.scan_briefing_dont_show), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { Button(onClick = { onStart(dontShow) }) { Text(stringResource(R.string.scan_briefing_start)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
