package dev.videoeditor.timeline.session

import dev.videoeditor.timeline.command.Batch
import dev.videoeditor.timeline.command.Command
import dev.videoeditor.timeline.command.CommandError
import dev.videoeditor.timeline.command.CommandExecutor
import dev.videoeditor.timeline.command.CommandResult
import dev.videoeditor.timeline.model.Project

/**
 * The single mutable holder of editor state (design section 5.2): project, revision, snapshot undo/redo,
 * drag transactions and AI previews. All public methods are synchronized; listeners run synchronously
 * under the lock and must not call back into the session.
 */
class EditorSession(
    initial: Project,
    val historyLimit: Int = 100,
) {
    init {
        require(historyLimit >= 1) { "historyLimit must be >= 1" }
    }

    private class Transaction(
        val label: String,
        val origin: Origin,
        val base: Project,
        var lastCommand: Command? = null,
    )

    private var current: Project = initial
    private var rev: Long = 0
    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()
    private var tx: Transaction? = null
    private val listeners = mutableListOf<(EditorState) -> Unit>()

    val project: Project
        @Synchronized get() = current

    val revision: Long
        @Synchronized get() = rev

    val state: EditorState
        @Synchronized get() =
            EditorState(
                project = current,
                revision = rev,
                canUndo = tx == null && undoStack.isNotEmpty(),
                canRedo = tx == null && redoStack.isNotEmpty(),
                undoLabel = undoStack.lastOrNull()?.label,
                redoLabel = redoStack.lastOrNull()?.label,
                transactionOpen = tx != null,
            )

    /** Undo entries, oldest first. For tests and debugging. */
    val undoHistory: List<HistoryEntry>
        @Synchronized get() = undoStack.toList()

    val redoHistory: List<HistoryEntry>
        @Synchronized get() = redoStack.toList()

    @Synchronized
    fun execute(
        command: Command,
        origin: Origin = Origin.USER,
    ): CommandResult {
        if (tx != null) return CommandResult.Failure(CommandError.TransactionInProgress)
        val r = CommandExecutor.execute(current, command)
        if (r is CommandResult.Success && r.changed) {
            val label = (command as? Batch)?.label ?: r.summary
            push(HistoryEntry(label, current, r.project, command, origin))
            setProject(r.project)
        }
        return r
    }

    @Synchronized
    fun undo(): HistoryEntry? {
        if (tx != null) return null
        val e = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(e)
        setProject(e.before)
        return e
    }

    @Synchronized
    fun redo(): HistoryEntry? {
        if (tx != null) return null
        val e = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(e)
        setProject(e.after)
        return e
    }

    /** Opens a drag transaction. False if one is already open. */
    @Synchronized
    fun beginTransaction(
        label: String,
        origin: Origin = Origin.USER,
    ): Boolean {
        if (tx != null) return false
        tx = Transaction(label, origin, current)
        notifyListeners()
        return true
    }

    /** Executes [command] against the transaction base (replace semantics). */
    @Synchronized
    fun updateTransaction(command: Command): CommandResult {
        val t = tx ?: throw IllegalStateException("no transaction is open")
        val r = CommandExecutor.execute(t.base, command)
        if (r is CommandResult.Success) {
            t.lastCommand = command
            if (r.project != current) setProject(r.project)
        }
        return r
    }

    /** Pushes one entry for the whole gesture, or returns null if nothing changed. */
    @Synchronized
    fun commitTransaction(): HistoryEntry? {
        val t = tx ?: throw IllegalStateException("no transaction is open")
        tx = null
        if (t.lastCommand == null || current == t.base) {
            notifyListeners()
            return null
        }
        val e = HistoryEntry(t.label, t.base, current, t.lastCommand, t.origin)
        push(e)
        notifyListeners()
        return e
    }

    /** Restores the transaction base. No-op if no transaction is open. */
    @Synchronized
    fun cancelTransaction() {
        val t = tx ?: return
        tx = null
        if (current != t.base) setProject(t.base) else notifyListeners()
    }

    @Synchronized
    fun newPreview(): EditPreview = EditPreview(current, rev)

    @Synchronized
    fun commitPreview(
        preview: EditPreview,
        label: String,
        origin: Origin = Origin.AI,
    ): CommandResult {
        if (tx != null) return CommandResult.Failure(CommandError.TransactionInProgress)
        if (preview.baseRevision !=
            rev
        ) {
            return CommandResult.Failure(CommandError.StaleRevision(preview.baseRevision, rev))
        }
        if (preview.commands.isEmpty() || preview.project == current) {
            return CommandResult.Success(current, "No changes", changed = false)
        }
        val batch = preview.toBatch(label)
        push(HistoryEntry(label, current, preview.project, batch, origin))
        setProject(preview.project)
        return CommandResult.Success(current, label, changed = true, createdIds = preview.createdIds)
    }

    /** Replaces the project and clears history. Cancels an open transaction. */
    @Synchronized
    fun load(project: Project) {
        tx = null
        undoStack.clear()
        redoStack.clear()
        setProject(project)
    }

    fun asTarget(origin: Origin): CommandTarget =
        object : CommandTarget {
            override val project: Project get() = this@EditorSession.project

            override fun submit(command: Command): CommandResult = execute(command, origin)
        }

    @Synchronized
    fun addListener(listener: (EditorState) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { synchronized(this) { listeners.remove(listener) } }
    }

    private fun push(e: HistoryEntry) {
        undoStack.addLast(e)
        while (undoStack.size > historyLimit) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun setProject(p: Project) {
        current = p
        rev++
        notifyListeners()
    }

    private fun notifyListeners() {
        if (listeners.isEmpty()) return
        val s = state
        listeners.toList().forEach { it(s) }
    }
}
