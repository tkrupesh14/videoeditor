package dev.videoeditor.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** AI edit panel: instruction, Send, progress with Cancel, and the pending suggestion with Accept / Reject. */
@Composable
fun AiPanel(
    state: AiPanelState,
    actions: AiPanelActions,
    modifier: Modifier = Modifier,
) {
    var instruction by rememberSaveable { mutableStateOf("") }
    val phase = state.phase
    val busy = phase is AiPhase.Working || phase is AiPhase.Pending
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AI edit", style = MaterialTheme.typography.titleMedium)
        if (state.sessionCostLine.isNotEmpty()) {
            Text(state.sessionCostLine, style = MaterialTheme.typography.bodySmall)
        }
        if (!state.hasKey) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Add your Anthropic API key in Settings to ask Claude for edits.")
                    Text(AI_CREDITS_NOTE, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = actions.onOpenSettings) { Text("Open settings") }
                }
            }
        } else if (state.showCreditsNote) {
            Text(AI_CREDITS_NOTE, style = MaterialTheme.typography.bodySmall)
        }
        state.analysisNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        OutlinedTextField(
            value = instruction,
            onValueChange = { instruction = it },
            label = { Text("What should Claude do?") },
            placeholder = { Text("e.g. Tighten the pauses and add a title caption") },
            enabled = !busy,
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { actions.onSend(instruction.trim()) },
                enabled = state.hasKey && !busy && instruction.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Send") }
            OutlinedButton(
                onClick = actions.onRemoveSilences,
                enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Remove silences") }
        }
        Text(
            "Remove silences runs on this device and makes no API call.",
            style = MaterialTheme.typography.bodySmall,
        )
        when (phase) {
            AiPhase.Idle -> Unit
            is AiPhase.Working -> {
                HorizontalDivider()
                Text(phase.label)
                val f = phase.fraction
                if (f == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                }
                OutlinedButton(onClick = actions.onCancel) { Text("Cancel") }
            }
            is AiPhase.Pending -> SuggestionCard(phase, actions)
            is AiPhase.Message -> {
                Card(
                    Modifier.fillMaxWidth(),
                    colors =
                        if (phase.isError) {
                            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        } else {
                            CardDefaults.cardColors()
                        },
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(phase.title, fontWeight = FontWeight.SemiBold)
                        Text(phase.text)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.capReached) {
                                OutlinedButton(onClick = actions.onNewSession) { Text("New session") }
                                OutlinedButton(onClick = actions.onOpenSettings) { Text("Raise limit") }
                            }
                            TextButton(onClick = actions.onDismissMessage) { Text("OK") }
                        }
                    }
                }
            }
        }
    }
    state.consent?.let { c ->
        AlertDialog(
            onDismissRequest = { actions.onConsent(false) },
            title = { Text(c.title) },
            text = { Text(c.body) },
            confirmButton = { TextButton(onClick = { actions.onConsent(true) }) { Text("I agree") } },
            dismissButton = { TextButton(onClick = { actions.onConsent(false) }) { Text("Not now") } },
        )
    }
}

@Composable
private fun SuggestionCard(
    phase: AiPhase.Pending,
    actions: AiPanelActions,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(phase.title, style = MaterialTheme.typography.titleSmall)
            Text(phase.summary)
            if (phase.edits.isNotEmpty()) {
                Text("${phase.edits.size} edit(s):", style = MaterialTheme.typography.labelLarge)
                phase.edits.forEachIndexed { i, e ->
                    Text("${i + 1}. $e", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(phase.costLine, style = MaterialTheme.typography.bodySmall)
            phase.note?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(
                "Previewing the result. The timeline is read-only until you accept or reject.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.onReject, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Reject")
                }
                Button(onClick = actions.onAccept, modifier = Modifier.heightIn(min = 48.dp)) { Text("Accept") }
            }
        }
    }
}

/** Slim bar above the timeline while a suggestion is pending. */
@Composable
fun AiPendingBar(
    title: String,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Preview: $title (read-only)",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onReject) { Text("Reject") }
            TextButton(onClick = onAccept) { Text("Accept") }
        }
    }
}

