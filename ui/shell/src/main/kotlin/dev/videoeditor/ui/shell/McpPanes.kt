package dev.videoeditor.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Bridge status for the "Claude connected" chip (docs/design/security.md B6). */
sealed interface McpStatus {
    data object Off : McpStatus

    data object Waiting : McpStatus

    data class Connected(
        val clientNames: List<String>,
    ) : McpStatus
}

@Immutable
data class McpClientUi(
    val clientId: String,
    val name: String,
    val scopes: List<String>,
    val createdAt: String,
    val lastUsed: String?,
)

sealed interface McpPairingUi {
    data object Closed : McpPairingUi

    data class Offering(
        val code: String,
        val expiresAtEpochMs: Long,
        val attemptsLeft: Int,
    ) : McpPairingUi

    data class AwaitingApproval(
        val requestId: String,
        val clientName: String,
    ) : McpPairingUi

    data class LockedOut(
        val untilEpochMs: Long,
    ) : McpPairingUi
}

@Immutable
data class McpExportPromptUi(
    val exportId: String,
    val clientName: String,
    val projectName: String,
)

@Immutable
data class McpUiState(
    val available: Boolean = false,
    val allowed: Boolean = false,
    val status: McpStatus = McpStatus.Off,
    val clients: List<McpClientUi> = emptyList(),
    val pairing: McpPairingUi = McpPairingUi.Closed,
    val exportPrompt: McpExportPromptUi? = null,
    val socketName: String = "dev.videoeditor.app.bridge",
    val port: Int = 47821,
)

class McpActions(
    val onAllowChanged: (Boolean) -> Unit,
    val onStop: () -> Unit,
    val onBeginPairing: () -> Unit,
    val onCancelPairing: () -> Unit,
    val onAnswerPairing: (requestId: String, allow: Boolean, scopes: Set<String>) -> Unit,
    val onRevoke: (clientId: String) -> Unit,
    val onRevokeAll: () -> Unit,
    val onAnswerExport: (exportId: String, allow: Boolean) -> Unit,
)

/** Thin bar under the top bar while MCP control is on: "Claude connected: name" with the Stop kill switch. */
@Composable
fun McpStatusBanner(
    status: McpStatus,
    onStop: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (status == McpStatus.Off) return
    val connected = status is McpStatus.Connected
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth(), color = if (connected) colors.tertiaryContainer else colors.surfaceVariant) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (status) {
                    is McpStatus.Connected -> "Claude connected: ${status.clientNames.joinToString()}"
                    else -> "Waiting for Claude"
                },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpen) { Text("Details") }
            TextButton(onClick = onStop, modifier = Modifier.semantics { contentDescription = "Stop Claude control" }) {
                Text("Stop")
            }
        }
    }
}

/** "Claude Code (MCP)" screen: allow toggle, pairing, connected clients. The host sets FLAG_SECURE. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpScreen(
    state: McpUiState,
    actions: McpActions,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Claude Code (MCP)") },
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
            Text(
                "Let Claude Code on your computer edit this project over USB (adb). Nothing listens on Wi-Fi or " +
                    "the network. Each computer must be paired once and every export asks you first.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Allow MCP control")
                    Text(
                        "Off by default and after every restart. Only while the editor is open.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = state.allowed, onCheckedChange = actions.onAllowChanged, enabled = state.available)
            }
            if (state.status is McpStatus.Connected) {
                OutlinedButton(
                    onClick = actions.onStop,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stop: disconnect Claude") }
            }
            HorizontalDivider()
            PairingSection(state, actions)
            HorizontalDivider()
            Text("Paired clients", style = MaterialTheme.typography.titleMedium)
            if (state.clients.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall)
            state.clients.forEach { c ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${c.scopes.joinToString(", ")} · paired ${c.createdAt}" +
                                (c.lastUsed?.let { " · used $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { actions.onRevoke(c.clientId) }) { Text("Revoke") }
                }
            }
            if (state.clients.size > 1) {
                OutlinedButton(onClick = actions.onRevokeAll, modifier = Modifier.fillMaxWidth()) { Text("Revoke all") }
            }
        }
    }
}

@Composable
private fun PairingSection(
    state: McpUiState,
    actions: McpActions,
) {
    Text("Pair a new client", style = MaterialTheme.typography.titleMedium)
    when (val p = state.pairing) {
        McpPairingUi.Closed ->
            Button(onClick = actions.onBeginPairing, enabled = state.allowed, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.allowed) "Show pairing code" else "Turn on MCP control to pair")
            }
        is McpPairingUi.Offering -> {
            Text("On your computer, run:", style = MaterialTheme.typography.bodyMedium)
            SelectionContainer {
                Text(
                    "adb forward tcp:${state.port} localabstract:${state.socketName}\nvedit-mcp pair",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "and enter this code (valid 2 minutes, ${p.attemptsLeft} tries left):",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                p.code.chunked(4).joinToString(" "),
                style = MaterialTheme.typography.displaySmall,
                fontFamily = FontFamily.Monospace,
                modifier =
                    Modifier.semantics {
                        contentDescription = "Pairing code ${p.code.toList().joinToString(
                            " ",
                        )}"
                    },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.onBeginPairing) { Text("New code") }
                TextButton(onClick = actions.onCancelPairing) { Text("Cancel") }
            }
        }
        is McpPairingUi.AwaitingApproval ->
            Text(
                "Waiting for your answer…",
                style = MaterialTheme.typography.bodyMedium,
            )
        is McpPairingUi.LockedOut ->
            Text(
                "Pairing is locked for 10 minutes after too many wrong codes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
    }
}

/** The on-phone Allow step of pairing (B3 step 3): a correct code alone is not enough. */
@Composable
fun McpApprovalDialog(
    request: McpPairingUi.AwaitingApproval,
    onAnswer: (allow: Boolean, scopes: Set<String>) -> Unit,
) {
    var read by remember(request.requestId) { mutableStateOf(true) }
    var edit by remember(request.requestId) { mutableStateOf(true) }
    var export by remember(request.requestId) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onAnswer(false, emptySet()) },
        title = { Text("Allow '${request.clientName}' to read and edit your projects?") },
        text = {
            Column {
                ScopeRow("Read the project and transcript", read) { read = it }
                ScopeRow("Edit the timeline (undoable)", edit) { edit = it }
                ScopeRow("Ask to export (you confirm each time)", export) { export = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = read || edit || export,
                onClick = {
                    onAnswer(
                        true,
                        buildSet {
                            if (read) add("read")
                            if (edit) add("edit")
                            if (export) add("export")
                        },
                    )
                },
            ) { Text("Allow") }
        },
        dismissButton = { TextButton(onClick = { onAnswer(false, emptySet()) }) { Text("Deny") } },
    )
}

@Composable
private fun ScopeRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Every remote export asks first (B6). There is no "always allow". */
@Composable
fun McpExportConfirmDialog(
    prompt: McpExportPromptUi,
    onAnswer: (allow: Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text("Export requested") },
        text = { Text("'${prompt.clientName}' wants to export '${prompt.projectName}'. Export?") },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("Export") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Don't export") } },
    )
}
