package dev.videoeditor.timeline.query

import dev.videoeditor.timeline.model.CaptionClip
import dev.videoeditor.timeline.model.Clip
import dev.videoeditor.timeline.model.MediaAsset
import dev.videoeditor.timeline.model.MediaClip
import dev.videoeditor.timeline.model.OutputSettings
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.model.Track
import dev.videoeditor.timeline.model.TrackKind
import dev.videoeditor.timeline.model.endUs
import kotlinx.serialization.Serializable

@Serializable
data class TrackSummary(
    val id: String,
    val kind: TrackKind,
    val name: String,
    val clipCount: Int,
    val endUs: Long,
)

@Serializable
data class ProjectSummary(
    val id: String,
    val name: String,
    val durationUs: Long,
    val output: OutputSettings,
    val tracks: List<TrackSummary>,
    val assets: List<MediaAsset>,
)

@Serializable
data class ClipView(
    val id: String,
    val trackId: String,
    val trackKind: TrackKind,
    val startUs: Long,
    val endUs: Long,
    val durationUs: Long,
    val assetId: String? = null,
    val sourceInUs: Long? = null,
    val sourceOutUs: Long? = null,
    val speed: Double? = null,
    val volume: Double? = null,
    val text: String? = null,
)

/** Read-only views of a project, used by the UI, the AI tools and the MCP server. */
object Queries {
    fun summary(project: Project): ProjectSummary =
        ProjectSummary(
            id = project.id,
            name = project.name,
            durationUs = project.durationUs,
            output = project.output,
            tracks = project.tracks.map { TrackSummary(it.id, it.kind, it.name, it.clips.size, it.endUs) },
            assets = project.assets,
        )

    /** Clips intersecting [fromUs, toUs) (open-ended when null), in track then start order. */
    fun listClips(
        project: Project,
        trackId: String? = null,
        fromUs: Long? = null,
        toUs: Long? = null,
    ): List<ClipView> =
        project.tracks
            .filter { trackId == null || it.id == trackId }
            .flatMap { t ->
                t.clips
                    .filter { (fromUs == null || it.endUs > fromUs) && (toUs == null || it.startUs < toUs) }
                    .map { view(t, it) }
            }

    fun view(
        track: Track,
        clip: Clip,
    ): ClipView =
        when (clip) {
            is MediaClip ->
                ClipView(
                    clip.id,
                    track.id,
                    track.kind,
                    clip.startUs,
                    clip.endUs,
                    clip.durationUs,
                    assetId = clip.assetId,
                    sourceInUs = clip.sourceInUs,
                    sourceOutUs = clip.sourceOutUs,
                    speed = clip.speed,
                    volume = clip.volume,
                )
            is CaptionClip ->
                ClipView(clip.id, track.id, track.kind, clip.startUs, clip.endUs, clip.durationUs, text = clip.text)
        }

    /** The clip on [trackId] covering [timeUs] (`startUs <= timeUs < endUs`), or null. */
    fun clipAt(
        project: Project,
        trackId: String,
        timeUs: Long,
    ): Clip? = project.track(trackId)?.clips?.firstOrNull { it.startUs <= timeUs && timeUs < it.endUs }

    /** Clips with startUs < timeUs < endUs, in track then start order. */
    fun clipsAt(
        project: Project,
        timeUs: Long,
    ): List<Clip> = project.tracks.flatMap { t -> t.clips.filter { it.startUs < timeUs && timeUs < it.endUs } }
}
