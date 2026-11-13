package dev.videoeditor.timeline.gen

import dev.videoeditor.timeline.command.AddCaption
import dev.videoeditor.timeline.command.AddClip
import dev.videoeditor.timeline.command.AddTrack
import dev.videoeditor.timeline.command.ApplyCuts
import dev.videoeditor.timeline.command.Batch
import dev.videoeditor.timeline.command.CaptionSpec
import dev.videoeditor.timeline.command.ClipEdge
import dev.videoeditor.timeline.command.Command
import dev.videoeditor.timeline.command.DeleteClips
import dev.videoeditor.timeline.command.EditCaption
import dev.videoeditor.timeline.command.ImportMedia
import dev.videoeditor.timeline.command.MoveClip
import dev.videoeditor.timeline.command.RemoveTrack
import dev.videoeditor.timeline.command.RippleDeleteRange
import dev.videoeditor.timeline.command.SetClipProperties
import dev.videoeditor.timeline.command.SetProjectSettings
import dev.videoeditor.timeline.command.SplitClip
import dev.videoeditor.timeline.command.TrimClip
import dev.videoeditor.timeline.model.CaptionClip
import dev.videoeditor.timeline.model.CaptionStylePatch
import dev.videoeditor.timeline.model.Clip
import dev.videoeditor.timeline.model.Limits
import dev.videoeditor.timeline.model.MediaAsset
import dev.videoeditor.timeline.model.MediaClip
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.model.TimeRange
import dev.videoeditor.timeline.model.Track
import dev.videoeditor.timeline.model.TrackKind
import dev.videoeditor.timeline.model.Transform
import dev.videoeditor.timeline.model.TransformPatch
import dev.videoeditor.timeline.model.endUs
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import kotlin.random.Random

const val MIN = Limits.MIN_CLIP_DURATION_US

/** A valid project plus a seed for deriving commands from it. */
data class Scenario(
    val project: Project,
    val seed: Long,
) {
    val random: Random get() = Random(seed)
}

object Arbs {
    private val SPEEDS = listOf(10, 25, 50, 75, 100, 150, 200, 333, 1000)

    fun project(): Arb<Project> = arbitrary { rs -> genProject(rs.random) }

    fun scenario(): Arb<Scenario> = arbitrary { rs -> Scenario(genProject(rs.random), rs.random.nextLong()) }

    fun genProject(r: Random): Project {
        var next = 1L

        fun id(prefix: String) = "${prefix}_${next++}"
        val assets =
            List(r.nextInt(0, 5)) {
                val video = r.nextBoolean()
                val audio = !video || r.nextBoolean()
                val dur =
                    when (r.nextInt(4)) {
                        0 -> MIN * 2
                        1 -> r.nextLong(MIN, 200_000)
                        else -> r.nextLong(MIN, 30_000_000)
                    }
                MediaAsset(
                    id("asset"),
                    "content://media/${r.nextInt(1_000_000)}",
                    dur,
                    video,
                    audio,
                    if (video) 1920 else null,
                    if (video) 1080 else null,
                    if (r.nextBoolean()) 29.97 else null,
                    if (r.nextBoolean()) "file${r.nextInt(100)}.mp4" else null,
                )
            }
        val tracks =
            List(r.nextInt(0, 7)) {
                val kind = TrackKind.entries[r.nextInt(TrackKind.entries.size)]
                val trackId = id("track")
                val compatible =
                    assets.filter {
                        when (kind) {
                            TrackKind.VIDEO, TrackKind.OVERLAY -> it.hasVideo
                            TrackKind.AUDIO -> it.hasAudio
                            TrackKind.CAPTION -> false
                        }
                    }
                val clips = mutableListOf<Clip>()
                var cursor = if (r.nextBoolean()) 0L else r.nextLong(0, 3_000_000)
                repeat(r.nextInt(0, 6)) {
                    val clip =
                        if (kind == TrackKind.CAPTION) {
                            val d = if (r.nextInt(4) == 0) MIN else r.nextLong(MIN, 4_000_000)
                            CaptionClip(id("clip"), cursor, d, "caption ${r.nextInt(1000)}")
                        } else {
                            if (compatible.isEmpty()) return@repeat
                            genMediaClip(r, compatible.random(r), cursor) { id("clip") } ?: return@repeat
                        }
                    clips += clip
                    cursor = clip.endUs + if (r.nextBoolean()) 0L else r.nextLong(0, 2_000_000)
                }
                Track(trackId, kind, "${kind.name.lowercase()} $it", clips)
            }
        return Project(id = "p", name = "Generated", nextId = next, assets = assets, tracks = tracks)
    }

