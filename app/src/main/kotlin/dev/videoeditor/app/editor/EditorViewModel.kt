package dev.videoeditor.app.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.videoeditor.timeline.command.AddCaption
import dev.videoeditor.timeline.command.AddClip
import dev.videoeditor.timeline.command.AddTrack
import dev.videoeditor.timeline.command.CaptionSpec
import dev.videoeditor.timeline.command.Command
import dev.videoeditor.timeline.command.CommandExecutor
import dev.videoeditor.timeline.command.CommandResult
import dev.videoeditor.timeline.command.EditCaption
import dev.videoeditor.timeline.command.SetClipProperties
import dev.videoeditor.timeline.host.ExportState
import dev.videoeditor.timeline.json.ProjectLoadResult
import dev.videoeditor.timeline.json.TimelineJson
import dev.videoeditor.timeline.model.Clip
import dev.videoeditor.timeline.model.MediaAsset
import dev.videoeditor.timeline.model.MediaClip
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.model.TrackKind
import dev.videoeditor.timeline.model.TransformPatch
import dev.videoeditor.timeline.sample.SampleProjects
import dev.videoeditor.timeline.session.EditPreview
import dev.videoeditor.timeline.session.EditorSession
import dev.videoeditor.timeline.session.EditorState
import dev.videoeditor.timeline.session.Origin
import dev.videoeditor.ui.shell.ClipProperty
import dev.videoeditor.ui.shell.EditorShortcut
import dev.videoeditor.ui.shell.EditorTab
import dev.videoeditor.ui.shell.ExportDialogState
import dev.videoeditor.ui.timeline.TimelineEdits
import dev.videoeditor.ui.timeline.TimelineEvent
import dev.videoeditor.ui.timeline.TimelineZoom
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the editor screen renders. */
data class EditorUiState(
    val loaded: Boolean = false,
    val project: Project,
    val revision: Long = 0,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoLabel: String? = null,
    val redoLabel: String? = null,
    val selection: Set<String> = emptySet(),
    val playheadUs: Long = 0L,
    val isPlaying: Boolean = false,
    val previewError: String? = null,
    val dpPerSecond: Float = TimelineZoom.DEFAULT_DP_PER_SECOND,
    val scrollUs: Long = 0L,
    val snapping: Boolean = true,
    val missingAssetIds: Set<String> = emptySet(),
    val importing: Boolean = false,
    val export: ExportDialogState? = null,
    val showSettings: Boolean = false,
    val tab: EditorTab = EditorTab.PREVIEW,
    val message: String? = null,
)

/**
 * Owns the one [EditorSession] for the open project and exposes [state]. Preview, export, storage and media probing
 * go through the ports in EditorPorts.kt. Must be used from the main thread (the session is synchronous).
 */
