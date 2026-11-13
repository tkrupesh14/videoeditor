package dev.videoeditor.timeline.command

import dev.videoeditor.timeline.command.internal.Reject
import dev.videoeditor.timeline.command.internal.addCaption
import dev.videoeditor.timeline.command.internal.addClip
import dev.videoeditor.timeline.command.internal.addTrack
import dev.videoeditor.timeline.command.internal.applyCuts
import dev.videoeditor.timeline.command.internal.deleteClips
import dev.videoeditor.timeline.command.internal.editCaption
import dev.videoeditor.timeline.command.internal.importMedia
import dev.videoeditor.timeline.command.internal.moveClip
import dev.videoeditor.timeline.command.internal.removeTrack
import dev.videoeditor.timeline.command.internal.rippleDeleteRange
import dev.videoeditor.timeline.command.internal.setClipProperties
import dev.videoeditor.timeline.command.internal.setProjectSettings
import dev.videoeditor.timeline.command.internal.splitClip
import dev.videoeditor.timeline.command.internal.trimClip
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.validate.ProjectValidator

/**
 * Stateless, pure command execution (design section 4). `execute` never throws for domain errors and never
 * returns an invalid project: a result that fails [ProjectValidator] becomes [CommandError.Internal].
 */
object CommandExecutor {
    fun execute(
        project: Project,
        command: Command,
    ): CommandResult {
        val raw =
            try {
                if (command is Batch) executeBatch(project, command) else dispatch(project, command)
            } catch (r: Reject) {
                CommandResult.Failure(r.error)
            } catch (e: RuntimeException) {
                CommandResult.Failure(
                    CommandError.Internal("${command.typeName}: ${e::class.simpleName}: ${e.message}"),
                )
            }
        if (raw !is CommandResult.Success) return raw
        val changed = raw.project != project
        if (!changed) return raw.copy(project = project, changed = false)
        val violations = ProjectValidator.validate(raw.project)
        if (violations.isNotEmpty()) {
            return CommandResult.Failure(
                CommandError.Internal(
                    "${command.typeName} produced an invalid project: " + violations.joinToString("; ") { it.message },
                ),
            )
        }
        return raw.copy(changed = true)
    }

    private fun dispatch(
        p: Project,
        c: Command,
    ): CommandResult =
        when (c) {
            is ImportMedia -> importMedia(p, c)
            is AddTrack -> addTrack(p, c)
            is RemoveTrack -> removeTrack(p, c)
            is AddClip -> addClip(p, c)
            is SplitClip -> splitClip(p, c)
            is TrimClip -> trimClip(p, c)
            is MoveClip -> moveClip(p, c)
            is DeleteClips -> deleteClips(p, c)
            is RippleDeleteRange -> rippleDeleteRange(p, c)
            is SetClipProperties -> setClipProperties(p, c)
            is AddCaption -> addCaption(p, c)
            is EditCaption -> editCaption(p, c)
            is ApplyCuts -> applyCuts(p, c)
            is SetProjectSettings -> setProjectSettings(p, c)
            is Batch -> error("batch is handled by executeBatch")
        }

    private fun executeBatch(
        p: Project,
        b: Batch,
    ): CommandResult {
        b.commands.forEachIndexed { i, c ->
            if (c is Batch) return CommandResult.Failure(CommandError.InvalidArgument("commands[$i]", "nested batch"))
        }
        if (b.commands.isEmpty()) return CommandResult.Success(p, b.label ?: "Empty batch", changed = false)
        var current = p
        val created = mutableListOf<String>()
        val summaries = mutableListOf<String>()
        b.commands.forEachIndexed { i, c ->
            when (val r = execute(current, c)) {
                is CommandResult.Failure -> return CommandResult.Failure(
                    CommandError.BatchFailed(i, c.typeName, r.error),
                )
                is CommandResult.Success -> {
                    current = r.project
                    created += r.createdIds
                    summaries += r.summary
                }
            }
        }
        val summary = b.label ?: "${b.commands.size} edits: ${summaries.take(3).joinToString("; ")}"
        return CommandResult.Success(current, summary, current != p, created)
    }
}
