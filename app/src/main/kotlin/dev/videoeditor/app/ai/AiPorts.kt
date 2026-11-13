package dev.videoeditor.app.ai

import dev.videoeditor.app.security.Prefs
import dev.videoeditor.core.ai.assistant.AiResult
import dev.videoeditor.core.ai.assistant.AnalysisBundle
import dev.videoeditor.core.ai.assistant.AssistantConfig
import dev.videoeditor.core.ai.assistant.ClaudeEditAssistant
import dev.videoeditor.core.ai.key.ApiKeyStore
import dev.videoeditor.core.ai.spend.PriceTable
import dev.videoeditor.core.ai.spend.SpendGuard
import dev.videoeditor.timeline.session.EditorSession

/** The one call the editor makes to Claude. Production: [ClaudeEditAssistant]. */
fun interface EditAssistant {
    suspend fun suggest(
        session: EditorSession,
        instruction: String,
        analysis: AnalysisBundle,
    ): AiResult
}

/** Non-secret AI preferences. */
interface AiPreferences {
    var model: String
    var shareFrames: Boolean
    var consentVersion: Int
}

/** Everything the AI flow needs from the platform; fakes in JVM tests. */
interface AiPorts {
    val keyStore: ApiKeyStore
    val preferences: AiPreferences
    val analyzer: MediaAnalysisPort

    /** Selectable models: the ids in the price table (an unpriced model is refused anyway). */
    val models: List<String>

    /** Opens (once) the persisted spend ledger. */
    suspend fun spendGuard(): SpendGuard

    fun assistant(
        spendGuard: SpendGuard,
        model: String,
    ): EditAssistant

    companion object {
        /** Models in the default price table, default model first. */
        val PRICED_MODELS: List<String> =
            PriceTable.DEFAULT.modelIds.sortedWith(
                compareBy<String> { it != AssistantConfig.DEFAULT_MODEL }.thenBy { it },
            )

        /** Production assistant: requests go only to api.anthropic.com with the user's own Console key. */
        fun claudeAssistant(
            keyStore: ApiKeyStore,
            spendGuard: SpendGuard,
            model: String,
        ): EditAssistant {
            val a = ClaudeEditAssistant(keyStore, spendGuard, AssistantConfig(model = model))
            return EditAssistant { session, instruction, analysis -> a.suggest(session, instruction, analysis) }
        }
    }
}

/** [AiPreferences] over a [Prefs] file (`ai_prefs.xml`). */
class PrefsAiPreferences(
    private val prefs: Prefs,
    private val models: List<String>,
) : AiPreferences {
    override var model: String
        get() = prefs.getString(KEY_MODEL)?.takeIf { it in models } ?: AssistantConfig.DEFAULT_MODEL
        set(value) {
            require(value in models) { "unknown model" }
            prefs.commit(puts = mapOf(KEY_MODEL to value))
        }

    override var shareFrames: Boolean
        get() = prefs.getLong(KEY_FRAMES) == 1L
        set(value) {
            prefs.commit(puts = mapOf(KEY_FRAMES to if (value) 1L else 0L))
        }

    override var consentVersion: Int
        get() = (prefs.getLong(KEY_CONSENT) ?: 0L).toInt()
        set(value) {
            prefs.commit(puts = mapOf(KEY_CONSENT to value.toLong()))
        }

    companion object {
        const val PREFS_FILE = "ai_prefs"
        const val KEY_MODEL = "model"
        const val KEY_FRAMES = "share_frames"
        const val KEY_CONSENT = "consent_version"
    }
}
