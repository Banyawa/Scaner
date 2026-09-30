package com.banyawa.sitescanner.ui.guide

import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.banyawa.sitescanner.R
import kotlinx.coroutines.launch

/** One page of the walkthrough: a large icon, a title and a few short lines. */
private class IntroPage(
    val icon: ImageVector,
    @StringRes val title: Int,
    @ArrayRes val lines: Int,
    val marker: LineMarker = LineMarker.DOT,
)

private val IntroPages = listOf(
    IntroPage(Icons.Filled.ViewInAr, R.string.intro_1_title, R.array.intro_1_lines),
    IntroPage(Icons.Filled.PhoneAndroid, R.string.intro_2_title, R.array.intro_2_lines),
    IntroPage(Icons.Filled.DocumentScanner, R.string.intro_3_title, R.array.intro_3_lines, LineMarker.NUMBER),
    IntroPage(Icons.Filled.DirectionsWalk, R.string.intro_4_title, R.array.intro_4_lines),
    IntroPage(Icons.Filled.Architecture, R.string.intro_5_title, R.array.intro_5_lines),
    IntroPage(Icons.Filled.Share, R.string.intro_6_title, R.array.intro_6_lines),
)

/**
 * Tutor-style walkthrough shown before the project list on first launch, and again from
 * the user guide. [onDone] is called when the last page is finished or the intro is skipped.
 */
@Composable
fun IntroScreen(onDone: () -> Unit) {
    val pages = IntroPages
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val current = pagerState.currentPage
    val last = current == pages.size - 1

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDone) { Text(stringResource(R.string.intro_skip)) }
            }
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                IntroPageView(pages[page])
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pages.indices.forEach { i ->
                    val selected = i == current
                    Box(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (selected) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { scope.launch { pagerState.animateScrollToPage(current - 1) } },
                    enabled = current > 0,
                ) { Text(stringResource(R.string.action_back)) }
                Button(onClick = {
                    if (last) onDone() else scope.launch { pagerState.animateScrollToPage(current + 1) }
                }) {
                    Text(stringResource(if (last) R.string.intro_get_started else R.string.intro_next))
                }
            }
        }
    }
}

@Composable
private fun IntroPageView(page: IntroPage) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(page.icon, contentDescription = null, modifier = Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text(stringResource(page.title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            stringArrayResource(page.lines).forEachIndexed { i, line -> GuideLine(page.marker, i, line) }
        }
    }
}
