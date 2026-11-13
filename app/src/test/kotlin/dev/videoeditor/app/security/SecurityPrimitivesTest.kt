package dev.videoeditor.app.security

import dev.videoeditor.core.ai.spend.SpendLedgerCodec
import dev.videoeditor.core.ai.spend.SpendLedgerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.security.KeyStoreException
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.xml.parsers.DocumentBuilderFactory

class SecurityPrimitivesTest {
    @Test
    fun recordRejectsMalformedInput() {
        for (bad in listOf("not base64 !!", "", "AQID")) {
            try {
                ApiKeyRecord.decode(bad)
                fail("accepted $bad")
            } catch (e: CorruptRecordException) {
                // expected
            }
        }
        val sealed = ApiKeyCipher.Sealed(ByteArray(12) { 7 }, ByteArray(20) { 9 })
        val back = ApiKeyRecord.decode(ApiKeyRecord.encode(sealed))
        assertTrue(back.iv.contentEquals(sealed.iv))
        assertTrue(back.ciphertextAndTag.contentEquals(sealed.ciphertextAndTag))
    }

    @Test
    fun failureClassification() {
        assertEquals(KeyFailure.Invalidated, classifyKeyFailure(AEADBadTagException()))
        assertEquals(KeyFailure.Invalidated, classifyKeyFailure(ProviderException(KeyStoreException("x"))))
        assertEquals(KeyFailure.Transient, classifyKeyFailure(ProviderException("busy")))
        assertEquals(KeyFailure.Transient, classifyKeyFailure(IllegalStateException()))
    }

    @Test
    fun spendLedgerStoreRoundTripsAndFailsClosed() =
        runBlocking {
            val prefs = FakePrefs()
            val store = SharedPreferencesSpendLedgerStore(prefs, Dispatchers.Unconfined)
            assertNull(store.load())
            val state =
                SpendLedgerState(
                    sessionCapMicros = 1_000_000,
                    rollingCapMicros = null,
                    sessionId = "s1",
                    sessionStartedAtMs = 1,
                    sessionLastActivityMs = 2,
                    sessionSpentMicros = 3,
                )
            store.save(state)
            assertEquals(state, store.load())
            assertEquals(SpendLedgerCodec.encode(state), prefs.getString(SharedPreferencesSpendLedgerStore.KEY_LEDGER))

            prefs.values[SharedPreferencesSpendLedgerStore.KEY_LEDGER] = "{corrupt"
            assertNull(store.load())

            prefs.failCommits = true
            try {
                store.save(state)
                fail("failed write must throw")
            } catch (e: IOException) {
                // expected: the guard refuses to reserve
            }
        }

    @Test
    fun backupRulesExcludeSecurityFilesEverywhere() {
        val files =
            setOf(
                AndroidKeystoreApiKeyStore.PREFS_FILE + ".xml",
                "bridge_clients.xml",
                SharedPreferencesSpendLedgerStore.PREFS_FILE + ".xml",
            )
        val extraction = parse("src/main/res/xml/data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val el = extraction.getElementsByTagName(section).item(0) as Element
            assertEquals(section, files, excludes(el))
        }
        assertEquals(files, excludes(parse("src/main/res/xml/backup_rules.xml")))

        val app = parse("src/main/AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        assertEquals("false", app.getAttribute("android:allowBackup"))
        assertEquals("@xml/data_extraction_rules", app.getAttribute("android:dataExtractionRules"))
        assertEquals("@xml/backup_rules", app.getAttribute("android:fullBackupContent"))
    }

    private fun parse(path: String): Element =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(File(path))
            .documentElement

    private fun excludes(el: Element): Set<String> {
        val nodes = el.getElementsByTagName("exclude")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .onEach { assertEquals("sharedpref", it.getAttribute("domain")) }
            .map { it.getAttribute("path") }
            .toSet()
    }
}
