package dev.videoeditor.core.render

import dev.videoeditor.timeline.host.ExportRequest
import dev.videoeditor.timeline.host.ExportState
import dev.videoeditor.timeline.host.ExportStatus
import dev.videoeditor.timeline.model.Limits
import dev.videoeditor.timeline.model.OutputSettings

/** Resolved, validated encoder settings for one export. Pure; see [ExportSettings.resolve]. */
data class ExportSettings(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val fileName: String,
    val videoMimeType: String = VIDEO_MIME_H264,
    val audioMimeType: String = AUDIO_MIME_AAC,
) {
    companion object {
        const val VIDEO_MIME_H264 = "video/avc"
        const val AUDIO_MIME_AAC = "audio/mp4a-latm"
        private const val MAX_NAME_CHARS = 100

        /**
         * Request values override the project's [OutputSettings]. Sizes are clamped to the model limits and
         * rounded down to even numbers (H.264 encoders reject odd dimensions).
         */
        fun resolve(
            output: OutputSettings,
            request: ExportRequest,
            jobId: String,
        ): ExportSettings {
            val w = evenDim(request.width ?: output.width)
            val h = evenDim(request.height ?: output.height)
            val fps = (request.frameRate ?: output.frameRate).coerceIn(Limits.MIN_FPS, Limits.MAX_FPS)
            return ExportSettings(w, h, fps, sanitizeFileName(request.fileName, jobId))
        }

        private fun evenDim(v: Int): Int {
            val c = v.coerceIn(Limits.MIN_OUTPUT_DIM, Limits.MAX_OUTPUT_DIM)
            return c - (c % 2)
        }

        /** Keeps `[A-Za-z0-9._-]`, strips any path, forces a `.mp4` extension. Falls back to `export-<jobId>.mp4`. */
        fun sanitizeFileName(
            raw: String?,
            jobId: String,
        ): String {
            val base =
                raw
                    ?.substringAfterLast('/')
                    ?.substringAfterLast('\\')
                    ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    ?.trimStart('.')
                    ?.removeSuffix(".mp4")
                    ?.removeSuffix(".MP4")
                    ?.take(MAX_NAME_CHARS)
                    ?.takeIf { it.isNotBlank() && it.any(Char::isLetterOrDigit) }
                    ?: "export-${jobId.replace(Regex("[^A-Za-z0-9_-]"), "_")}"
            return "$base.mp4"
        }
    }
}

/** Typed export failure. [code] is the Media3 `ExportException.errorCode` when one exists. */
sealed class ExportError(
    val message: String,
    val code: Int? = null,
) {
    class EmptyProject : ExportError("Project has no renderable media")

    class InvalidRequest(
        message: String,
    ) : ExportError(message)

    class Output(
        message: String,
    ) : ExportError(message)

    class Io(
        message: String,
        code: Int?,
    ) : ExportError(message, code)

    class Decoding(
        message: String,
        code: Int?,
    ) : ExportError(message, code)

    class Encoding(
        message: String,
        code: Int?,
    ) : ExportError(message, code)

    class Processing(
        message: String,
        code: Int?,
    ) : ExportError(message, code)

    class Muxing(
        message: String,
        code: Int?,
    ) : ExportError(message, code)

    class Unknown(
        message: String,
        code: Int?,
    ) : ExportError(message, code)

    override fun toString(): String = "${this::class.simpleName}(code=$code, message=$message)"

    companion object {
        /** Maps a Media3 `ExportException.errorCode` (grouped by thousands in Media3 1.11) to a typed error. */
        fun fromMedia3Code(
            code: Int,
            message: String?,
        ): ExportError {
            val msg = message ?: "Export failed (code $code)"
            return when (code / 1000) {
                2 -> Io(msg, code)
                3 -> Decoding(msg, code)
                4 -> Encoding(msg, code)
                5, 6 -> Processing(msg, code)
                7 -> Muxing(msg, code)
                else -> Unknown(msg, code)
            }
        }
    }
}

/** One progress observation for an export job. [fraction] is null while Media3 cannot estimate progress. */
data class ExportProgress(
    val jobId: String,
    val state: ExportState,
    val fraction: Double? = null,
    val outputUri: String? = null,
    val error: ExportError? = null,
) {
    val isTerminal: Boolean
        get() = state == ExportState.DONE || state == ExportState.FAILED || state == ExportState.CANCELLED

    fun toStatus(): ExportStatus =
        ExportStatus(
            jobId = jobId,
            state = state,
            progress =
                when (state) {
                    ExportState.DONE -> 1.0
                    else -> fraction ?: 0.0
                },
            outputUri = outputUri,
            error = error?.toString(),
        )
}

/** Mirror of Media3 `Transformer.PROGRESS_STATE_*` so the mapping stays JVM-testable. */
enum class TransformerProgressState { NOT_STARTED, WAITING_FOR_AVAILABILITY, AVAILABLE, UNAVAILABLE }

object ProgressMapper {
    /**
     * Folds a polled Transformer progress into [previous]. Terminal states are sticky; the fraction never goes
     * backwards; a running export is capped at 0.99 (only completion reports 1.0).
     */
    fun map(
        previous: ExportProgress,
        state: TransformerProgressState,
        percent: Int,
    ): ExportProgress {
        if (previous.isTerminal) return previous
        return when (state) {
            TransformerProgressState.NOT_STARTED -> previous
            TransformerProgressState.WAITING_FOR_AVAILABILITY,
            TransformerProgressState.UNAVAILABLE,
            -> previous.copy(state = ExportState.RUNNING)
            TransformerProgressState.AVAILABLE -> {
                val f = (percent.coerceIn(0, 100) / 100.0).coerceAtMost(MAX_RUNNING_FRACTION)
                previous.copy(state = ExportState.RUNNING, fraction = maxOf(previous.fraction ?: 0.0, f))
            }
        }
    }

    const val MAX_RUNNING_FRACTION = 0.99
}
