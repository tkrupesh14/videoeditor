package dev.videoeditor.timeline.command

import dev.videoeditor.timeline.model.Project

sealed interface CommandResult {
    data class Success(
        val project: Project,
        val summary: String,
        val changed: Boolean = true,
        val createdIds: List<String> = emptyList(),
    ) : CommandResult

    data class Failure(
        val error: CommandError,
    ) : CommandResult
}
