package dev.videoeditor.app.ai

import dev.videoeditor.core.ai.analysis.LumaFrame
import dev.videoeditor.core.ai.analysis.NoTranscriptAvailable
import dev.videoeditor.core.ai.analysis.SceneChangeDetector
import dev.videoeditor.core.ai.analysis.SilenceDetector
import dev.videoeditor.core.ai.assistant.AnalysisBundle
import dev.videoeditor.core.ai.assistant.Keyframe
import dev.videoeditor.core.ai.assistant.Keyframes
import dev.videoeditor.timeline.model.MediaAsset
import dev.videoeditor.timeline.model.MediaClip
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.model.TimeRange
import dev.videoeditor.timeline.model.TrackKind
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Decoded mono 16-bit PCM. [startUs] is the source time of the first sample. */
class PcmAudio(
    val samples: ShortArray,
    val sampleRate: Int,
    val startUs: Long,
)

/**
 * Platform media access the analyzer needs. The Android implementation ([AndroidMediaDecoder]) uses MediaExtractor +
 * MediaCodec and MediaMetadataRetriever; tests use fakes. Implementations must be cancellable.
 */
interface MediaDecoder {
    /** Audio of [uri] between source times [fromUs] and [toUs], or null when there is no audio track. */
    suspend fun decodeAudio(
        uri: String,
        fromUs: Long,
        toUs: Long,
    ): PcmAudio?

    /** A small grayscale frame near source time [timeUs], or null when none can be decoded. */
    suspend fun lumaFrame(
        uri: String,
        timeUs: Long,
    ): LumaFrame?

    /** A JPEG re-encoded from a decoded bitmap (no EXIF) with long edge <= [maxEdge], or null. */
    suspend fun jpegFrame(
        uri: String,
        timeUs: Long,
        maxEdge: Int,
    ): ByteArray?
}

/** Produces the [AnalysisBundle] inputs and the silences for "Remove silences". Off the main thread, cancellable. */
interface MediaAnalysisPort {
    suspend fun analyze(
        project: Project,
        includeKeyframes: Boolean,
        onProgress: (Float) -> Unit = {},
    ): AnalysisBundle

    /** Timeline ranges where every audible clip is silent. */
    suspend fun silences(
        project: Project,
        onProgress: (Float) -> Unit = {},
    ): List<TimeRange>
}

/**
 * Pure analysis over a [MediaDecoder]: silences (per clip, mapped to timeline time and combined across tracks),
 * scene changes (sampled frames per video clip) and optional keyframes. The transcript is a stub: there is no
 * on-device speech-to-text yet, so the bundle carries [NoTranscriptAvailable.MESSAGE].
 */
class ProjectAnalyzer(
    private val decoder: MediaDecoder,
    private val silenceDetector: SilenceDetector = SilenceDetector(),
    private val sceneDetector: SceneChangeDetector = SceneChangeDetector(),
    private val frameStepUs: Long = 500_000,
    private val maxFramesPerClip: Int = 240,
) : MediaAnalysisPort {
    private data class SourceKey(
        val uri: String,
        val fromUs: Long,
        val toUs: Long,
    )

    private val silenceCache = mutableMapOf<SourceKey, List<TimeRange>>()
    private val sceneCache = mutableMapOf<SourceKey, List<Long>>()

    override suspend fun analyze(
        project: Project,
        includeKeyframes: Boolean,
        onProgress: (Float) -> Unit,
    ): AnalysisBundle {
        val silences = silences(project) { onProgress(it * 0.5f) }
        val scenes = sceneChanges(project) { onProgress(0.5f + it * 0.4f) }
        val keyframes = if (includeKeyframes) keyframes(project, scenes) else emptyList()
        onProgress(1f)
        return AnalysisBundle(
            transcriptUnavailableReason = NoTranscriptAvailable.MESSAGE,
            silences = silences,
            sceneChangesUs = scenes,
            keyframes = keyframes,
        )
    }

    override suspend fun silences(
        project: Project,
        onProgress: (Float) -> Unit,
    ): List<TimeRange> {
        val audible = audibleClips(project)
        val perClip = mutableMapOf<String, List<TimeRange>>()
        audible.forEachIndexed { i, (clip, asset) ->
            currentCoroutineContext().ensureActive()
            val key = SourceKey(asset.uri, clip.sourceInUs, clip.sourceOutUs)
            val source =
                silenceCache[key] ?: run {
                    val pcm = decoder.decodeAudio(asset.uri, clip.sourceInUs, clip.sourceOutUs)
                    val r =
                        if (pcm == null || pcm.samples.isEmpty()) {
                            emptyList()
                        } else {
                            silenceDetector.detect(pcm.samples, pcm.sampleRate, pcm.startUs)
                        }
                    r.also { silenceCache[key] = it }
                }
            perClip[clip.id] = source.mapNotNull { TimelineMapping.sourceToTimeline(clip, it) }
            onProgress((i + 1f) / audible.size)
        }
        return TimelineMapping.combineSilences(audible.map { it.first }, perClip)
    }

    private suspend fun sceneChanges(
        project: Project,
        onProgress: (Float) -> Unit,
    ): List<Long> {
        val clips = videoClips(project)
        val out = sortedSetOf<Long>()
        clips.forEachIndexed { i, (clip, asset) ->
            val key = SourceKey(asset.uri, clip.sourceInUs, clip.sourceOutUs)
            val source =
                sceneCache[key] ?: run {
                    val frames = mutableListOf<LumaFrame>()
                    for (t in TimelineMapping.sampleTimes(
                        clip.sourceInUs,
                        clip.sourceOutUs,
                        frameStepUs,
                        maxFramesPerClip,
                    )) {
                        currentCoroutineContext().ensureActive()
                        decoder.lumaFrame(asset.uri, t)?.let { frames += LumaFrame(t, it.width, it.height, it.luma) }
                    }
                    sceneDetector.detect(frames).also { sceneCache[key] = it }
                }
            source.forEach { s -> TimelineMapping.sourceTimeToTimeline(clip, s)?.let { out += it } }
            onProgress((i + 1f) / clips.size)
        }
        return out.toList()
    }

    private suspend fun keyframes(
        project: Project,
        scenes: List<Long>,
    ): List<Keyframe> {
        val clips = videoClips(project)
        val times = (clips.map { it.first.startUs } + scenes).distinct().sorted()
        val picked = TimelineMapping.spread(times, Keyframes.MAX_PER_REQUEST)
        return picked.mapNotNull { t ->
            currentCoroutineContext().ensureActive()
            val (clip, asset) =
                clips.lastOrNull { (c, _) -> t >= c.startUs && t < c.startUs + c.durationUs }
                    ?: return@mapNotNull null
            val src = clip.sourceInUs + (t - clip.startUs) * clip.speedCenti / 100
            decoder.jpegFrame(asset.uri, src, Keyframes.MAX_LONG_EDGE_PX)?.let { Keyframe(t, it) }
        }
    }

    companion object {
        /** Media clips whose asset has audio and that are not muted. */
        fun audibleClips(project: Project): List<Pair<MediaClip, MediaAsset>> =
            project.tracks
                .filter { it.kind == TrackKind.VIDEO || it.kind == TrackKind.AUDIO || it.kind == TrackKind.OVERLAY }
                .flatMap { it.clips }
                .filterIsInstance<MediaClip>()
                .mapNotNull { c -> project.asset(c.assetId)?.takeIf { it.hasAudio && c.volume > 0.0 }?.let { c to it } }

        fun videoClips(project: Project): List<Pair<MediaClip, MediaAsset>> =
            project.tracks
                .filter { it.kind == TrackKind.VIDEO }
                .flatMap { it.clips }
                .filterIsInstance<MediaClip>()
                .mapNotNull { c -> project.asset(c.assetId)?.takeIf { it.hasVideo }?.let { c to it } }
                .sortedBy { it.first.startUs }
    }
}

