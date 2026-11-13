package dev.videoeditor.timeline.host

import dev.videoeditor.timeline.model.Project
import kotlinx.serialization.Serializable

/** Export parameters. Null size or frame rate means the project's output settings. */
@Serializable
data class ExportRequest(
    val fileName: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Int? = null,
)

@Serializable
enum class ExportState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

@Serializable
data class ExportStatus(
    val jobId: String,
    val state: ExportState,
    /** 0..1 */
    val progress: Double = 0.0,
    val outputUri: String? = null,
    val error: String? = null,
)

/** Implemented by the app (Media3 export). Sanitizing [ExportRequest.fileName] and the output location are its job. */
interface ExportService {
    suspend fun startExport(
        project: Project,
        request: ExportRequest,
    ): ExportStatus

    suspend fun exportStatus(jobId: String): ExportStatus?
}
