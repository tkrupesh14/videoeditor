package dev.videoeditor.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Export progress with Cancel; shows the result when finished. */
@Composable
fun ExportDialog(
    state: ExportDialogState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val running = state is ExportDialogState.Running
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = {
            Text(
                when (state) {
                    is ExportDialogState.Running -> "Exporting…"
                    is ExportDialogState.Done -> "Export finished"
                    is ExportDialogState.Failed -> "Export failed"
                    ExportDialogState.Cancelled -> "Export cancelled"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    is ExportDialogState.Running -> {
                        val f = state.fraction
                        if (f == null) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(progress = { f.toFloat() }, modifier = Modifier.fillMaxWidth())
                            Text("${(f * 100).roundToInt()}%")
                        }
                    }
                    is ExportDialogState.Done -> Text("Saved to ${state.outputPath}")
                    is ExportDialogState.Failed -> Text(state.message)
                    ExportDialogState.Cancelled -> Text("The partial file was removed.")
                }
            }
        },
        confirmButton = {
            if (running) {
                TextButton(onClick = onCancel) { Text("Cancel") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

/** Placeholder settings screen until the AI settings land (Phase 5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) {
                        Text("←")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "AI settings arrive in the next build",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
