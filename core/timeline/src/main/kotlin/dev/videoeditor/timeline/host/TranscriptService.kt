package dev.videoeditor.timeline.host

import kotlinx.serialization.Serializable

@Serializable
data class TranscriptWord(
    val startUs: Long,
    val endUs: Long,
    val text: String,
)

@Serializable
data class TranscriptSegment(
    val startUs: Long,
    val endUs: Long,
    val text: String,
    val words: List<TranscriptWord> = emptyList(),
)

/** A transcript in the asset's own (source) time. */
@Serializable
data class AssetTranscript(
    val assetId: String,
    val language: String? = null,
    val segments: List<TranscriptSegment>,
)

/** Implemented by the app (on-device or user-configured transcription). */
interface TranscriptService {
    /** Transcript in the asset's own (source) time, or null if none is available. */
    suspend fun transcript(assetId: String): AssetTranscript?
}