@OptIn(FlowPreview::class)
class EditorViewModel(
    val preview: PreviewPort,
    private val exporter: ExportPort,
    private val store: ProjectStore,
    private val importer: MediaImporter,
    private val availability: MediaAvailability,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val autosaveDebounceMs: Long = 500,
) : ViewModel() {
    private val session = EditorSession(blankProject())
    private val sessionState = MutableStateFlow(session.state)
    private val _state = MutableStateFlow(EditorUiState(project = session.project))
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private var previewKey = 0L
    private var exportJobId: String? = null
    private var exportJob: Job? = null
    private var shuttleHintShown = false
    private val listener: AutoCloseable

    init {
        listener = session.addListener { onSessionChanged(it) }
        viewModelScope.launch { loadInitial() }
        viewModelScope.launch {
            var wasPlaying = false
            preview.state.collect { p ->
                _state.update {
                    val follow = p.isPlaying || wasPlaying
                    it.copy(
                        isPlaying = p.isPlaying,
                        previewError = p.error,
                        playheadUs = if (follow) p.positionUs else it.playheadUs,
                    )
                }
                wasPlaying = p.isPlaying
            }
        }
        viewModelScope.launch {
            sessionState
                .filter { !it.transactionOpen }
                .map { it.project }
                .distinctUntilChanged()
                .drop(1)
                .debounce(autosaveDebounceMs)
                .collect { p ->
                    val json = TimelineJson.encodeProject(p)
                    runCatching { withContext(ioDispatcher) { store.save(json) } }
                        .onFailure { e -> showMessage("Autosave failed: ${e.message}") }
                }
        }
    }

    // ---- Project lifecycle ----

    private suspend fun loadInitial() {
        val text = runCatching { withContext(ioDispatcher) { store.load() } }.getOrNull()
        if (text != null) {
            when (val r = TimelineJson.decodeProject(text)) {
                is ProjectLoadResult.Success -> session.load(r.project)
                is ProjectLoadResult.Failure ->
                    showMessage("The saved project could not be opened (${r.error}); started a new project.")
            }
        }
        _state.update { it.copy(loaded = true) }
        checkAvailability()
    }

    fun newProject(): Unit = replaceProject(blankProject(), "New project")

    fun openSampleProject(): Unit = replaceProject(SampleProjects.twoClipDemo(), "Opened the sample project")

    private fun replaceProject(
        project: Project,
        note: String,
    ) {
        preview.pause()
        session.load(project)
        _state.update { it.copy(selection = emptySet(), playheadUs = 0L, scrollUs = 0L) }
        preview.seekTo(0L)
        showMessage(note)
    }

    /** uri -> openable. Assets not in here yet are kept out of the preview until checked. */
    private val uriAvailable = mutableMapOf<String, Boolean>()

    private fun checkAvailability() {
        val unchecked =
            session.project.assets
                .map { it.uri }
                .filter { it !in uriAvailable }
                .distinct()
        if (unchecked.isEmpty()) return
        viewModelScope.launch {
            val results =
                withContext(ioDispatcher) {
                    unchecked.associateWith { runCatching { availability.isAvailable(it) }.getOrDefault(false) }
                }
            uriAvailable.putAll(results)
            val missing =
                session.project.assets
                    .filter { uriAvailable[it.uri] == false }
                    .map { it.id }
                    .toSet()
            _state.update { it.copy(missingAssetIds = missing) }
            pushPreview(force = true)
        }
    }

    // ---- Session plumbing ----

    private fun onSessionChanged(s: EditorState) {
        sessionState.value = s
        _state.update { ui ->
            ui.copy(
                project = s.project,
                revision = s.revision,
                canUndo = s.canUndo,
                canRedo = s.canRedo,
                undoLabel = s.undoLabel,
                redoLabel = s.redoLabel,
                selection = ui.selection.filterTo(mutableSetOf()) { s.project.clip(it) != null },
            )
        }
        val missing =
            s.project.assets
                .filter { uriAvailable[it.uri] == false }
                .map { it.id }
                .toSet()
        if (missing != _state.value.missingAssetIds) _state.update { it.copy(missingAssetIds = missing) }
        if (!s.transactionOpen) {
            pushPreview(force = false)
            if (_state.value.loaded) checkAvailability()
        }
    }

    private var lastPreviewRevision = -1L

    private fun pushPreview(force: Boolean) {
        val s = session.state
        if (s.transactionOpen) return
        if (!force && s.revision == lastPreviewRevision) return
        lastPreviewRevision = s.revision
        val notPlayable =
            s.project.assets
                .filter { uriAvailable[it.uri] != true }
                .map { it.id }
                .toSet()
        preview.setProject(playableProject(s.project, notPlayable), ++previewKey)
    }

    private fun execute(command: Command): Boolean {
        val r = session.execute(command)
        if (r is CommandResult.Failure) showMessage(r.error.message)
        return r is CommandResult.Success
    }

    fun undo() {
        session.undo()
    }

    fun redo() {
        session.redo()
    }

    // ---- Timeline ----

    fun onTimelineEvent(event: TimelineEvent) {
        when (event) {
            is TimelineEvent.Seek -> seek(event.timeUs)
            is TimelineEvent.SelectClip ->
                _state.update {
                    val sel =
                        when {
                            !event.additive -> setOf(event.clipId)
                            event.clipId in it.selection -> it.selection - event.clipId
                            else -> it.selection + event.clipId
                        }
                    it.copy(selection = sel)
                }
            TimelineEvent.ClearSelection -> _state.update { it.copy(selection = emptySet()) }
            is TimelineEvent.ViewportChanged ->
                _state.update {
                    it.copy(
                        dpPerSecond = TimelineZoom.clampZoom(event.dpPerSecond),
                        scrollUs = event.scrollUs,
                    )
                }
            is TimelineEvent.SetSnapping -> _state.update { it.copy(snapping = event.enabled) }
            is TimelineEvent.Execute -> execute(event.command)
            is TimelineEvent.GestureStart -> {
                if (_state.value.isPlaying) preview.pause()
                session.beginTransaction(event.label)
            }
            is TimelineEvent.GestureUpdate ->
                if (session.state.transactionOpen) {
                    session.updateTransaction(
                        event.command,
                    )
                }
            is TimelineEvent.GestureEnd ->
                if (session.state.transactionOpen) {
                    if (event.commit) session.commitTransaction() else session.cancelTransaction()
                }
        }
    }

    fun seek(timeUs: Long) {
        val t = timeUs.coerceAtLeast(0L)
        _state.update { it.copy(playheadUs = t) }
        preview.seekTo(t)
    }

    fun splitAtPlayhead() {
        val s = _state.value
        val cmd = TimelineEdits.splitAtPlayhead(s.project, s.selection, s.playheadUs)
        if (cmd == null) showMessage("No clip under the playhead to split") else execute(cmd)
    }

    fun deleteSelection() {
        TimelineEdits.deleteSelection(_state.value.selection)?.let { execute(it) }
    }

    fun addCaptionAtPlayhead() {
        val start = _state.value.playheadUs
        if (execute(AddCaption(listOf(CaptionSpec(start, start + 2_000_000, "Caption"))))) {
            val id =
                session.project.tracks
                    .filter { it.kind == TrackKind.CAPTION }
                    .flatMap { it.clips }
                    .firstOrNull { it.startUs == start }
                    ?.id
            if (id != null) _state.update { it.copy(selection = setOf(id)) }
        }
    }

    // ---- Playback & shortcuts ----

    fun playPause() {
        if (_state.value.isPlaying) {
            preview.pause()
        } else {
            preview.seekTo(_state.value.playheadUs)
            preview.play()
        }
    }

    fun onShortcut(shortcut: EditorShortcut) {
        when (shortcut) {
            EditorShortcut.PLAY_PAUSE -> playPause()
            EditorShortcut.SHUTTLE_FORWARD -> if (!_state.value.isPlaying) playPause()
            EditorShortcut.SHUTTLE_STOP -> preview.pause()
            EditorShortcut.SHUTTLE_BACK -> {
                preview.pause()
                seek(_state.value.playheadUs - 1_000_000)
                if (!shuttleHintShown) {
                    shuttleHintShown = true
                    showMessage("Reverse playback is not available yet; J steps back 1 s")
                }
            }
            EditorShortcut.SPLIT_AT_PLAYHEAD -> splitAtPlayhead()
            EditorShortcut.UNDO -> undo()
            EditorShortcut.REDO -> redo()
            EditorShortcut.DELETE_SELECTION -> deleteSelection()
        }
    }

    // ---- Inspector ----

    fun onPropertyChange(
        property: ClipProperty,
        value: Double,
    ) {
        val ids = _state.value.selection.toList()
        if (ids.isEmpty()) return
        if (!session.state.transactionOpen) {
            val label =
                when (property) {
                    ClipProperty.SPEED -> "Change speed"
                    ClipProperty.VOLUME -> "Change volume"
                    ClipProperty.OPACITY -> "Change opacity"
                }
            if (!session.beginTransaction(label)) return
        }
        val cmd =
            when (property) {
                ClipProperty.SPEED -> SetClipProperties(ids, speed = value)
                ClipProperty.VOLUME -> SetClipProperties(ids, volume = value)
                ClipProperty.OPACITY -> SetClipProperties(ids, transform = TransformPatch(opacity = value))
            }
        val r = session.updateTransaction(cmd)
        if (r is CommandResult.Failure) _state.update { it.copy(message = r.error.message) }
    }

    fun onPropertyChangeFinished() {
        if (session.state.transactionOpen) session.commitTransaction()
    }

    fun onCaptionTextCommit(text: String) {
        val id = _state.value.selection.singleOrNull() ?: return
        execute(EditCaption(listOf(id), text = text))
    }

    // ---- Media ----

    fun onMediaPicked(uris: List<String>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(importing = true) }
            try {
                for (uri in uris) importOne(uri)
            } finally {
                _state.update { it.copy(importing = false) }
            }
        }
    }

    private suspend fun importOne(uri: String) {
        when (val outcome = importer.probe(uri)) {
            is ImportOutcome.Unsupported -> showMessage(outcome.message)
            is ImportOutcome.Failed -> showMessage("Import failed: ${outcome.message}")
            is ImportOutcome.Ready -> {
                val p = session.newPreview()
                val r = p.submit(outcome.command)
                if (r is CommandResult.Failure) {
                    showMessage("Import failed: ${r.error.message}")
                    return
                }
                val asset = p.project.assets.first { it.uri == outcome.command.uri }
                if (!appendClip(p, asset)) return
                val name = asset.displayName ?: "media"
                val c = session.commitPreview(p, "Import $name", Origin.USER)
                if (c is CommandResult.Failure) showMessage("Import failed: ${c.error.message}")
            }
        }
    }

    fun addAssetToTimeline(assetId: String) {
        val asset = session.project.asset(assetId) ?: return
        val p = session.newPreview()
        if (!appendClip(p, asset)) return
        val c = session.commitPreview(p, "Add ${asset.displayName ?: "clip"}", Origin.USER)
        if (c is CommandResult.Failure) showMessage(c.error.message)
    }

    /** Appends [asset] to the first matching track in [p], creating that track if needed. */
    private fun appendClip(
        p: EditPreview,
        asset: MediaAsset,
    ): Boolean {
        val kind = if (asset.hasVideo) TrackKind.VIDEO else TrackKind.AUDIO
        val trackId =
            p.project.tracks
                .firstOrNull { it.kind == kind }
                ?.id
                ?: when (val t = p.submit(AddTrack(kind))) {
                    is CommandResult.Success -> t.createdIds.first()
                    is CommandResult.Failure -> {
                        showMessage(t.error.message)
                        return false
                    }
                }
        val r = p.submit(AddClip(trackId = trackId, assetId = asset.id))
        if (r is CommandResult.Failure) showMessage("Could not add the clip: ${r.error.message}")
        return r is CommandResult.Success
    }

    // ---- Export ----

    fun startExport() {
        val s = _state.value
        if (s.export is ExportDialogState.Running) return
        if (s.missingAssetIds.isNotEmpty()) {
            showMessage("Export unavailable: ${s.missingAssetIds.size} media file(s) are missing on this device")
            return
        }
        if (s.project.tracks.none { t -> t.clips.any { it is MediaClip } }) {
            showMessage("Nothing to export yet: add a video first")
            return
        }
        preview.pause()
        _state.update { it.copy(export = ExportDialogState.Running(null)) }
        exportJob =
            viewModelScope.launch {
                val jobId =
                    try {
                        exporter.start(s.project, exportFileName(s.project))
                    } catch (e: Exception) {
                        _state.update { it.copy(export = ExportDialogState.Failed(e.message ?: e.toString())) }
                        return@launch
                    }
                exportJobId = jobId
                exporter
                    .progress(jobId)
                    .takeWhile { u ->
                        _state.update { it.copy(export = u.toDialogState()) }
                        !u.isTerminal
                    }.collect {}
            }
    }

    fun cancelExport() {
        val id = exportJobId
        viewModelScope.launch {
            if (id != null) exporter.cancel(id)
            if (_state.value.export is ExportDialogState.Running) {
                exportJob?.cancel()
                _state.update { it.copy(export = ExportDialogState.Cancelled) }
            }
        }
    }

    fun dismissExport() {
        if (_state.value.export !is ExportDialogState.Running) _state.update { it.copy(export = null) }
    }

    // ---- Chrome ----

    fun selectTab(tab: EditorTab) = _state.update { it.copy(tab = tab) }

    fun openSettings() = _state.update { it.copy(showSettings = true) }

    fun closeSettings() = _state.update { it.copy(showSettings = false) }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun showMessage(text: String) = _state.update { it.copy(message = text) }

    override fun onCleared() {
        listener.close()
        if (session.state.transactionOpen) session.commitTransaction()
        // Save synchronously-ish on the way out so the last edit is not lost to the debounce.
        val json = TimelineJson.encodeProject(session.project)
        kotlinx.coroutines.runBlocking { runCatching { withContext(ioDispatcher) { store.save(json) } } }
        preview.release()
    }

    companion object {
        fun blankProject(): Project {
            val empty = Project.empty(id = "project", name = "Untitled project")
            return (CommandExecutor.execute(empty, AddTrack(TrackKind.VIDEO)) as CommandResult.Success).project
        }

        /** The project without media clips whose files are missing, so the player never opens them. */
        fun playableProject(
            project: Project,
            missingAssetIds: Set<String>,
        ): Project {
            if (missingAssetIds.isEmpty()) return project
            val keep: (Clip) -> Boolean = { c -> !(c is MediaClip && c.assetId in missingAssetIds) }
            return project.copy(tracks = project.tracks.map { t -> t.copy(clips = t.clips.filter(keep)) })
        }

        fun exportFileName(project: Project): String {
            val base =
                project.name
                    .replace(Regex("[^A-Za-z0-9._-]+"), "_")
                    .trim('_')
                    .ifEmpty { "export" }
            return "$base.mp4"
        }

        private fun ExportUpdate.toDialogState(): ExportDialogState =
            when (state) {
                ExportState.QUEUED, ExportState.RUNNING -> ExportDialogState.Running(fraction)
                ExportState.DONE -> ExportDialogState.Done(outputPath ?: "(unknown location)")
                ExportState.FAILED -> ExportDialogState.Failed(error ?: "Unknown error")
                ExportState.CANCELLED -> ExportDialogState.Cancelled
            }
    }
}
