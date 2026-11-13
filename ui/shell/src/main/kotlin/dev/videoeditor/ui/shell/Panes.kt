package dev.videoeditor.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Media bin: imported assets with a "missing" badge, an import button and add-to-timeline per item. Stateless. */
@Composable
fun MediaBinPane(
    state: MediaBinState,
    onImport: () -> Unit,
    onAddToTimeline: (assetId: String) -> Unit,
    onOpenSample: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Media", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(onClick = onImport, enabled = !state.importing) { Text("Import") }
        }
        if (state.importing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.items.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "No media yet. Import a video from your device, or open the sample project.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onOpenSample) { Text("Open sample project") }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.items, key = { it.assetId }) { item ->
                MediaRow(item, onAdd = { onAddToTimeline(item.assetId) })
            }
        }
    }
}

@Composable
private fun MediaRow(
    item: MediaItemUi,
    onAdd: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (item.missing) colors.errorContainer else colors.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 12.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val kind =
                    when {
                        item.hasVideo && item.hasAudio -> "Video + audio"
                        item.hasVideo -> "Video"
                        else -> "Audio"
                    }
                Text(
                    if (item.missing) "Media missing on this device" else "$kind · ${formatDuration(item.durationUs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.missing) MaterialTheme.colorScheme.onErrorContainer else Color.Unspecified,
                )
            }
            TextButton(onClick = onAdd, enabled = !item.missing) { Text("Add") }
        }
    }
}

/**
 * Preview area: the [player] slot (a video surface supplied by the app) letterboxed to [aspectRatio], status
 * overlays (empty project, missing media, player error) and transport controls. Stateless.
 */
@Composable
fun PreviewPane(
    state: PreviewPaneState,
    aspectRatio: Float,
    onPlayPause: () -> Unit,
    onSeekToStart: () -> Unit,
    modifier: Modifier = Modifier,
    player: @Composable (Modifier) -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(aspectRatio.coerceIn(0.2f, 5f)), contentAlignment = Alignment.Center) {
                player(Modifier.fillMaxSize())
                if (!state.hasContent) {
                    Text(
                        if (state.missingMediaCount >
                            0
                        ) {
                            "Nothing to preview: the media is missing"
                        } else {
                            "Import a video to start"
                        },
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                if (state.missingMediaCount > 0) {
                    Banner(
                        "${state.missingMediaCount} media file(s) missing on this device. " +
                            "Their clips are skipped in the preview and export is disabled.",
                    )
                }
                state.error?.let { Banner("Preview error: $it") }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onSeekToStart, modifier = Modifier.semantics { contentDescription = "Go to start" }) {
                Text("|◀")
            }
            FilledTonalButton(
                onClick = onPlayPause,
                enabled = state.hasContent,
                modifier = Modifier.semantics { contentDescription = if (state.isPlaying) "Pause" else "Play" },
            ) { Text(if (state.isPlaying) "❚❚" else "▶") }
            Spacer(Modifier.width(12.dp))
            Text(
                "${formatDuration(state.positionUs)} / ${formatDuration(state.durationUs)}",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun Banner(text: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(8.dp),
        )
    }
}

/** Properties of the selected clip(s). Sliders report live values; text is committed with "Apply". Stateless. */
@Composable
fun InspectorPane(
    state: InspectorState,
    actions: InspectorActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Inspector", style = MaterialTheme.typography.titleMedium)
        when (state) {
            InspectorState.Nothing -> Text("Select a clip on the timeline to edit its properties.")
            is InspectorState.Multiple -> {
                Text("${state.count} clips selected. Select a single clip to edit its properties.")
                OutlinedButton(onClick = actions.onDeleteSelection) { Text("Delete ${state.count} clips") }
            }
            is InspectorState.Media -> {
                Text(
                    state.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Starts ${formatDuration(state.startUs)} · length ${formatDuration(state.durationUs)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.missing) Text("Media missing on this device", color = MaterialTheme.colorScheme.error)
                PropertySlider("Speed", state.speed, 0.1..4.0, "×", ClipProperty.SPEED, actions)
                if (state.hasAudio) PropertySlider("Volume", state.volume, 0.0..2.0, "×", ClipProperty.VOLUME, actions)
                if (state.hasVideo) {
                    PropertySlider(
                        "Opacity",
                        state.opacity,
                        0.0..1.0,
                        "",
                        ClipProperty.OPACITY,
                        actions,
                    )
                }
                OutlinedButton(onClick = actions.onDeleteSelection) { Text("Delete clip") }
            }
            is InspectorState.Caption -> {
                Text(
                    "Caption · starts ${formatDuration(state.startUs)} · length ${formatDuration(state.durationUs)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                var text by remember(state.clipId, state.text) { mutableStateOf(state.text) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Caption text") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { actions.onCaptionTextCommit(text) },
                    enabled = text != state.text && text.isNotBlank(),
                ) { Text("Apply text") }
                PropertySlider("Opacity", state.opacity, 0.0..1.0, "", ClipProperty.OPACITY, actions)
                OutlinedButton(onClick = actions.onDeleteSelection) { Text("Delete caption") }
            }
        }
    }
}

@Composable
private fun PropertySlider(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    suffix: String,
    property: ClipProperty,
    actions: InspectorActions,
) {
    Column {
        Text("$label: ${(value * 100).roundToInt() / 100.0}$suffix", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = value.toFloat(),
            onValueChange = { actions.onPropertyChange(property, (it * 100).roundToInt() / 100.0) },
            onValueChangeFinished = actions.onPropertyChangeFinished,
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}
