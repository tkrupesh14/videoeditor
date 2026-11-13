package dev.videoeditor.timeline.query

import dev.videoeditor.timeline.host.AssetTranscript
import dev.videoeditor.timeline.model.MediaClip
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.model.TrackKind
import dev.videoeditor.timeline.model.endUs
import kotlinx.serialization.Serializable

@Serializable
data class TimelineTranscriptEntry(
    val clipId: String,
    val startUs: Long,
    val endUs: Long,
    val text: String,
)

@Serializable
data class TimelineTranscript(
    val entries: List<TimelineTranscriptEntry>,
    val missingAssetIds: List<String> = emptyList(),
)

/** Maps asset (source-time) transcripts through the clips to timeline time (design section 6.5). */
object TranscriptMapper {
    /** Asset ids used by media clips on VIDEO or AUDIO tracks, in first-use order. */
    fun transcribableAssetIds(project: Project): List<String> =
        project.tracks
            .filter { it.kind == TrackKind.VIDEO || it.kind == TrackKind.AUDIO }
            .flatMap { t -> t.clips.filterIsInstance<MediaClip>().map { it.assetId } }
            .distinct()

    fun toTimeline(
        project: Project,
        transcripts: Map<String, AssetTranscript>,
        wordLevel: Boolean = false,
        fromUs: Long? = null,
        toUs: Long? = null,
    ): TimelineTranscript {
        val entries = mutableListOf<TimelineTranscriptEntry>()
        for (track in project.tracks) {
            if (track.kind != TrackKind.VIDEO && track.kind != TrackKind.AUDIO) continue
            for (clip in track.clips) {
                if (clip !is MediaClip) continue
                val tr = transcripts[clip.assetId] ?: continue
                entries += mapClip(clip, tr, wordLevel)
            }
        }
        val filtered =
            entries
                .filter { (fromUs == null || it.endUs > fromUs) && (toUs == null || it.startUs < toUs) }
                .sortedWith(compareBy({ it.startUs }, { it.clipId }))
        val missing = transcribableAssetIds(project).filter { it !in transcripts }
        return TimelineTranscript(filtered, missing)
    }

    private fun mapClip(
        clip: MediaClip,
        tr: AssetTranscript,
        wordLevel: Boolean,
    ): List<TimelineTranscriptEntry> {
        val sIn = clip.sourceInUs
        val sOut = clip.sourceOutUs
        val k = clip.speedCenti

        fun toTimeline(sourceUs: Long): Long =
            (clip.startUs + Math.floorDiv((sourceUs - sIn) * 100, k.toLong())).coerceIn(clip.startUs, clip.endUs)
        val out = mutableListOf<TimelineTranscriptEntry>()
        for (seg in tr.segments) {
            if (seg.words.isEmpty()) {
                if (wordLevel) continue
                if (seg.startUs < sOut && sIn < seg.endUs) {
                    val st = toTimeline(maxOf(seg.startUs, sIn))
                    val en = toTimeline(minOf(seg.endUs, sOut))
                    if (en > st) out += TimelineTranscriptEntry(clip.id, st, en, seg.text)
                }
                continue
            }
            val words = seg.words.filter { it.startUs in sIn until sOut }
            if (words.isEmpty()) continue
            if (wordLevel) {
                words.forEach { w ->
                    out +=
                        TimelineTranscriptEntry(clip.id, toTimeline(w.startUs), toTimeline(w.endUs), w.text)
                }
            } else {
                out +=
                    TimelineTranscriptEntry(
                        clip.id,
                        toTimeline(words.first().startUs),
                        toTimeline(words.last().endUs),
                        words.joinToString(" ") { it.text.trim() },
                    )
            }
        }
        return out
    }
}
