package dev.videoeditor.core.ai.analysis

import dev.videoeditor.timeline.host.AssetTranscript
import dev.videoeditor.timeline.host.TranscriptService

/** Outcome of asking for a transcript. Never a made-up transcript. */
sealed interface TranscriptOutcome {
    data class Available(
        val transcript: AssetTranscript,
    ) : TranscriptOutcome

    data class Unavailable(
        val reason: String,
    ) : TranscriptOutcome
}

/** Speech-to-text extension point. The app plugs in a real provider; until then [NoTranscriptAvailable]. */
interface TranscriptHook {
    /** Transcript of [assetId] in the asset's own (source) time. */
    suspend fun transcribe(assetId: String): TranscriptOutcome
}

/** The default hook: there is no speech-to-text provider, and it says so instead of pretending. */
object NoTranscriptAvailable : TranscriptHook {
    const val MESSAGE: String = "No speech-to-text provider configured"

    override suspend fun transcribe(assetId: String): TranscriptOutcome = TranscriptOutcome.Unavailable(MESSAGE)
}

/** Adapts a hook to the timeline's [TranscriptService]; unavailable transcripts become null. */
fun TranscriptHook.asTranscriptService(): TranscriptService =
    object : TranscriptService {
        override suspend fun transcript(assetId: String): AssetTranscript? =
            (transcribe(assetId) as? TranscriptOutcome.Available)?.transcript
    }
