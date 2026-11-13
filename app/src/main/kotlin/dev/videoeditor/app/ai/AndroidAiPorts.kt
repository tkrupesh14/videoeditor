package dev.videoeditor.app.ai

import android.content.Context
import dev.videoeditor.app.security.AndroidKeystoreApiKeyStore
import dev.videoeditor.app.security.SharedPreferencesPrefs
import dev.videoeditor.app.security.SharedPreferencesSpendLedgerStore
import dev.videoeditor.core.ai.key.ApiKeyStore
import dev.videoeditor.core.ai.spend.LedgerSpendGuard
import dev.videoeditor.core.ai.spend.SpendGuard
import dev.videoeditor.core.media.ThumbnailProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Production [AiPorts]: Keystore-backed key store, persisted spend ledger, on-device media analysis. */
class AndroidAiPorts(
    context: Context,
) : AiPorts {
    private val app = context.applicationContext

    override val keyStore: ApiKeyStore = AndroidKeystoreApiKeyStore.create(app)
    override val models: List<String> = AiPorts.PRICED_MODELS
    override val preferences: AiPreferences =
        PrefsAiPreferences(SharedPreferencesPrefs.open(app, PrefsAiPreferences.PREFS_FILE), models)
    override val analyzer: MediaAnalysisPort = ProjectAnalyzer(AndroidMediaDecoder(app, ThumbnailProvider(app)))

    private val mutex = Mutex()
    private var guard: SpendGuard? = null

    override suspend fun spendGuard(): SpendGuard =
        mutex.withLock {
            guard
                ?: LedgerSpendGuard.open(SharedPreferencesSpendLedgerStore.create(app)).also { guard = it }
        }

    override fun assistant(
        spendGuard: SpendGuard,
        model: String,
    ): EditAssistant = AiPorts.claudeAssistant(keyStore, spendGuard, model)
}
