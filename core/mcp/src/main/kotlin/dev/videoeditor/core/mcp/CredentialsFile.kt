package dev.videoeditor.core.mcp

import dev.videoeditor.core.mcp.bridge.BridgeCredentials
import dev.videoeditor.core.mcp.bridge.BridgeScope
import dev.videoeditor.core.mcp.bridge.SecretToken
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * `~/.config/vedit-mcp/credentials.json` (B3 step 5): file mode 0600, directory 0700; a file with wider
 * permissions is refused. Override the path with `VEDIT_MCP_CREDENTIALS`; `XDG_CONFIG_HOME` is honoured.
 */
object CredentialsFile {
    const val ENV = "VEDIT_MCP_CREDENTIALS"

    fun defaultPath(env: Map<String, String> = System.getenv()): Path {
        env[ENV]?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
        val base =
            env["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                ?: Path.of(System.getProperty("user.home"), ".config")
        return base.resolve("vedit-mcp").resolve("credentials.json")
    }

    private fun posix(path: Path): Boolean = path.fileSystem.supportedFileAttributeViews().contains("posix")

    fun write(
        path: Path,
        credentials: BridgeCredentials,
    ) {
        val dir = path.toAbsolutePath().parent
        if (!Files.exists(dir)) {
            if (posix(dir)) {
                Files.createDirectories(
                    dir,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                )
            } else {
                Files.createDirectories(dir)
            }
        }
        val body =
            buildJsonObject {
                put("version", 1)
                put("bridgeId", credentials.bridgeId)
                put("clientId", credentials.clientId)
                credentials.token.use { put("token", it) }
                put("scopes", buildJsonArray { credentials.scopes.forEach { add(JsonPrimitive(it.wire)) } })
            }.toString()
        val tmp =
            if (posix(dir)) {
                Files.createTempFile(
                    dir,
                    ".credentials",
                    ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                )
            } else {
                Files.createTempFile(dir, ".credentials", ".tmp")
            }
        try {
            Files.writeString(tmp, body)
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** Null when the file does not exist. Throws [IOException] when it is unreadable or too permissive. */
    fun read(path: Path): BridgeCredentials? {
        if (!Files.exists(path)) return null
        if (posix(path)) {
            val perms = Files.getPosixFilePermissions(path)
            val wide =
                perms.any {
                    it != PosixFilePermission.OWNER_READ && it != PosixFilePermission.OWNER_WRITE
                }
            if (wide) {
                throw IOException(
                    "refusing to use $path: permissions ${PosixFilePermissions.toString(
                        perms,
                    )} are wider than rw------- " +
                        "(run: chmod 600 $path)",
                )
            }
        }
        return try {
            val o = Json.parseToJsonElement(Files.readString(path)).jsonObject

            fun s(k: String) =
                (o[k] as? JsonPrimitive)?.contentOrNull ?: throw IOException("credentials file is missing '$k'")
            val scopes =
                (o["scopes"] as? JsonArray)
                    .orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(BridgeScope::fromWire) }
                    .toSet()
            BridgeCredentials(s("bridgeId"), s("clientId"), SecretToken.of(s("token")), scopes)
        } catch (e: SerializationException) {
            throw IOException("credentials file is not valid JSON")
        } catch (e: IllegalArgumentException) {
            throw IOException("credentials file is malformed")
        }
    }
}
