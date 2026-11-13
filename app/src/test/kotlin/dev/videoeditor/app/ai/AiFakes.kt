package dev.videoeditor.app.ai

import dev.videoeditor.core.ai.assistant.AiResult
import dev.videoeditor.core.ai.assistant.AiSuggestion
import dev.videoeditor.core.ai.assistant.AnalysisBundle
import dev.videoeditor.core.ai.assistant.AssistantConfig
import dev.videoeditor.core.ai.key.InMemoryApiKeyStore
import dev.videoeditor.core.ai.spend.InMemorySpendLedgerStore
import dev.videoeditor.core.ai.spend.LedgerSpendGuard
import dev.videoeditor.core.ai.spend.MicroUsd
import dev.videoeditor.core.ai.spend.SpendGuard
import dev.videoeditor.core.ai.spend.Usage
import dev.videoeditor.timeline.command.Command
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.model.TimeRange
import dev.videoeditor.timeline.session.EditPreview
import dev.videoeditor.timeline.session.EditorSession

class FakeAiPreferences(
    override var model: String = AssistantConfig.DEFAULT_MODEL,
    override var shareFrames: Boolean = false,
    override var consentVersion: Int = AiEditController.CONSENT_VERSION,
) : AiPreferences

class FakeAnalyzer(
    var silences: List<TimeRange> = emptyList(),
) : MediaAnalysisPort {
    var analyzeCalls = 0

    override suspend fun analyze(
        project: Project,
        includeKeyframes: Boolean,
        onProgress: (Float) -> Unit,
    ): AnalysisBundle {
        analyzeCalls++
        return AnalysisBundle(transcriptUnavailableReason = "stub", silences = silences)
    }

    override suspend fun silences(
        project: Project,
        onProgress: (Float) -> Unit,
    ): List<TimeRange> = silences
}

/** [respond] runs on the session the ViewModel passes in; it may suspend (e.g. to test Cancel). */
class FakeAiPorts(
    var respond: suspend (EditorSession) -> AiResult = { AiResult.Suggested(suggestion(it.newPreview())) },
) : AiPorts {
    override val keyStore = InMemoryApiKeyStore()
    override val preferences = FakeAiPreferences()
    override val analyzer = FakeAnalyzer()
    override val models = AiPorts.PRICED_MODELS
    val instructions = mutableListOf<String>()
    private var guard: SpendGuard? = null

    override suspend fun spendGuard(): SpendGuard =
        guard ?: LedgerSpendGuard.open(InMemorySpendLedgerStore()).also { guard = it }

    override fun assistant(
        spendGuard: SpendGuard,
        model: String,
    ): EditAssistant =
        EditAssistant { session, instruction, _ ->
            instructions += instruction
            respond(session)
        }

    companion object {
        val COST: MicroUsd = MicroUsd.ofCents(4)

        fun suggestion(
            preview: EditPreview,
            vararg commands: Command,
            complete: Boolean = true,
        ): AiSuggestion {
            commands.forEach { preview.submit(it) }
            return AiSuggestion(
                preview = preview,
                summary = "Did ${commands.size} things",
                editSummaries = commands.map { it::class.simpleName ?: "edit" },
                toolErrors = emptyList(),
                usage = Usage(1_000, 200),
                cost = COST,
                complete = complete,
                stopReason = "end_turn",
                rounds = 1,
            )
        }
    }
}

/** Obviously fake key material, assembled so no source line holds a key-shaped literal. */
object FakeKeys {
    fun console(): String = "sk-" + "ant-" + "api03-FAKEaiTEST" + "Q".repeat(60)

    fun subscription(): String = "sk-" + "ant-" + "oat01-FAKE" + "Q".repeat(60)

    fun admin(): String = "sk-" + "ant-" + "admin01-FAKE" + "Q".repeat(60)
}
