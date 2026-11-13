package dev.videoeditor.app.security

import android.content.Context
import dev.videoeditor.core.ai.spend.SpendLedgerCodec
import dev.videoeditor.core.ai.spend.SpendLedgerState
import dev.videoeditor.core.ai.spend.SpendLedgerStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * [SpendLedgerStore] in the private prefs file `spend_ledger.xml` (excluded from backup so a restore cannot
 * reset the caps). Writes use `commit()`; a failed write throws so the guard fails closed.
 */
class SharedPreferencesSpendLedgerStore(
    private val prefs: Prefs,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SpendLedgerStore {
    override suspend fun load(): SpendLedgerState? =
        withContext(io) { prefs.getString(KEY_LEDGER)?.let(SpendLedgerCodec::decode) }

    override suspend fun save(state: SpendLedgerState) {
        val ok = withContext(io) { prefs.commit(puts = mapOf(KEY_LEDGER to SpendLedgerCodec.encode(state))) }
        if (!ok) throw IOException("spend ledger write failed")
    }

    companion object {
        const val PREFS_FILE = "spend_ledger"
        const val KEY_LEDGER = "ledger"

        fun create(context: Context): SharedPreferencesSpendLedgerStore =
            SharedPreferencesSpendLedgerStore(SharedPreferencesPrefs.open(context.applicationContext, PREFS_FILE))
    }
}
