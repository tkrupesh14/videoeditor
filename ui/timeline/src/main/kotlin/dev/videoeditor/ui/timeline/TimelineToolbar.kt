package dev.videoeditor.ui.timeline

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private const val BUTTON_ZOOM_STEP = 1.5f

/**
 * Buttons for the timeline actions that also have gestures (so everything works without pinch or a keyboard):
 * zoom out/in, split at playhead, delete selection, snapping toggle. Stateless; emits [TimelineEvent]s.
 * Scrolls horizontally if the window is narrower than the row.
 */
@Composable
fun TimelineToolbar(
    state: TimelineUiState,
    onEvent: (TimelineEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val split = TimelineEdits.splitAtPlayhead(state.project, state.selectedClipIds, state.playheadUs)
    val delete = TimelineEdits.deleteSelection(state.selectedClipIds)

    fun zoom(factor: Float) {
        val z = TimelineZoom.clampZoom(state.dpPerSecond * factor)
        onEvent(TimelineEvent.ViewportChanged(z, state.scrollUs))
    }
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TextButton(
            onClick = { zoom(1f / BUTTON_ZOOM_STEP) },
            modifier = Modifier.semantics { contentDescription = "Zoom out" },
        ) { Text("−") }
        TextButton(
            onClick = { zoom(BUTTON_ZOOM_STEP) },
            modifier = Modifier.semantics { contentDescription = "Zoom in" },
        ) { Text("+") }
        TextButton(onClick = { split?.let { onEvent(TimelineEvent.Execute(it)) } }, enabled = split != null) {
            Text("Split")
        }
        TextButton(onClick = { delete?.let { onEvent(TimelineEvent.Execute(it)) } }, enabled = delete != null) {
            Text("Delete")
        }
        FilterChip(
            selected = state.snappingEnabled,
            onClick = { onEvent(TimelineEvent.SetSnapping(!state.snappingEnabled)) },
            label = { Text("Snap") },
        )
    }
}
