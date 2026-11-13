package dev.videoeditor.timeline.json

import kotlinx.serialization.json.JsonObject

/** One step of project-file migration on the JSON tree: [fromVersion] -> [fromVersion] + 1. */
interface Migration {
    val fromVersion: Int

    /** Returns the migrated tree. [TimelineJson.decodeProject] sets `schemaVersion` afterwards. */
    fun migrate(project: JsonObject): JsonObject
}
