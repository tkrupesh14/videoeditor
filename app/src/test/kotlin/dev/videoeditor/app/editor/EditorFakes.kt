package dev.videoeditor.app.editor

import dev.videoeditor.timeline.host.ExportState
import dev.videoeditor.timeline.model.Project
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakePreview : PreviewPort {
    override val state = MutableStateFlow(PlaybackState())
    val projects = mutableListOf<Pair<Project, Long>>()
    val seeks = mutableListOf<Long>()
    var released = false

    override fun setProject(
        project: Project,
        key: Long,
    ) {
        projects += project to key
    }

    override fun play() {
        state.value = state.value.copy(isPlaying = true)
    }

    override fun pause() {
        state.value = state.value.copy(isPlaying = false)
    }

    override fun seekTo(positionUs: Long) {
        seeks += positionUs
        state.value = state.value.copy(positionUs = positionUs)
    }

    override fun release() {
        released = true
    }
}

class FakeExport : ExportPort {
    val flow = MutableStateFlow(ExportUpdate(ExportState.QUEUED))
    val started = mutableListOf<Pair<Project, String>>()
    val cancelled = mutableListOf<String>()

    override suspend fun start(
        project: Project,
        fileName: String,
    ): String {
        started += project to fileName
        return "job-1"
    }

    override fun progress(jobId: String): Flow<ExportUpdate> = flow

    override suspend fun cancel(jobId: String) {
        cancelled += jobId
        flow.value = ExportUpdate(ExportState.CANCELLED)
    }
}

class FakeStore(
    var text: String? = null,
) : ProjectStore {
    val saves = mutableListOf<String>()

    override suspend fun load(): String? = text

    override suspend fun save(json: String) {
        saves += json
        text = json
    }
}

class FakeImporter(
    private val outcomes: Map<String, ImportOutcome>,
) : MediaImporter {
    override suspend fun probe(uri: String): ImportOutcome = outcomes[uri] ?: ImportOutcome.Failed("unknown $uri")
}

class FakeAvailability(
    var available: Set<String> = emptySet(),
) : MediaAvailability {
    override suspend fun isAvailable(uri: String): Boolean = uri in available
}
