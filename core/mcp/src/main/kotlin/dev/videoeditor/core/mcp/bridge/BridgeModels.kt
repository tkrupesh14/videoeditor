package dev.videoeditor.core.mcp.bridge

import kotlinx.serialization.json.JsonObject
import java.net.InetAddress
import java.nio.file.Path

/*
 * Shared bridge model (docs/design/security.md B4, B9). Pure JVM: used by the phone app (:app) and by vedit-mcp.
 */

enum class BridgeScope(
    val wire: String,
) {
    READ("read"),
    EDIT("edit"),
    EXPORT("export"),
    ;

    companion object {
        fun fromWire(s: String): BridgeScope? = entries.firstOrNull { it.wire == s }

        val DEFAULT: Set<BridgeScope> = setOf(READ, EDIT)
    }
}

data class PairedClient(
    val clientId: String,
    val name: String,
    val scopes: Set<BridgeScope>,
    val createdAtEpochMs: Long,
    val lastUsedAtEpochMs: Long?,
)

/** A bridge token. Never printed: [toString] is redacted; read it only through [use]. */
class SecretToken private constructor(
    private val value: String,
) {
    fun <T> use(block: (String) -> T): T = block(value)

    override fun toString(): String = "SecretToken(REDACTED)"

    override fun equals(other: Any?): Boolean =
        other is SecretToken && BridgeCrypto.constantTimeEquals(value, other.value)

    override fun hashCode(): Int = 0

    companion object {
        fun of(value: String): SecretToken {
            require(value.startsWith(BridgeCrypto.TOKEN_PREFIX)) { "not a bridge token" }
            return SecretToken(value)
        }
    }
}

/** What `vedit-mcp pair` persists. [toString] redacts the token. */
class BridgeCredentials(
    val bridgeId: String,
    val clientId: String,
    val token: SecretToken,
    val scopes: Set<BridgeScope>,
) {
    override fun toString(): String =
        "BridgeCredentials(bridgeId=$bridgeId, clientId=$clientId, token=REDACTED, scopes=$scopes)"
}

/** Application error codes (B5). */
object BridgeErrors {
    const val UNAUTHENTICATED = 1001
    const val FORBIDDEN_SCOPE = 1002
    const val STALE_REVISION = 1003
    const val COMMAND_REJECTED = 1004
    const val USER_DENIED = 1005
    const val RATE_LIMITED = 1006
    const val NOT_FOUND = 1007
    const val PAYLOAD_TOO_LARGE = 1008
    const val SESSION_CLOSED = 1009
    const val UNSUPPORTED = 1010

    fun name(code: Int): String =
        when (code) {
            UNAUTHENTICATED -> "UNAUTHENTICATED"
            FORBIDDEN_SCOPE -> "FORBIDDEN_SCOPE"
            STALE_REVISION -> "STALE_REVISION"
            COMMAND_REJECTED -> "COMMAND_REJECTED"
            USER_DENIED -> "USER_DENIED"
            RATE_LIMITED -> "RATE_LIMITED"
            NOT_FOUND -> "NOT_FOUND"
            PAYLOAD_TOO_LARGE -> "PAYLOAD_TOO_LARGE"
            SESSION_CLOSED -> "SESSION_CLOSED"
            UNSUPPORTED -> "UNSUPPORTED"
            else -> "ERROR_$code"
        }
}

class BridgeException(
    val code: Int,
    message: String,
    val data: JsonObject? = null,
) : Exception(message) {
    val name: String get() = BridgeErrors.name(code)
}

sealed interface BridgeEndpoint {
    /** Loopback TCP, normally the local end of `adb forward` or `ssh -R`. Non-loopback hosts are refused. */
    data class Tcp(
        val host: String = "127.0.0.1",
        val port: Int,
    ) : BridgeEndpoint {
        init {
            require(port in 1..65535) { "port out of range" }
            require(InetAddress.getAllByName(host).all { it.isLoopbackAddress }) {
                "bridge host must be a loopback address; got $host"
            }
        }

        override fun toString(): String = "tcp:$host:$port"
    }

    data class UnixSocket(
        val path: Path,
    ) : BridgeEndpoint {
        override fun toString(): String = "unix:$path"
    }

    companion object {
        const val DEFAULT_PORT = 47821
        const val SOCKET_NAME = "dev.videoeditor.app.bridge"

        /** `tcp:PORT`, `tcp:HOST:PORT`, `unix:/path`, or a bare port. */
        fun parse(spec: String): BridgeEndpoint {
            val s = spec.trim()
            return when {
                s.startsWith("unix:") -> UnixSocket(Path.of(s.removePrefix("unix:")))
                s.startsWith("tcp:") -> {
                    val rest = s.removePrefix("tcp:")
                    val i = rest.lastIndexOf(':')
                    if (i < 0) Tcp(port = rest.toInt()) else Tcp(rest.substring(0, i), rest.substring(i + 1).toInt())
                }
                else -> Tcp(port = s.toInt())
            }
        }
    }
}

/** Remote command types callable over the bridge (B6). Deny by default: anything else is rejected. */
object RemoteCommandAllowlist {
    val types: Set<String> =
        setOf("split_clip", "trim_clip", "move_clip", "delete_clips", "add_caption", "apply_cuts")

    fun allows(type: String?): Boolean = type != null && type in types
}
