package dev.videoeditor.ui.shell

import androidx.compose.runtime.Immutable

/** The exact disclosure sentence (security.md A4). Kept here as well so :ui:shell stays free of :core:ai. */
const val AI_CREDITS_NOTE = "Uses your Anthropic API credits, not your Claude subscription."

/** What the AI panel shows. Stateless: the instruction text lives in the panel, everything else comes from here. */
@Immutable
data class AiPanelState(
    /** True when an API key is saved; otherwise the panel points to settings. */
    val hasKey: Boolean = false,
    /** True before the first request of a project: the panel's empty state shows [AI_CREDITS_NOTE]. */
    val showCreditsNote: Boolean = true,
    /** "This session: about $0.18 of $1.00". */
    val sessionCostLine: String = "",
    /** Explains what Claude can and cannot see (e.g. transcript unavailable). */
    val analysisNote: String? = null,
    val phase: AiPhase = AiPhase.Idle,
    /** Non-null while the consent dialog should be shown. */
    val consent: AiConsentText? = null,
    /** True when the last stop was the spend cap: offers "New session" and "Raise limit". */
    val capReached: Boolean = false,
)

@Immutable
sealed interface AiPhase {
    data object Idle : AiPhase

    /** Analysis or the request is running; Cancel is offered. */
    data class Working(
        val label: String,
        val fraction: Float? = null,
    ) : AiPhase

    /** A suggestion waiting for Accept / Reject. The timeline is read-only meanwhile. */
    data class Pending(
        val title: String,
        val summary: String,
        val edits: List<String>,
        /** "This request: about $0.04" or "Offline: no API cost". */
        val costLine: String,
        /** Shown above the actions, e.g. "Spend limit reached: this is a partial suggestion." */
        val note: String? = null,
    ) : AiPhase

    /** Refused, cap reached without edits, or failed. Never contains the API key. */
    data class Message(
        val title: String,
        val text: String,
        val isError: Boolean,
    ) : AiPhase
}

@Immutable
data class AiConsentText(
    val title: String,
    val body: String,
)

class AiPanelActions(
    val onSend: (String) -> Unit,
    val onCancel: () -> Unit,
    val onAccept: () -> Unit,
    val onReject: () -> Unit,
    val onRemoveSilences: () -> Unit,
    val onDismissMessage: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onNewSession: () -> Unit,
    val onConsent: (Boolean) -> Unit,
)

/** AI settings. Holds no key material: only the saved key's masked hint. */
@Immutable
data class AiSettingsState(
    /** "sk-ant-…ab12" when a key is saved. */
    val keyHint: String? = null,
    /** "No key saved", "Saved key sk-ant-…ab12", or why the key is unusable. */
    val keyStatus: String = "No key saved",
    /** Result of the last save attempt: a validation problem or a storage failure. */
    val keyError: String? = null,
    /** "Key saved. Clipboard cleared." etc. */
    val keyNotice: String? = null,
    val models: List<String> = emptyList(),
    val model: String = "",
    val shareFrames: Boolean = false,
    /** Current caps in dollars, e.g. "1.00"; [rollingCapUsd] null means off. */
    val sessionCapUsd: String = "1.00",
    val rollingCapUsd: String? = "20.00",
    val capError: String? = null,
    val sessionSpentLine: String = "",
    val rollingSpentLine: String = "",
)

class AiSettingsActions(
    /** Live format check of the typed text; returns the user message or null when it looks valid. */
    val validateKey: (CharSequence) -> String?,
    /** Saves the key. The caller copies it into a SecretString immediately; the field is cleared after. */
    val onSaveKey: (CharSequence) -> Unit,
    val onRemoveKey: () -> Unit,
    /** One user-initiated clipboard read, or null when nothing is there. */
    val onPaste: () -> CharSequence?,
    val onModelSelected: (String) -> Unit,
    val onShareFramesChanged: (Boolean) -> Unit,
    /** Dollars as typed; [rollingUsd] null turns the rolling cap off. */
    val onSaveCaps: (sessionUsd: String, rollingUsd: String?) -> Unit,
    val onNewSession: () -> Unit,
)
