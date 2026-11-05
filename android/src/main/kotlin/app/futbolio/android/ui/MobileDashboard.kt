package app.futbolio.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.futbolio.android.presentation.AndroidUiState
import app.futbolio.android.presentation.TvActions
import app.futbolio.application.presentation.providerTestsBusy
import app.futbolio.domain.provider.SourceConfig

/** Fixed mobile controls and footer; only the source list scrolls. */
@Composable internal fun MobileDashboard(
    state: AndroidUiState,
    actions: TvActions,
    onToggle: () -> Unit,
    onProvider: (SourceConfig) -> Unit,
    onHelp: () -> Unit,
    onSettings: () -> Unit,
    onOpenUpdate: (() -> Unit)? = null,
) {
    val runtime = state.runtime
    val batch = runtime.testBatch
    val sourceScroll = rememberLazyListState()
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { BrandHeader(compact = true) }
                if (onOpenUpdate != null) UpdateAvailableAction(onOpenUpdate)
            }
            RelayHero(runtime, centered = true, onToggle = onToggle)
            if (runtime.error.isNotBlank() || state.configuration.applyError.isNotBlank() || state.message.isNotBlank()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (runtime.error.isNotBlank()) Text(runtime.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (state.configuration.applyError.isNotBlank()) Text(state.configuration.applyError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (state.message.isNotBlank()) Text(state.message, color = quiet, style = MaterialTheme.typography.bodySmall)
                }
            }
            FlowRow(Modifier.fillMaxWidth().padding(top = 44.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SolidIcon(SolidIcon.Sources, Modifier.size(22.dp), lavender)
                    Text("Sources", style = MaterialTheme.typography.titleMedium)
                }
                TvAction(if (batch.running) "Testing ${minOf(batch.completed + 1, batch.total)} / ${batch.total}" else "Test Providers",
                    enabled = runtime.running && !runtime.providerTestsBusy() && state.config.sources.any { it.enabled },
                    modifier = Modifier.testTag("test-all-providers"), icon = SolidIcon.Whistle, compact = true,
                    onClick = actions::testAll)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.config.sources.isEmpty()) {
                    Text("No stream sources configured.", Modifier.padding(vertical = 16.dp), color = quiet)
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(end = 12.dp).testTag("mobile-sources"), state = sourceScroll) {
                        items(state.config.sources, key = { it.id }) { source ->
                            ProviderTile(source, runtime, Modifier.fillMaxWidth()) { onProvider(source) }
                            HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = edge)
                        }
                    }
                    SourcesScrollbar(sourceScroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(3.dp))
                }
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvAction("How to Install", modifier = Modifier.weight(1f).fillMaxHeight(), icon = SolidIcon.Help, compact = true, onClick = onHelp)
                TvAction("Settings", modifier = Modifier.weight(1f).fillMaxHeight(), icon = SolidIcon.Settings, compact = true, onClick = onSettings)
            }
        }
    }
}

/** Always visible when overflowing, including before the first swipe. Follows actual visible rows. */
@Composable private fun SourcesScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (!state.canScrollBackward && !state.canScrollForward) return@Canvas
        val layout = state.layoutInfo
        val first = layout.visibleItemsInfo.firstOrNull() ?: return@Canvas
        val last = layout.visibleItemsInfo.lastOrNull() ?: return@Canvas
        val total = layout.totalItemsCount.toFloat()
        if (total <= 0f) return@Canvas
        val start = first.index + ((layout.viewportStartOffset - first.offset).toFloat() / first.size.coerceAtLeast(1)).coerceIn(0f, 1f)
        val end = last.index + ((layout.viewportEndOffset - last.offset).toFloat() / last.size.coerceAtLeast(1)).coerceIn(0f, 1f)
        val thumbHeight = (size.height * (end - start) / total).coerceIn(minOf(24.dp.toPx(), size.height), size.height)
        val travel = size.height - thumbHeight
        val scrollRange = total - (end - start)
        val top = if (scrollRange > 0f) travel * (start / scrollRange).coerceIn(0f, 1f) else 0f
        val radius = CornerRadius(size.width / 2)
        drawRoundRect(quiet.copy(alpha = .18f), cornerRadius = radius)
        drawRoundRect(lavender, topLeft = Offset(0f, top), size = Size(size.width, thumbHeight), cornerRadius = radius)
    }
}