    private fun genMediaClip(
        r: Random,
        asset: MediaAsset,
        start: Long,
        id: () -> String,
    ): MediaClip? {
        val k = if (r.nextInt(3) == 0) r.nextInt(10, 1001) else SPEEDS.random(r)
        val maxD = ((asset.durationUs + 1) * 100 - 1) / k
        if (maxD < MIN) return null
        val d =
            when (r.nextInt(5)) {
                0 -> MIN
                1 -> maxD
                else -> r.nextLong(MIN, minOf(maxD, 6_000_000) + 1)
            }
        val need = d * k / 100
        val maxIn = asset.durationUs - need
        val sIn =
            when (r.nextInt(4)) {
                0 -> 0L
                1 -> maxIn
                else -> r.nextLong(0, maxIn + 1)
            }
        val volume = listOf(0.0, 0.5, 1.0, 2.0).random(r)
        return MediaClip(id(), asset.id, start, d, sIn, k / 100.0, volume, Transform())
    }

    private fun allClips(p: Project): List<Clip> = p.tracks.flatMap { it.clips }

    /** Interesting time values for [p]: clip boundaries and their neighbours, plus random times. */
    fun time(
        r: Random,
        p: Project,
    ): Long {
        val clips = allClips(p)
        if (clips.isEmpty() || r.nextInt(5) == 0) {
            return listOf(
                0L,
                -1L,
                MIN,
                r.nextLong(0, maxOf(1, p.durationUs + 2_000_000)),
                Limits.MAX_TIMELINE_US,
            ).random(r)
        }
        val c = clips.random(r)
        val base = if (r.nextBoolean()) c.startUs else c.endUs
        return base +
            listOf(0L, 1L, -1L, MIN, -MIN, MIN - 1, -(MIN - 1), r.nextLong(-c.durationUs, c.durationUs + 1)).random(r)
    }

    private fun clipIds(
        r: Random,
        p: Project,
    ): List<String> {
        val ids = allClips(p).map { it.id }
        if (ids.isEmpty() ||
            r.nextInt(10) == 0
        ) {
            return listOf(if (r.nextBoolean()) "clip_999999" else ids.firstOrNull() ?: "nope")
        }
        if (r.nextInt(20) == 0) return emptyList()
        return List(r.nextInt(1, 4)) { ids.random(r) }
    }

    private fun clipId(
        r: Random,
        p: Project,
    ): String = clipIds(r, p).firstOrNull() ?: "clip_404"

    private fun trackId(
        r: Random,
        p: Project,
    ): String = if (p.tracks.isEmpty() || r.nextInt(10) == 0) "track_404" else p.tracks.random(r).id

    fun cutRanges(
        r: Random,
        p: Project,
    ): List<TimeRange> =
        List(r.nextInt(1, 5)) {
            val a = time(r, p).coerceAtLeast(0)
            val len =
                when (r.nextInt(4)) {
                    0 -> 1L
                    1 -> MIN
                    else -> r.nextLong(1, 3_000_000)
                }
            TimeRange(a, a + len)
        }