/** Pure time mapping between source and timeline, and the cross-track silence combination. */
object TimelineMapping {
    fun sourceTimeToTimeline(
        clip: MediaClip,
        sourceUs: Long,
    ): Long? {
        if (sourceUs < clip.sourceInUs || sourceUs > clip.sourceOutUs) return null
        return (clip.startUs + (sourceUs - clip.sourceInUs) * 100 / clip.speedCenti).coerceAtMost(
            clip.startUs + clip.durationUs,
        )
    }

    /** The part of source range [r] inside [clip], in timeline time; null when it lies outside the clip. */
    fun sourceToTimeline(
        clip: MediaClip,
        r: TimeRange,
    ): TimeRange? {
        val s = maxOf(r.startUs, clip.sourceInUs)
        val e = minOf(r.endUs, clip.sourceOutUs)
        if (e <= s) return null
        val ts = sourceTimeToTimeline(clip, s) ?: return null
        val te = sourceTimeToTimeline(clip, e) ?: return null
        return if (te > ts) TimeRange(ts, te) else null
    }

    /**
     * Timeline ranges covered by at least one audible clip where every audible clip covering them is silent.
     * Gaps with no audible clip are not reported (removing them is a different edit).
     */
    fun combineSilences(
        clips: List<MediaClip>,
        silentByClip: Map<String, List<TimeRange>>,
    ): List<TimeRange> {
        if (clips.isEmpty()) return emptyList()
        val points = sortedSetOf<Long>()
        for (c in clips) {
            points += c.startUs
            points += c.startUs + c.durationUs
            silentByClip[c.id].orEmpty().forEach {
                points += it.startUs
                points += it.endUs
            }
        }
        val p = points.toList()
        val out = mutableListOf<TimeRange>()
        for (i in 0 until p.size - 1) {
            val a = p[i]
            val b = p[i + 1]
            val covering = clips.filter { it.startUs <= a && it.startUs + it.durationUs >= b }
            if (covering.isEmpty()) continue
            val silent =
                covering.all { c -> silentByClip[c.id].orEmpty().any { it.startUs <= a && it.endUs >= b } }
            if (!silent) continue
            val last = out.lastOrNull()
            if (last != null &&
                last.endUs == a
            ) {
                out[out.lastIndex] = TimeRange(last.startUs, b)
            } else {
                out += TimeRange(a, b)
            }
        }
        return out
    }

    /** Evenly spaced sample times in [fromUs, toUs), at most [max]. */
    fun sampleTimes(
        fromUs: Long,
        toUs: Long,
        stepUs: Long,
        max: Int,
    ): List<Long> {
        if (toUs <= fromUs || max <= 0) return emptyList()
        val span = toUs - fromUs
        val step = maxOf(stepUs, (span + max - 1) / max)
        return generateSequence(fromUs) { it + step }.takeWhile { it < toUs }.toList()
    }

    /** At most [n] items spread evenly over [items], keeping the first. */
    fun <T> spread(
        items: List<T>,
        n: Int,
    ): List<T> {
        if (items.size <= n) return items
        return (0 until n).map { items[(it.toLong() * items.size / n).toInt()] }
    }
}