/**
 * Settings: the Anthropic API key (masked field, cleared after save, never shown again), model, frames toggle and
 * spend caps. The host must set FLAG_SECURE while this is shown. The key field uses `remember`, never
 * `rememberSaveable`, so it never enters the saved-instance Bundle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: AiSettingsState,
    actions: AiSettingsActions,
    onBack: () -> Unit,
) {
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
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Anthropic API key", style = MaterialTheme.typography.titleMedium)
            Text(state.keyStatus, style = MaterialTheme.typography.bodyMedium)
            ApiKeyField(state, actions)
            HorizontalDivider()
            Text("Model", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.selectableGroup()) {
                state.models.forEach { m ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                m == state.model,
                                onClick = { actions.onModelSelected(m) },
                                role = Role.RadioButton,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = m == state.model, onClick = null)
                        Text(m, Modifier.padding(start = 12.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Let AI see frames")
                    Text(
                        "Sends up to 8 small still images per request. Off by default.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = state.shareFrames, onCheckedChange = actions.onShareFramesChanged)
            }
            HorizontalDivider()
            SpendSection(state, actions)
        }
    }
}

@Composable
private fun ApiKeyField(
    state: AiSettingsState,
    actions: AiSettingsActions,
) {
    // remember, not rememberSaveable: the key must never be written to the saved-instance Bundle.
    var key by remember { mutableStateOf("") }
    val problem = if (key.isEmpty()) null else actions.validateKey(key)
    OutlinedTextField(
        value = key,
        onValueChange = { key = it },
        label = { Text(if (state.keyHint == null) "Paste your API key" else "Replace the saved key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        isError = problem != null,
        supportingText = { Text(AI_CREDITS_NOTE) },
        modifier = Modifier.fillMaxWidth(),
    )
    (problem ?: state.keyError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    state.keyNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { actions.onPaste()?.let { key = it.toString() } }) { Text("Paste") }
        Button(
            onClick = {
                actions.onSaveKey(key)
                key = ""
            },
            enabled = key.isNotEmpty() && problem == null,
        ) { Text("Save key") }
        if (state.keyHint != null) {
            OutlinedButton(onClick = actions.onRemoveKey) { Text("Remove") }
        }
    }
    Text(
        "Create a key at console.anthropic.com/settings/keys. The saved key is never shown again; " +
            "to change it, enter a new one.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun SpendSection(
    state: AiSettingsState,
    actions: AiSettingsActions,
) {
    Text("Spend limits", style = MaterialTheme.typography.titleMedium)
    Text(state.sessionSpentLine)
    Text(state.rollingSpentLine)
    var session by remember(state.sessionCapUsd) { mutableStateOf(state.sessionCapUsd) }
    var rollingOn by remember(state.rollingCapUsd) { mutableStateOf(state.rollingCapUsd != null) }
    var rolling by remember(state.rollingCapUsd) { mutableStateOf(state.rollingCapUsd ?: "20.00") }
    OutlinedTextField(
        value = session,
        onValueChange = { session = it },
        label = { Text("Per-session cap (USD, 0.10 to 50)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("30-day cap", Modifier.weight(1f))
        Switch(checked = rollingOn, onCheckedChange = { rollingOn = it })
    }
    if (rollingOn) {
        OutlinedTextField(
            value = rolling,
            onValueChange = { rolling = it },
            label = { Text("Rolling 30-day cap (USD, 1 to 500)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    state.capError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { actions.onSaveCaps(session, if (rollingOn) rolling else null) }) { Text("Save limits") }
        OutlinedButton(onClick = actions.onNewSession) { Text("New session") }
    }
    Text(
        "These limits are an on-device estimate, not an invoice. For a hard limit, use a dedicated key in a " +
            "Console workspace with its own spend limit (console.anthropic.com/settings/limits).",
        style = MaterialTheme.typography.bodySmall,
    )
}
