package com.banyawa.sitescanner.ui.guide

import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.banyawa.sitescanner.R

/** How each line of a list is marked. */
internal enum class LineMarker { DOT, NUMBER, CHECK, CROSS }

private sealed class GuideBlock {
    class Paragraph(@StringRes val text: Int) : GuideBlock()
    class Heading(@StringRes val text: Int) : GuideBlock()
    class Lines(@ArrayRes val items: Int, val marker: LineMarker = LineMarker.DOT) : GuideBlock()

    /** Two arrays of the same length: a bold name and the explanation under it. */
    class Pairs(@ArrayRes val names: Int, @ArrayRes val bodies: Int) : GuideBlock()
}

private class GuideSection(val icon: ImageVector, @StringRes val title: Int, val blocks: List<GuideBlock>)

private val Sections = listOf(
    GuideSection(
        Icons.Filled.PlayArrow, R.string.guide_start_title,
        listOf(
            GuideBlock.Paragraph(R.string.guide_start_text),
            GuideBlock.Lines(R.array.guide_start_steps, LineMarker.NUMBER),
        ),
    ),
    GuideSection(
        Icons.Filled.DirectionsWalk, R.string.guide_scan_title,
        listOf(
            GuideBlock.Paragraph(R.string.guide_scan_text),
            GuideBlock.Lines(R.array.guide_scan_steps, LineMarker.NUMBER),
            GuideBlock.Heading(R.string.guide_do),
            GuideBlock.Lines(R.array.guide_scan_do, LineMarker.CHECK),
            GuideBlock.Heading(R.string.guide_dont),
            GuideBlock.Lines(R.array.guide_scan_dont, LineMarker.CROSS),
        ),
    ),
    GuideSection(Icons.Filled.ViewInAr, R.string.guide_model_title, listOf(GuideBlock.Lines(R.array.guide_model_lines))),
    GuideSection(Icons.Filled.Architecture, R.string.guide_plan_title, listOf(GuideBlock.Lines(R.array.guide_plan_lines))),
    GuideSection(Icons.Filled.Straighten, R.string.guide_measure_title, listOf(GuideBlock.Lines(R.array.guide_measure_lines))),
    GuideSection(
        Icons.Filled.Share, R.string.guide_export_title,
        listOf(
            GuideBlock.Paragraph(R.string.guide_export_text),
            GuideBlock.Pairs(R.array.guide_export_names, R.array.guide_export_uses),
        ),
    ),
    GuideSection(Icons.Filled.PhotoCamera, R.string.guide_media_title, listOf(GuideBlock.Lines(R.array.guide_media_lines))),
    GuideSection(
        Icons.Filled.Build, R.string.guide_trouble_title,
        listOf(GuideBlock.Pairs(R.array.guide_trouble_problems, R.array.guide_trouble_fixes)),
    ),
)

/**
 * The in-app user guide: one expandable card per topic, a button to replay the intro,
 * and the app version at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(onBack: () -> Unit, onReplayIntro: () -> Unit) {
    val context = LocalContext.current
    val version = remember { appVersion(context) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.guide_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.guide_lead),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Sections.forEach { section -> key(section.title) { SectionCard(section) } }
            OutlinedButton(onClick = onReplayIntro, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Icon(Icons.Filled.School, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.guide_show_intro))
            }
            if (version != null) {
                Text(
                    stringResource(R.string.guide_version, version),
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(section: GuideSection) {
    var open by rememberSaveable { mutableStateOf(false) }
    val chevron by animateFloatAsState(targetValue = if (open) 180f else 0f, label = "chevron")
    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(section.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(section.title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.rotate(chevron))
            }
            AnimatedVisibility(visible = open) {
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    section.blocks.forEach { BlockView(it) }
                }
            }
        }
    }
}

@Composable
private fun BlockView(block: GuideBlock) {
    when (block) {
        is GuideBlock.Paragraph -> Text(stringResource(block.text), style = MaterialTheme.typography.bodyMedium)
        is GuideBlock.Heading -> Text(
            stringResource(block.text),
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.titleSmall,
        )
        is GuideBlock.Lines -> stringArrayResource(block.items).forEachIndexed { i, line -> GuideLine(block.marker, i, line) }
        is GuideBlock.Pairs -> {
            val bodies = stringArrayResource(block.bodies)
            stringArrayResource(block.names).forEachIndexed { i, name ->
                Column(Modifier.padding(top = 4.dp)) {
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    bodies.getOrNull(i)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** One line of a list: a marker (bullet, number, check or cross) and its text. Shared with the intro. */
@Composable
internal fun GuideLine(marker: LineMarker, index: Int, text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(28.dp).padding(top = 1.dp), contentAlignment = Alignment.TopStart) {
            when (marker) {
                LineMarker.DOT -> Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                LineMarker.NUMBER -> Text(
                    "${index + 1}.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                LineMarker.CHECK -> Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                LineMarker.CROSS -> Icon(
                    Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

/** The installed version (0.1.<build>), or null when the package cannot be read. */
private fun appVersion(context: Context): String? = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
} catch (e: PackageManager.NameNotFoundException) {
    null
}
