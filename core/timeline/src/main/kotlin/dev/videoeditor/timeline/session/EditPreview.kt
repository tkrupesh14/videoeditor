package dev.videoeditor.timeline.session

import dev.videoeditor.timeline.command.Batch
import dev.videoeditor.timeline.command.Command
import dev.videoeditor.timeline.command.CommandExecutor
import dev.videoeditor.timeline.command.CommandResult
import dev.videoeditor.timeline.diff.ProjectDiff
import dev.videoeditor.timeline.model.Project

/**
 * A detached working copy for an AI suggestion (design section 5.4). Submitted commands accumulate on
 * [project]; the live session is untouched until [EditorSession.commitPreview]. Not thread-safe.
 */
class EditPreview internal constructor(
    val base: Project,
    val baseRevision: Long,
) : CommandTarget {
    override var project: Project = base
        private set

    private val applied = mutableListOf<Command>()
    private val created = mutableListOf<String>()

    /** Successfully applied commands, in order. Batches are flattened so [toBatch] never nests. */
    val commands: List<Command> get() = applied.toList()

    val createdIds: List<String> get() = created.toList()

    override fun submit(command: Command): CommandResult {
        val r = CommandExecutor.execute(project, command)
        if (r is CommandResult.Success) {
            project = r.project
            created += r.createdIds
            if (r.changed) {
                if (command is Batch) applied += command.commands else applied += command
            }
        }
        return r
    }

    fun diff(): ProjectDiff = ProjectDiff.between(base, project)

    fun toBatch(label: String? = null): Batch = Batch(commands, label)
}
