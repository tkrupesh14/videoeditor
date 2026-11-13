package dev.videoeditor.app.mcp

import dev.videoeditor.app.security.ApiKeyCipher
import dev.videoeditor.app.security.Prefs
import dev.videoeditor.core.mcp.bridge.BridgeCrypto
import dev.videoeditor.core.mcp.bridge.BridgeScope
import dev.videoeditor.core.mcp.bridge.ClientStore
import dev.videoeditor.core.mcp.bridge.PairedClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.SecureRandom

/**
 * Paired clients in `bridge_clients.xml` (excluded from backup), docs/design/security.md B4. Per client only
 * `{clientId, name, scopes, createdAt, lastUsedAt, verifierBlob}`; the token is never stored. The verifier is
 * encrypted with a dedicated AndroidKeyStore AES-256-GCM key; the plaintext is bound to the client id
 * (`clientId || 0x00 || verifier`), which is checked on decrypt. If the Keystore key is lost, every client
 * is dropped and must pair again.
 */
class KeystoreClientStore(
    private val prefs: Prefs,
    private val cipher: ApiKeyCipher,
    random: SecureRandom = SecureRandom(),
) : ClientStore {
    private val json = Json { ignoreUnknownKeys = true }

    private class Record(
        val client: PairedClient,
        val iv: String,
        val blob: String,
    )

    override val bridgeId: String =
        prefs.getString(KEY_BRIDGE_ID)
            ?: BridgeCrypto.newBridgeId(random).also { prefs.commit(mapOf(KEY_BRIDGE_ID to it)) }

    @Synchronized
    private fun records(): List<Record> {
        val text = prefs.getString(KEY_CLIENTS) ?: return emptyList()
        return try {
            (json.parseToJsonElement(text) as JsonArray).mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null

                fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                val client =
                    PairedClient(
                        clientId = s("clientId") ?: return@mapNotNull null,
                        name = s("name") ?: "client",
                        scopes =
                            (o["scopes"] as? JsonArray)
                                .orEmpty()
                                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(BridgeScope::fromWire) }
                                .toSet(),
                        createdAtEpochMs = (o["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
                        lastUsedAtEpochMs = (o["lastUsedAt"] as? JsonPrimitive)?.longOrNull,
                    )
                Record(client, s("iv") ?: return@mapNotNull null, s("verifier") ?: return@mapNotNull null)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun save(records: List<Record>) {
        val arr =
            buildJsonArray {
                for (r in records) {
                    add(
                        buildJsonObject {
                            put("clientId", r.client.clientId)
                            put("name", r.client.name)
                            put("scopes", buildJsonArray { r.client.scopes.forEach { add(JsonPrimitive(it.wire)) } })
                            put("createdAt", r.client.createdAtEpochMs)
                            r.client.lastUsedAtEpochMs?.let { put("lastUsedAt", it) }
                            put("iv", r.iv)
                            put("verifier", r.blob)
                        },
                    )
                }
            }
        prefs.commit(mapOf(KEY_CLIENTS to arr.toString()))
    }

    override fun list(): List<PairedClient> = records().map { it.client }

    @Synchronized
    override fun verifier(clientId: String): ByteArray? {
        val r = records().firstOrNull { it.client.clientId == clientId } ?: return null
        val iv = BridgeCrypto.unb64url(r.iv) ?: return null
        val blob = BridgeCrypto.unb64url(r.blob) ?: return null
        val plain =
            try {
                cipher.decrypt(ApiKeyCipher.Sealed(iv, blob))
            } catch (e: Exception) {
                // Key lost (restore, reset) or record corrupt: every client must pair again (B4).
                removeAll()
                return null
            }
        val prefix = (clientId + "\u0000").toByteArray(Charsets.UTF_8)
        if (plain.size != prefix.size + 32 || !plain.copyOfRange(0, prefix.size).contentEquals(prefix)) return null
        return plain.copyOfRange(prefix.size, plain.size)
    }

    @Synchronized
    override fun add(
        client: PairedClient,
        verifier: ByteArray,
    ) {
        val sealed = cipher.encrypt((client.clientId + "\u0000").toByteArray(Charsets.UTF_8) + verifier)
        val rest = records().filter { it.client.clientId != client.clientId }
        save(rest + Record(client, BridgeCrypto.b64url(sealed.iv), BridgeCrypto.b64url(sealed.ciphertextAndTag)))
    }

    @Synchronized
    override fun touch(
        clientId: String,
        nowEpochMs: Long,
    ) {
        save(
            records().map {
                if (it.client.clientId ==
                    clientId
                ) {
                    Record(it.client.copy(lastUsedAtEpochMs = nowEpochMs), it.iv, it.blob)
                } else {
                    it
                }
            },
        )
    }

    @Synchronized
    override fun remove(clientId: String) = save(records().filter { it.client.clientId != clientId })

    @Synchronized
    override fun removeAll() {
        prefs.commit(removes = listOf(KEY_CLIENTS))
    }

    companion object {
        const val FILE = "bridge_clients"
        private const val KEY_BRIDGE_ID = "bridge_id"
        private const val KEY_CLIENTS = "clients_v1"
    }
}
