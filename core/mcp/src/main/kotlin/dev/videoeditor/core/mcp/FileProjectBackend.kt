package dev.videoeditor.core.mcp

import dev.videoeditor.timeline.command.Command
import dev.videoeditor.timeline.command.CommandExecutor
import dev.videoeditor.timeline.command.CommandResult
import dev.videoeditor.timeline.host.AssetTranscript
import dev.videoeditor.timeline.host.TranscriptService
import dev.videoeditor.timeline.json.ProjectLoadResult
import dev.videoeditor.timeline.json.TimelineJson
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.schema.ToolCallResult
import dev.videoeditor.timeline.schema.ToolDispatcher
import dev.videoeditor.timeline.session.CommandTarget
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Offline guards (B8): only the given file, or files directly inside the workspace; no symlink escapes. */
object PathGuard {
    /**
     * Resolves the project file. With [workspace], [project] is resolved against it and must end up directly
     * inside it. Without, the file must resolve inside the directory it was named in (a symlink pointing
     * elsewhere is refused). Returns the canonical path.
     */
    fun resolveProject(
        project: String,
        workspace: String?,
    ): Path {
        val given: Path
        val allowedDir: Path
        if (workspace != null) {
            allowedDir = Path.of(workspace).toRealPath()
            given = allowedDir.resolve(project).normalize()
        } else {
            given = Path.of(project).toAbsolutePath().normalize()
            allowedDir = (given.parent ?: throw IllegalArgumentException("bad project path")).toRealPath()
        }
        if (!Files.exists(given)) throw IllegalArgumentException("project file not found: $given")
        val real = given.toRealPath()
        if (real.parent != allowedDir) {
            throw IllegalArgumentException("refused: $project resolves outside the allowed directory $allowedDir")
        }
        if (!Files.isRegularFile(real)) throw IllegalArgumentException("not a regular file: $real")
        return real
    }
}

/**
 * Edits one project JSON file through the :core:timeline command API, with the same tool code as the app
 * (ToolDispatcher). Writes are atomic (temp file, fsync, rename) under an exclusive lock. Export is
 * unsupported. Transcripts come from an optional sidecar `<file>.transcripts.json` (a JSON array of
 * AssetTranscript in source time).
 */
class FileProjectBackend(
    private val file: Path,
) : ProjectBackend {
    override val description: String = "offline project file $file"

    private val lockFile: Path = file.resolveSibling(file.fileName.toString() + ".lock")
    private val transcriptFile: Path = file.resolveSibling(file.fileName.toString() + ".transcripts.json")

    override suspend fun callTool(
        name: String,
        args: JsonObject,
    ): ToolCallResult {
        if (McpTools.find(name) == null) return McpTools.error("unknown_tool", "Unknown tool: $name")
        if (name == "export") {
            return McpTools.error(
                "UNSUPPORTED",
                "Export is not supported in offline --project mode: rendering needs the phone. " +
                    "Open the project in the app and export there, or run vedit-mcp against the phone.",
            )
        }
        return try {
            withLock {
                val loaded =
                    when (val r = TimelineJson.decodeProject(Files.readString(file))) {
                        is ProjectLoadResult.Success -> r.project
                        is ProjectLoadResult.Failure ->
                            return@withLock McpTools.error("project_unreadable", "Cannot open $file: ${r.error}")
                    }
                val target = FileTarget(loaded)
                val result = ToolDispatcher(null, transcripts()).call(target, name, args)
                if (target.dirty) writeAtomically(TimelineJson.encodeProject(target.project))
                result
            }
        } catch (e: IOException) {
            McpTools.error("io_error", "Cannot access the project file: ${e.message}")
        }
    }

    private class FileTarget(
        override var project: Project,
    ) : CommandTarget {
        var dirty = false

        override fun submit(command: Command): CommandResult {
            val r = CommandExecutor.execute(project, command)
            if (r is CommandResult.Success && r.changed) {
                project = r.project
                dirty = true
            }
            return r
        }
    }

    private fun transcripts(): TranscriptService? {
        if (!Files.isRegularFile(transcriptFile)) return null
        val all =
            try {
                TimelineJson.json.decodeFromString(
                    ListSerializer(AssetTranscript.serializer()),
                    Files.readString(transcriptFile),
                )
            } catch (e: SerializationException) {
                Log.warn("ignoring unreadable transcript sidecar: ${e.message}")
                return null
            }
        val byAsset = all.associateBy { it.assetId }
        return object : TranscriptService {
            override suspend fun transcript(assetId: String): AssetTranscript? = byAsset[assetId]
        }
    }

    private suspend fun <T> withLock(block: suspend () -> T): T =
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
            ch.lock().use { block() }
        }

    private fun writeAtomically(text: String) {
        val tmp = Files.createTempFile(file.parent, "." + file.fileName.toString(), ".tmp")
        try {
            FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
                val buf = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
                while (buf.hasRemaining()) ch.write(buf)
                ch.force(true)
            }
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
