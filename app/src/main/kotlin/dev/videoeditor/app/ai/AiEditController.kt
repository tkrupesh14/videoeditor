package dev.videoeditor.app.ai

import dev.videoeditor.core.ai.analysis.SilenceCuts
import dev.videoeditor.core.ai.assistant.AiError
import dev.videoeditor.core.ai.assistant.AiResult
import dev.videoeditor.core.ai.assistant.AiSuggestion
import dev.videoeditor.core.ai.key.API_CREDITS_DISCLOSURE
import dev.videoeditor.core.ai.key.API_KEY_INVALIDATED_MESSAGE
import dev.videoeditor.core.ai.key.ApiKeyFormat
import dev.videoeditor.core.ai.key.ApiKeyState
import dev.videoeditor.core.ai.key.SaveResult
import dev.videoeditor.core.ai.key.SecretString
import dev.videoeditor.core.ai.spend.LedgerSpendGuard
import dev.videoeditor.core.ai.spend.MicroUsd
import dev.videoeditor.core.ai.spend.SpendDecision
import dev.videoeditor.core.ai.spend.SpendGuard
import dev.videoeditor.core.ai.spend.SpendSnapshot
import dev.videoeditor.timeline.command.ApplyCuts
import dev.videoeditor.timeline.command.CommandError
import dev.videoeditor.timeline.command.CommandResult
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.session.EditPreview
import dev.videoeditor.timeline.session.EditorSession
import dev.videoeditor.timeline.session.Origin
import dev.videoeditor.ui.shell.AiConsentText
import dev.videoeditor.ui.shell.AiPanelState
import dev.videoeditor.ui.shell.AiPhase
import dev.videoeditor.ui.shell.AiSettingsState
import dev.videoeditor.ui.shell.formatDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode

/** AI state the editor renders. [pendingProject] is the previewed result while a suggestion awaits Accept / Reject. */
data class AiState(
    val panel: AiPanelState = AiPanelState(),
    val settings: AiSettingsState = AiSettingsState(),
    /** True from Send until the result is accepted, rejected or dismissed: the timeline is read-only. */
    val locked: Boolean = false,
    val pendingProject: Project? = null,
    val pendingTitle: String? = null,
    /** Bumped after a successful key save; the screen clears the clipboard when it changes. */
    val clipboardClearRequests: Int = 0,
)

/**
 * The AI half of the editor: Send -> analysis -> [EditAssistant] -> pending [EditPreview] -> Accept (one undo step
 * via [EditorSession.commitPreview]) or Reject; the offline "Remove silences" action; and the AI settings (key,
 * model, caps). Main-thread only, like the session. Holds no key material: keys go straight into [SecretString].
 */
