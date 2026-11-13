package dev.videoeditor.core.media

import dev.videoeditor.timeline.model.MediaAsset

/** What a probe learned about a media file. Sizes are display sizes (rotation metadata already applied). */
data class ProbeResult(
    val uri: String,
    val durationUs: Long,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val width: Int?,
    val height: Int?,
    val frameRate: Double?,
    val rotationDegrees: Int,
    val mimeType: String?,
    val displayName: String?,
) {
    fun toAsset(id: String): MediaAsset =
        MediaAsset(
            id = id,
            uri = uri,
            durationUs = durationUs,
            hasVideo = hasVideo,
            hasAudio = hasAudio,
            width = width,
            height = height,
            frameRate = frameRate,
            displayName = displayName,
        )
}

class MediaProbeException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Raw retriever/extractor values (all optional strings/numbers as Android hands them over). Pure. */
data class RawProbe(
    val durationMs: String?,
    val hasVideo: String?,
    val hasAudio: String?,
    val width: String?,
    val height: String?,
    val rotation: String?,
    val mimeType: String?,
    /** Longest track duration from MediaExtractor (KEY_DURATION, us), used when the retriever has none. */
    val extractorDurationUs: Long? = null,
    /** KEY_FRAME_RATE of the first video track, if present. */
    val extractorFrameRate: Number? = null,
    /** METADATA_KEY_CAPTURE_FRAMERATE, a fallback. */
    val captureFrameRate: String? = null,
    val extractorHasVideo: Boolean = false,
    val extractorHasAudio: Boolean = false,
)

object ProbeParser {
    /** Builds a [ProbeResult]; throws [MediaProbeException] when the file has no usable stream or duration. */
    fun parse(
        uri: String,
        raw: RawProbe,
        displayName: String?,
    ): ProbeResult {
        val hasVideo = raw.hasVideo.isYes() || raw.extractorHasVideo
        val hasAudio = raw.hasAudio.isYes() || raw.extractorHasAudio
        if (!hasVideo && !hasAudio) throw MediaProbeException("No audio or video stream in $uri")
        val durationUs =
            raw.durationMs
                ?.trim()
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.times(1000)
                ?: raw.extractorDurationUs?.takeIf { it > 0 }
                ?: throw MediaProbeException("Unknown duration for $uri")
        val rotation = normalizeRotation(raw.rotation?.trim()?.toIntOrNull() ?: 0)
        var w =
            raw.width
                ?.trim()
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
        var h =
            raw.height
                ?.trim()
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
        if (rotation == 90 || rotation == 270) {
            val t = w
            w = h
            h = t
        }
        val fps =
            raw.extractorFrameRate?.toDouble()?.takeIf { it.isFinite() && it > 0 }
                ?: raw.captureFrameRate
                    ?.trim()
                    ?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it > 0 }
        return ProbeResult(
            uri = uri,
            durationUs = durationUs,
            hasVideo = hasVideo,
            hasAudio = hasAudio,
            width = if (hasVideo) w else null,
            height = if (hasVideo) h else null,
            frameRate = if (hasVideo) fps else null,
            rotationDegrees = rotation,
            mimeType = raw.mimeType,
            displayName = displayName,
        )
    }

    fun normalizeRotation(deg: Int): Int = ((deg % 360) + 360) % 360

    private fun String?.isYes(): Boolean = this?.trim().equals("yes", ignoreCase = true)
}
