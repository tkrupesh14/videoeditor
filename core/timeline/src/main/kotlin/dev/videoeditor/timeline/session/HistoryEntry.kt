package dev.videoeditor.timeline.session

import dev.videoeditor.timeline.command.Command
import dev.videoeditor.timeline.model.Project

enum class Origin { USER, AI, MCP }

/** One undo step: the project before and after. The command is for labelling and logging only. */
data class HistoryEntry(
    val label: String,
    val before: Project,
    val after: Project,
    val command: Command?,
    val origin: Origin,
)

data class EditorState(
    val project: Project,
    val revision: Long,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val undoLabel: String?,
    val redoLabel: String?,
    val transactionOpen: Boolean,
)