class AiEditController(
    private val session: EditorSession,
    private val scope: CoroutineScope,
    private val ports: AiPorts,
    private val io: CoroutineDispatcher,
    private val onMessage: (String) -> Unit,
    /** Called when the lock or the previewed project changes so the player and timeline can follow. */
    private val onPendingChanged: () -> Unit,
) {
    private val _state =
        MutableStateFlow(
            AiState(
                panel = AiPanelState(analysisNote = ANALYSIS_NOTE),
                settings =
                    AiSettingsState(
                        models = ports.models,
                        model = ports.preferences.model,
                        shareFrames = ports.preferences.shareFrames,
                    ),
            ),
        )
    val state: StateFlow<AiState> = _state.asStateFlow()

    private class Pending(
        val preview: EditPreview,
        val label: String,
        val origin: Origin,
    )

    private var pending: Pending? = null
    private var job: Job? = null
    private var consentInstruction: String? = null
    private var usedThisProject = false

    private val guardMutex = Mutex()
    private var guard: SpendGuard? = null

    init {
        scope.launch { ports.keyStore.state.collect { onKeyState(it) } }
        scope.launch {
            val g = runCatching { spendGuard() }.getOrNull() ?: return@launch
            g.snapshot.collect { onSnapshot(it) }
        }
    }

    val locked: Boolean get() = _state.value.locked

    private suspend fun spendGuard(): SpendGuard =
        guardMutex.withLock { guard ?: ports.spendGuard().also { guard = it } }

    // ---- Panel ----

    fun send(instruction: String) {
        if (locked || instruction.isBlank()) return
        if (_state.value.settings.keyHint == null) {
            showPhase(AiPhase.Message("No API key", "Add your Anthropic API key in Settings first.", isError = true))
            return
        }
        if (ports.preferences.consentVersion < CONSENT_VERSION) {
            consentInstruction = instruction
            _state.update { it.copy(panel = it.panel.copy(consent = CONSENT)) }
            return
        }
        runInstruction(instruction)
    }

    fun onConsent(agree: Boolean) {
        val instruction = consentInstruction
        consentInstruction = null
        _state.update { it.copy(panel = it.panel.copy(consent = null)) }
        if (!agree || instruction == null) return
        ports.preferences.consentVersion = CONSENT_VERSION
        runInstruction(instruction)
    }

    private fun runInstruction(instruction: String) {
        lock(AiPhase.Working("Analyzing your media…", 0f))
        usedThisProject = true
        val project = session.project
        val frames = ports.preferences.shareFrames
        val model = ports.preferences.model
        job =
            scope.launch {
                try {
                    val g = spendGuard()
                    val analysis =
                        withContext(io) {
                            ports.analyzer.analyze(project, frames) { f ->
                                _state.update { s ->
                                    s.copy(panel = s.panel.copy(phase = AiPhase.Working("Analyzing your media…", f)))
                                }
                            }
                        }
                    showPhase(AiPhase.Working("Asking Claude ($model)…"))
                    onResult(ports.assistant(g, model).suggest(session, instruction, analysis), label(instruction))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    unlock(
                        AiPhase.Message(
                            "Something went wrong",
                            "The request could not be prepared. Nothing was changed.",
                            true,
                        ),
                    )
                }
            }
    }

    private fun onResult(
        r: AiResult,
        label: String,
    ) {
        val costLine = "This request: about ${r.cost.toUsdString()}"
        when (r) {
            is AiResult.Suggested -> {
                val s = r.suggestion
                if (!s.hasEdits) {
                    unlock(AiPhase.Message("No changes suggested", s.summary, isError = false))
                } else {
                    val note =
                        when {
                            !s.complete -> "Claude stopped before finishing: this is a partial suggestion."
                            s.toolErrors.isNotEmpty() ->
                                "${s.toolErrors.size} proposed edit(s) were invalid and skipped."
                            else -> null
                        }
                    showPending(s, label, costLine, note)
                }
            }
            is AiResult.Refused ->
                unlock(
                    AiPhase.Message(
                        "Claude declined",
                        r.explanation?.let(::scrub)?.takeIf { it.isNotBlank() }
                            ?: "Claude declined this request. Try rephrasing it.",
                        isError = true,
                    ),
                )
            is AiResult.CapReached -> {
                _state.update { it.copy(panel = it.panel.copy(capReached = true)) }
                val why = capText(r.reason, r.snapshot)
                val partial = r.partial
                if (partial != null && partial.hasEdits) {
                    showPending(partial, label, costLine, "$why This is a partial suggestion.")
                } else {
                    unlock(AiPhase.Message("Spend limit reached", "$why Nothing was changed.", isError = true))
                }
            }
            is AiResult.Failed -> {
                val text = friendly(r.error)
                val partial = r.partial
                if (partial != null && partial.hasEdits) {
                    showPending(partial, label, costLine, "$text This is a partial suggestion.")
                } else {
                    unlock(AiPhase.Message("Request failed", text, isError = true))
                }
            }
        }
    }

    private fun showPending(
        s: AiSuggestion,
        label: String,
        costLine: String,
        note: String?,
    ) = setPending(
        Pending(s.preview, label, Origin.AI),
        AiPhase.Pending("Claude's suggestion", s.summary, s.editSummaries, costLine, note),
    )

    private fun setPending(
        p: Pending,
        phase: AiPhase.Pending,
    ) {
        pending = p
        _state.update {
            it.copy(
                locked = true,
                pendingProject = p.preview.project,
                pendingTitle = phase.title,
                panel = it.panel.copy(phase = phase),
            )
        }
        onPendingChanged()
    }

    fun cancel() {
        val j = job ?: return
        if (_state.value.panel.phase !is AiPhase.Working) return
        j.cancel()
        job = null
        unlock(AiPhase.Message("Cancelled", "Nothing was changed.", isError = false))
    }

    fun accept() {
        val p = pending ?: return
        val r = session.commitPreview(p.preview, p.label, p.origin)
        clearPending()
        if (r is CommandResult.Failure) {
            val text =
                if (r.error is CommandError.StaleRevision) {
                    "The timeline changed since this suggestion was made, so it was discarded."
                } else {
                    r.error.message
                }
            unlock(AiPhase.Message("Could not apply", text, isError = true))
        } else {
            unlock(AiPhase.Idle)
            onMessage("Applied \"${p.label}\". Undo reverts it in one step.")
        }
    }

    fun reject() {
        if (pending == null) return
        clearPending()
        unlock(AiPhase.Idle)
        onMessage("Suggestion discarded")
    }

    fun dismissMessage() {
        if (_state.value.panel.phase is AiPhase.Message) showPhase(AiPhase.Idle)
    }

    /** Offline: detect silences on device and preview cutting them. No API call. */
    fun removeSilences() {
        if (locked) return
        lock(AiPhase.Working("Finding silences…", 0f))
        val project = session.project
        job =
            scope.launch {
                try {
                    val silences =
                        withContext(io) {
                            ports.analyzer.silences(project) { f ->
                                _state.update { s ->
                                    s.copy(panel = s.panel.copy(phase = AiPhase.Working("Finding silences…", f)))
                                }
                            }
                        }
                    val preview = SilenceCuts.preview(session, silences)
                    val cuts = preview.commands.filterIsInstance<ApplyCuts>().flatMap { it.ranges }
                    if (cuts.isEmpty()) {
                        unlock(AiPhase.Message("No silences found", "No silent stretches long enough to cut.", false))
                        return@launch
                    }
                    val total = cuts.sumOf { it.durationUs }
                    setPending(
                        Pending(preview, "Remove silences", Origin.USER),
                        AiPhase.Pending(
                            title = "Remove silences",
                            summary = "Cuts ${cuts.size} silent stretch(es), ${formatDuration(total)} in total.",
                            edits = cuts.map { "Cut ${formatDuration(it.startUs)} to ${formatDuration(it.endUs)}" },
                            costLine = "Runs on this device: no API call, no cost.",
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    unlock(
                        AiPhase.Message(
                            "Could not analyze audio",
                            "The audio could not be decoded on this device.",
                            true,
                        ),
                    )
                }
            }
    }

    fun newSession() {
        scope.launch {
            runCatching { spendGuard().startNewSession() }
                .onSuccess {
                    _state.update { it.copy(panel = it.panel.copy(capReached = false)) }
                    dismissMessage()
                    onMessage("Started a new AI session")
                }.onFailure { onMessage("Could not start a new session") }
        }
    }

    /** The project was replaced: drop any pending suggestion and show the credits note again. */
    fun onProjectReplaced() {
        job?.cancel()
        job = null
        clearPending()
        usedThisProject = false
        unlock(AiPhase.Idle)
    }

    private fun lock(phase: AiPhase) {
        _state.update { it.copy(locked = true, panel = it.panel.copy(phase = phase)) }
        onPendingChanged()
    }

    private fun unlock(phase: AiPhase) {
        _state.update {
            it.copy(locked = false, panel = it.panel.copy(phase = phase, showCreditsNote = !usedThisProject))
        }
        onPendingChanged()
    }

    private fun showPhase(phase: AiPhase) = _state.update { it.copy(panel = it.panel.copy(phase = phase)) }

    private fun clearPending() {
        val had = pending != null
        pending = null
        _state.update { it.copy(pendingProject = null, pendingTitle = null) }
        if (had) onPendingChanged()
    }

    // ---- Settings ----

    fun validateKey(candidate: CharSequence): String? = ApiKeyFormat.check(candidate)?.userMessage

    /** Copies [candidate] into a [SecretString] at once; the caller clears its field. */
    fun saveKey(candidate: CharSequence) {
        val secret = SecretString.of(candidate)
        scope.launch {
            val r =
                try {
                    ports.keyStore.save(secret)
                } finally {
                    secret.close()
                }
            when (r) {
                SaveResult.Saved ->
                    _state.update {
                        it.copy(
                            settings = it.settings.copy(keyError = null, keyNotice = "Key saved. Clipboard cleared."),
                            clipboardClearRequests = it.clipboardClearRequests + 1,
                        )
                    }
                is SaveResult.Rejected -> setKeyError(r.reason.userMessage)
                SaveResult.StorageFailed -> setKeyError("The key could not be stored securely on this device.")
            }
        }
    }

    fun removeKey() {
        scope.launch {
            ports.keyStore.clear()
            _state.update { it.copy(settings = it.settings.copy(keyError = null, keyNotice = "Key removed.")) }
        }
    }

    fun selectModel(model: String) {
        if (model !in ports.models) return
        ports.preferences.model = model
        _state.update { it.copy(settings = it.settings.copy(model = model)) }
    }

    fun setShareFrames(on: Boolean) {
        ports.preferences.shareFrames = on
        _state.update { it.copy(settings = it.settings.copy(shareFrames = on)) }
    }

    fun saveCaps(
        sessionUsd: String,
        rollingUsd: String?,
    ) {
        val sessionCap = parseUsd(sessionUsd)
        val rollingCap = rollingUsd?.let(::parseUsd)
        val error =
            when {
                sessionCap == null || sessionCap !in LedgerSpendGuard.SESSION_CAP_RANGE ->
                    "Per-session cap must be between \$0.10 and \$50."
                rollingUsd != null && (rollingCap == null || rollingCap !in LedgerSpendGuard.ROLLING_CAP_RANGE) ->
                    "30-day cap must be between \$1 and \$500, or turned off."
                else -> null
            }
        if (error != null) {
            _state.update { it.copy(settings = it.settings.copy(capError = error)) }
            return
        }
        scope.launch {
            runCatching { spendGuard().setCaps(sessionCap!!, rollingCap) }
                .onSuccess {
                    _state.update { it.copy(settings = it.settings.copy(capError = null)) }
                    onMessage("Spend limits saved")
                }.onFailure {
                    _state.update {
                        it.copy(
                            settings = it.settings.copy(capError = "Could not save the limits."),
                        )
                    }
                }
        }
    }

    private fun setKeyError(text: String) =
        _state.update { it.copy(settings = it.settings.copy(keyError = text, keyNotice = null)) }

    private fun onKeyState(k: ApiKeyState) {
        val (hint, status) =
            when (k) {
                is ApiKeyState.Present -> k.hint to "Saved key ${k.hint}"
                is ApiKeyState.Missing ->
                    null to
                        when (k.reason) {
                            ApiKeyState.Missing.Reason.Invalidated -> API_KEY_INVALIDATED_MESSAGE
                            ApiKeyState.Missing.Reason.Removed -> "No key saved (removed)"
                            ApiKeyState.Missing.Reason.NeverSet -> "No key saved"
                        }
                ApiKeyState.Unavailable -> null to "Secure key storage is not available on this device."
            }
        _state.update {
            it.copy(
                settings = it.settings.copy(keyHint = hint, keyStatus = status),
                panel = it.panel.copy(hasKey = hint != null),
            )
        }
    }

    private fun onSnapshot(s: SpendSnapshot) {
        _state.update {
            it.copy(
                panel = it.panel.copy(sessionCostLine = sessionLine(s)),
                settings =
                    it.settings.copy(
                        sessionCapUsd = plain(s.sessionCap),
                        rollingCapUsd = s.rollingCap?.let(::plain),
                        sessionSpentLine = sessionLine(s),
                        rollingSpentLine =
                            "Last 30 days: about ${s.rollingSpent.toUsdString()}" +
                                (s.rollingCap?.let { " of ${it.toUsdString()}" } ?: " (no 30-day cap)"),
                    ),
            )
        }
    }

    companion object {
        const val CONSENT_VERSION = 1

        const val ANALYSIS_NOTE =
            "Transcript: not available yet (no on-device speech-to-text). Claude sees the timeline, silences and " +
                "scene changes."

        /** security.md A2.5, verbatim. */
        val CONSENT =
            AiConsentText(
                title = "AI editing sends data to Anthropic",
                body =
                    "To suggest edits, this app sends your project's timeline, the transcript of your clips, " +
                        "and the timestamps of silences and scene changes to Anthropic, using your own API key. " +
                        "If you turn on \"Let AI see frames\", a few small still images from your video are sent " +
                        "as well. Your video and audio files never leave this device. Anthropic handles this data " +
                        "under the terms of your Anthropic API account.\n\n$API_CREDITS_DISCLOSURE",
            )

        private val KEY_PATTERN = Regex("sk-ant-[A-Za-z0-9_-]+")

        /** Defence in depth: never show anything key-shaped, whatever an error message contains. */
        fun scrub(text: String): String = KEY_PATTERN.replace(text, "[redacted]")

        fun sessionLine(s: SpendSnapshot): String =
            "This session: about ${s.sessionSpent.toUsdString()} of ${s.sessionCap.toUsdString()}"

        fun label(instruction: String): String {
            val t = instruction.trim().replace(Regex("\\s+"), " ")
            return "AI: " + if (t.length > 40) t.take(39) + "…" else t
        }

        fun parseUsd(text: String): MicroUsd? =
            runCatching {
                val bd = BigDecimal(text.trim().removePrefix("$"))
                if (bd.scale() > 6) null else MicroUsd(bd.movePointRight(6).longValueExact())
            }.getOrNull()

        private fun plain(m: MicroUsd): String =
            BigDecimal
                .valueOf(m.value)
                .movePointLeft(6)
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString()

        fun capText(
            reason: SpendDecision.Denied.Reason,
            s: SpendSnapshot,
        ): String =
            when (reason) {
                SpendDecision.Denied.Reason.SessionCap ->
                    "AI session limit reached (${s.sessionSpent.toUsdString()} used). Edits made so far are kept and can be undone."
                SpendDecision.Denied.Reason.RollingCap ->
                    "30-day AI spend limit reached (${s.rollingSpent.toUsdString()} used)."
                SpendDecision.Denied.Reason.UnknownModel ->
                    "The selected model has no known price, so requests to it are refused. Pick another model in Settings."
                SpendDecision.Denied.Reason.LoopLimit ->
                    "Claude used the maximum number of rounds for one instruction."
            }

        /** User-facing text for an [AiError]; never includes the key or request details. */
        fun friendly(e: AiError): String =
            when (e.kind) {
                AiError.Kind.NoApiKey -> "No API key is saved, or it could not be read. Add it in Settings."
                AiError.Kind.Auth ->
                    "Anthropic rejected the API key. Check it in Settings or create a new one in the Console."
                AiError.Kind.PermissionDenied -> "This API key is not allowed to use the selected model."
                AiError.Kind.RateLimited ->
                    "Rate limited by Anthropic." +
                        (e.retryAfterSeconds?.let { " Try again in $it s." } ?: " Try again shortly.")
                AiError.Kind.Overloaded -> "Anthropic is overloaded right now. Try again in a minute."
                AiError.Kind.Server -> "Anthropic had a server error. Try again."
                AiError.Kind.BadRequest -> "The request was rejected: ${scrub(e.message)}"
                AiError.Kind.Network -> "Could not reach Anthropic. Check your connection."
                AiError.Kind.InvalidResponse -> "Anthropic sent a response that could not be read."
                AiError.Kind.Refusal -> "Claude declined this request."
                AiError.Kind.InvalidToolInput -> "Claude proposed edits the timeline could not apply."
            }
    }
}