    /** A command for [p]: mostly plausible, sometimes adversarial. */
    fun command(
        r: Random,
        p: Project,
        allowBatch: Boolean = true,
    ): Command =
        when (r.nextInt(if (allowBatch) 16 else 15)) {
            0 ->
                ImportMedia(
                    "content://x/${r.nextInt(5)}",
                    r.nextLong(-1, 20_000_000),
                    r.nextBoolean(),
                    true,
                    1280,
                    720,
                )
            1 ->
                AddTrack(
                    TrackKind.entries.random(r),
                    index =
                        if (r.nextBoolean()) {
                            null
                        } else {
                            r.nextInt(
                                -1,
                                p.tracks.size + 2,
                            )
                        },
                )
            2 -> RemoveTrack(trackId(r, p), deleteClips = r.nextBoolean())
            3 -> {
                val asset = p.assets.randomOrNull(r)?.id ?: "asset_404"
                val dur = p.asset(asset)?.durationUs ?: 1_000_000
                AddClip(
                    trackId(r, p),
                    asset,
                    startUs = if (r.nextBoolean()) null else time(r, p),
                    sourceInUs = if (r.nextBoolean()) 0 else r.nextLong(-1, dur),
                    sourceOutUs = if (r.nextBoolean()) null else r.nextLong(0, dur + 2),
                    speed = listOf(1.0, 2.0, 0.5, 3.33, 0.05, 11.0).random(r),
                    ripple = r.nextBoolean(),
                )
            }
            4 -> SplitClip(clipIds(r, p), time(r, p))
            5 -> TrimClip(clipId(r, p), ClipEdge.entries.random(r), time(r, p), r.nextBoolean())
            6 ->
                MoveClip(
                    clipIds(r, p),
                    if (r.nextInt(4) ==
                        0
                    ) {
                        null
                    } else {
                        time(r, p)
                    },
                    if (r.nextInt(3) == 0) trackId(r, p) else null,
                )
            7 -> DeleteClips(clipIds(r, p), r.nextBoolean())
            8 -> {
                val a = time(r, p)
                RippleDeleteRange(
                    a,
                    a + r.nextLong(-1, 3_000_000),
                    if (r.nextBoolean()) null else listOf(trackId(r, p)),
                )
            }
            9 ->
                SetClipProperties(
                    clipIds(r, p),
                    speed = if (r.nextBoolean()) listOf(0.5, 1.0, 2.0, 0.25, 4.0, 0.01, 1.234).random(r) else null,
                    volume = if (r.nextInt(3) == 0) listOf(0.0, 1.5, 3.0).random(r) else null,
                    transform = if (r.nextInt(3) == 0) TransformPatch(opacity = 0.5, rotationDeg = 450.0) else null,
                    ripple = r.nextBoolean(),
                )
            10 -> {
                val a = time(r, p).coerceAtLeast(0)
                AddCaption(
                    List(r.nextInt(1, 3)) { i ->
                        CaptionSpec(
                            a + i * 1_000_000,
                            a + i * 1_000_000 + r.nextLong(MIN - 1, 900_000),
                            "hi $i",
                        )
                    },
                    trackId = if (r.nextBoolean()) null else trackId(r, p),
                )
            }
            11 ->
                EditCaption(
                    clipIds(r, p),
                    text = if (r.nextBoolean()) "edited" else null,
                    style = if (r.nextBoolean()) CaptionStylePatch(color = "#FFFFFF00", backgroundColor = "") else null,
                )
            12, 13 -> ApplyCuts(cutRanges(r, p))
            14 -> SetProjectSettings(name = "n${r.nextInt(10)}", width = listOf(1280, 1281, 8).random(r))
            else ->
                Batch(
                    List(r.nextInt(0, 4)) {
                        command(r, p, allowBatch = false)
                    },
                    label = if (r.nextBoolean()) "label" else null,
                )
        }

    /** Erase clip ids (for comparing results that allocate ids in different orders). */
    fun eraseIds(p: Project): Project =
        p.copy(
            nextId = 0,
            tracks =
                p.tracks.map { t ->
                    t.copy(
                        clips =
                            t.clips.map {
                                when (it) {
                                    is MediaClip -> it.copy(id = "")
                                    is CaptionClip -> it.copy(id = "")
                                }
                            },
                    )
                },
        )
}
