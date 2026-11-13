package dev.videoeditor.app.editor

import dev.videoeditor.timeline.command.ImportMedia
import dev.videoeditor.timeline.host.ExportState
import dev.videoeditor.timeline.model.Project
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * Seams between EditorViewModel and Android/Media3 so the ViewModel is JVM-unit-testable with fakes. The Android
 * implementations live in AndroidPorts.kt.
 */

data class PlaybackState(
    val isPlaying: Boolean = false,
    val positionUs: Long = 0L,
    val durationUs: Long = 0L,
    val error: String? = null,
)

/** Preview player. [setProject] is keyed: the same key twice is a no-op, a new key rebuilds the composition. */
interface PreviewPort {
    val state: StateFlow<PlaybackState>

    fun setProject(
        project: Project,
        key: Long,
    )

    fun play()

    fun pause()

    fun seekTo(positionUs: Long)

    fun release()
}

data class ExportUpdate(
    val state: ExportState,
    val fraction: Double? = null,
    val outputPath: String? = null,
    val error: String? = null,
) {
    val isTerminal: Boolean
        get() = state == ExportState.DONE || state == ExportState.FAILED || state == ExportState.CANCELLED
}

interface ExportPort {
    /** Starts an export and returns its job id. */
    suspend fun start(
        project: Project,
        fileName: String,
    ): String

    fun progress(jobId: String): Flow<ExportUpdate>

    suspend fun cancel(jobId: String)
}

/** The current project's JSON in app-private storage. */
interface ProjectStore {
    suspend fun load(): String?

    suspend fun save(json: String)
}

sealed interface ImportOutcome {
    data class Ready(
        val command: ImportMedia,
    ) : ImportOutcome

    /** A kind of media the editor cannot use yet (e.g. still images). */
    data class Unsupported(
        val message: String,
    ) : ImportOutcome

    data class Failed(
        val message: String,
    ) : ImportOutcome
}

fun interface MediaImporter {
    suspend fun probe(uri: String): ImportOutcome
}

/** Whether an asset uri can still be opened on this device. */
fun interface MediaAvailability {
    suspend fun isAvailable(uri: String): Boolean
}
