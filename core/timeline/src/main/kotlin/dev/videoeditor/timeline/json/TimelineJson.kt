package dev.videoeditor.timeline.json

import dev.videoeditor.timeline.command.Command
import dev.videoeditor.timeline.command.CommandError
import dev.videoeditor.timeline.model.Limits
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.validate.ProjectValidator
import dev.videoeditor.timeline.validate.Violation
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

sealed interface ProjectLoadError {
    data class Malformed(
        val detail: String,
    ) : ProjectLoadError

    data class UnsupportedVersion(
        val found: Int,
        val supported: Int,
    ) : ProjectLoadError

    data class Invalid(
        val violations: List<Violation>,
    ) : ProjectLoadError
}

sealed interface ProjectLoadResult {
    data class Success(
        val project: Project,
        val migratedFrom: Int? = null,
    ) : ProjectLoadResult

    data class Failure(
        val error: ProjectLoadError,
    ) : ProjectLoadResult
}

sealed interface CommandDecodeResult {
    data class Success(
        val command: Command,
    ) : CommandDecodeResult

    data class Failure(
        val error: CommandError.Malformed,
    ) : CommandDecodeResult
}

/** The one JSON configuration for projects, commands and errors (design section 6.1). */
object TimelineJson {
    val json: Json =
        Json {
            classDiscriminator = "type"
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = false
        }

    const val CURRENT_SCHEMA_VERSION: Int = Limits.CURRENT_SCHEMA_VERSION

    /** Registered migrations; empty in v1. */
    val migrations: List<Migration> = emptyList()

    fun encodeProject(project: Project): String = json.encodeToString(Project.serializer(), project)

    fun decodeProject(
        text: String,
        migrations: List<Migration> = this.migrations,
    ): ProjectLoadResult = decodeProjectFrom(text, CURRENT_SCHEMA_VERSION, migrations)

    /** [decodeProject] against an arbitrary current version, so the migration hook is testable in v1. */
    internal fun decodeProjectFrom(
        text: String,
        current: Int,
        migrations: List<Migration>,
    ): ProjectLoadResult {
        fun fail(e: ProjectLoadError) = ProjectLoadResult.Failure(e)
        var tree =
            try {
                json.parseToJsonElement(text) as? JsonObject
                    ?: return fail(ProjectLoadError.Malformed("project must be a JSON object"))
            } catch (e: SerializationException) {
                return fail(ProjectLoadError.Malformed(e.message ?: "invalid JSON"))
            }
        val found =
            (tree["schemaVersion"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                ?: return fail(ProjectLoadError.Malformed("schemaVersion is missing or not an integer"))
        if (found > current) return fail(ProjectLoadError.UnsupportedVersion(found, current))
        if (found < 1) return fail(ProjectLoadError.Malformed("schemaVersion must be >= 1, was $found"))
        var version = found
        while (version < current) {
            val step =
                migrations.firstOrNull { it.fromVersion == version }
                    ?: return fail(ProjectLoadError.UnsupportedVersion(found, current))
            tree = JsonObject(step.migrate(tree) + ("schemaVersion" to JsonPrimitive(version + 1)))
            version++
        }
        val decoded =
            try {
                json.decodeFromJsonElement(Project.serializer(), tree)
            } catch (e: SerializationException) {
                return fail(ProjectLoadError.Malformed(e.message ?: "cannot decode project"))
            } catch (e: IllegalArgumentException) {
                return fail(ProjectLoadError.Malformed(e.message ?: "cannot decode project"))
            }
        val project = normalize(decoded)
        val violations = ProjectValidator.validate(project)
        if (violations.isNotEmpty()) return fail(ProjectLoadError.Invalid(violations))
        return ProjectLoadResult.Success(project, if (found < current) found else null)
    }

    /** Load normalization: clips sorted by start, nextId raised above every numeric id suffix. */
    fun normalize(project: Project): Project {
        val tracks = project.tracks.map { t -> t.copy(clips = t.clips.sortedBy { it.startUs }) }
        val ids = project.assets.map { it.id } + project.tracks.flatMap { t -> listOf(t.id) + t.clips.map { it.id } }
        val floor = (ProjectValidator.maxNumericSuffix(ids) ?: 0L) + 1
        return project.copy(tracks = tracks, nextId = maxOf(project.nextId, floor, 1L))
    }

    fun encodeCommand(command: Command): JsonObject = json.encodeToJsonElement(Command.serializer(), command).jsonObject

    fun decodeCommand(element: JsonObject): CommandDecodeResult =
        try {
            CommandDecodeResult.Success(json.decodeFromJsonElement(Command.serializer(), element))
        } catch (e: SerializationException) {
            CommandDecodeResult.Failure(CommandError.Malformed(e.message ?: "cannot decode command"))
        } catch (e: IllegalArgumentException) {
            CommandDecodeResult.Failure(CommandError.Malformed(e.message ?: "cannot decode command"))
        }

    fun decodeCommand(text: String): CommandDecodeResult {
        val element =
            try {
                json.parseToJsonElement(text)
            } catch (e: SerializationException) {
                return CommandDecodeResult.Failure(CommandError.Malformed(e.message ?: "invalid JSON"))
            }
        return (element as? JsonObject)?.let { decodeCommand(it) }
            ?: CommandDecodeResult.Failure(CommandError.Malformed("command must be a JSON object"))
    }

    /** The error as a JSON object with its `type` code, its fields, and a human-readable `message`. */
    fun encodeError(error: CommandError): JsonObject {
        val base = json.encodeToJsonElement(CommandError.serializer(), error).jsonObject
        return JsonObject(base + ("message" to JsonPrimitive(error.message)))
    }

    fun decodeError(element: JsonObject): CommandError =
        json.decodeFromJsonElement(CommandError.serializer(), JsonObject(element - "message"))
}
