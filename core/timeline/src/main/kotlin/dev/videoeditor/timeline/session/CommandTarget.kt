package dev.videoeditor.timeline.session

import dev.videoeditor.timeline.command.Command
import dev.videoeditor.timeline.command.CommandResult
import dev.videoeditor.timeline.model.Project

/** Something commands can be submitted to: the live session or a detached preview. */
interface CommandTarget {
    val project: Project

    fun submit(command: Command): CommandResult
}
