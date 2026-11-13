package dev.videoeditor.core.render

import dev.videoeditor.timeline.model.CaptionClip
import dev.videoeditor.timeline.model.MediaAsset
import dev.videoeditor.timeline.model.MediaClip
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.model.Track
import dev.videoeditor.timeline.model.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderPlannerTest {
    private val video =
        MediaAsset("v", "content://media/v", 10_000_000, hasVideo = true, hasAudio = true, width = 3840, height = 2160)
    private val music = MediaAsset("m", "file:///music.m4a", 60_000_000, hasVideo = false, hasAudio = true)
    private val silent = MediaAsset("s", "content://media/s", 5_000_000, hasVideo = true, hasAudio = false)

    private fun clip(
        id: String,
        start: Long,
        dur: Long,
        sourceIn: Long = 0,
        asset: String = "v",
        speed: Double = 1.0,
        volume: Double = 1.0,
    ) = MediaClip(id, asset, start, dur, sourceIn, speed, volume)

    private fun project(vararg tracks: Track) =
        Project(id = "p", name = "p", assets = listOf(video, music, silent), tracks = tracks.toList())

    private fun media(seg: RenderSegment) = seg as RenderSegment.Media

    @Test
    fun emptyProject() {
        val plan = RenderPlanner.plan(Project.empty("p", "p"))
        assertTrue(plan.isEmpty)
        assertEquals(0L, plan.durationUs)
        assertTrue(plan.captions.isEmpty())
        assertNull(plan.mainVideoTrack)
    }

    @Test
    fun emptyTracksAreDropped() {
        val plan = RenderPlanner.plan(project(Track("t1", TrackKind.VIDEO, "V"), Track("t2", TrackKind.AUDIO, "A")))
        assertTrue(plan.isEmpty)
    }

    @Test
    fun singleClipMapsClippingAndFlags() {
        val plan =
            RenderPlanner.plan(
                project(Track("t", TrackKind.VIDEO, "V", listOf(clip("c", 0, 2_000_000, sourceIn = 1_000_000)))),
            )
        val seg =
            media(
                plan.tracks
                    .single()
                    .segments
                    .single(),
            )
        assertEquals("content://media/v", seg.uri)
        assertEquals(1_000_000L, seg.clipStartUs)
        assertEquals(3_000_000L, seg.clipEndUs)
        assertEquals(0L, seg.timelineStartUs)
        assertEquals(10_000_000L, seg.assetDurationUs)
        assertEquals(3840, seg.sourceWidth)
        assertTrue(seg.useVideo && seg.useAudio)
        assertEquals(2_000_000L, plan.durationUs)
        assertSame(plan.tracks[0], plan.mainVideoTrack)
    }

    @Test
    fun gapsAreExplicitIncludingLeading() {
        val t = Track("t", TrackKind.VIDEO, "V", listOf(clip("b", 3_000_000, 1_000_000), clip("a", 500_000, 1_000_000)))
        val segs =
            RenderPlanner
                .plan(project(t))
                .tracks
                .single()
                .segments
        assertEquals(4, segs.size)
        assertEquals(RenderSegment.Gap(0, 500_000), segs[0])
        assertEquals("a", media(segs[1]).clipId)
        assertEquals(RenderSegment.Gap(1_500_000, 1_500_000), segs[2])
        assertEquals("b", media(segs[3]).clipId)
        // Contiguous from 0
        var cursor = 0L
        for (s in segs) {
            assertEquals(cursor, s.timelineStartUs)
            cursor = s.timelineEndUs
        }
        assertEquals(4_000_000L, cursor)
    }

    @Test
    fun speedScalesSourceRange() {
        val fast = clip("f", 0, 2_000_000, sourceIn = 1_000_000, speed = 2.0)
        val slow = clip("s", 2_000_000, 2_000_000, sourceIn = 0, speed = 0.5)
        val segs = RenderPlanner.plan(project(Track("t", TrackKind.VIDEO, "V", listOf(fast, slow)))).tracks[0].segments
        val f = media(segs[0])
        assertEquals(2.0, f.speed, 0.0)
        assertEquals(4_000_000L, f.sourceDurationUs)
        assertEquals(5_000_000L, f.clipEndUs)
        assertEquals(2_000_000L, f.durationUs)
        val s = media(segs[1])
        assertEquals(1_000_000L, s.sourceDurationUs)
        assertEquals(0.5, s.speed, 0.0)
    }

    @Test
    fun speedIsNormalizedToHundredths() {
        val seg =
            media(
                RenderPlanner
                    .plan(
                        project(Track("t", TrackKind.VIDEO, "V", listOf(clip("c", 0, 1_000_000, speed = 1.234)))),
                    ).tracks[0]
                    .segments[0],
            )
        assertEquals(1.23, seg.speed, 1e-9)
        assertEquals(1_230_000L, seg.sourceDurationUs)
    }

    @Test
    fun splitClipsStayAdjacentWithoutGap() {
        // A 4s clip split at 1.5s: second half continues the source.
        val a = clip("a", 0, 1_500_000, sourceIn = 0)
        val b = clip("b", 1_500_000, 2_500_000, sourceIn = 1_500_000)
        val segs = RenderPlanner.plan(project(Track("t", TrackKind.VIDEO, "V", listOf(b, a)))).tracks[0].segments
        assertEquals(2, segs.size)
        assertEquals(media(segs[0]).clipEndUs, media(segs[1]).clipStartUs)
        assertEquals(media(segs[0]).timelineEndUs, media(segs[1]).timelineStartUs)
    }

    @Test
    fun multipleTracksKeepOrderAndKinds() {
        val v = Track("v1", TrackKind.VIDEO, "V", listOf(clip("c1", 0, 3_000_000)))
        val o = Track("o1", TrackKind.OVERLAY, "O", listOf(clip("c2", 1_000_000, 1_000_000, asset = "s")))
        val a = Track("a1", TrackKind.AUDIO, "A", listOf(clip("c3", 0, 8_000_000, asset = "m", volume = 0.5)))
        val plan = RenderPlanner.plan(project(v, o, a))
        assertEquals(listOf("v1", "o1", "a1"), plan.tracks.map { it.trackId })
        assertEquals(8_000_000L, plan.durationUs)
        val overlay = plan.tracks[1]
        assertTrue(overlay.hasVideo)
        assertFalse(overlay.hasAudio) // asset has no audio
        val music = media(plan.tracks[2].segments[0])
        assertFalse(music.useVideo)
        assertTrue(music.useAudio)
        assertEquals(0.5, music.volume, 0.0)
        assertEquals("v1", plan.mainVideoTrack?.trackId)
    }

    @Test
    fun videoAssetOnAudioTrackDropsPicture() {
        val plan = RenderPlanner.plan(project(Track("a", TrackKind.AUDIO, "A", listOf(clip("c", 0, 1_000_000)))))
        val seg = media(plan.tracks[0].segments[0])
        assertFalse(seg.useVideo)
        assertTrue(seg.useAudio)
        assertNull(plan.mainVideoTrack)
    }

    @Test
    fun mutedClipWithNothingVisibleBecomesMergedGap() {
        val t =
            Track(
                "a",
                TrackKind.AUDIO,
                "A",
                listOf(
                    clip("x", 1_000_000, 1_000_000, asset = "m", volume = 0.0),
                    clip("y", 2_000_000, 1_000_000, asset = "m"),
                ),
            )
        val segs = RenderPlanner.plan(project(t)).tracks[0].segments
        assertEquals(RenderSegment.Gap(0, 2_000_000), segs[0])
        assertEquals("y", media(segs[1]).clipId)
    }

    @Test
    fun mutedVideoClipKeepsPictureOnly() {
        val seg =
            media(
                RenderPlanner
                    .plan(
                        project(Track("t", TrackKind.VIDEO, "V", listOf(clip("c", 0, 1_000_000, volume = 0.0)))),
                    ).tracks[0]
                    .segments[0],
            )
        assertTrue(seg.useVideo)
        assertFalse(seg.useAudio)
    }

    @Test
    fun missingAssetAndOverlapAreReportedAndSkipped() {
        val t =
            Track(
                "t",
                TrackKind.VIDEO,
                "V",
                listOf(
                    clip("a", 0, 2_000_000),
                    clip("b", 1_000_000, 2_000_000),
                    clip("c", 3_000_000, 1_000_000, asset = "nope"),
                    clip("d", 4_000_000, 1_000_000),
                ),
            )
        val plan = RenderPlanner.plan(project(t))
        assertEquals(listOf(PlanIssue.Kind.OVERLAP, PlanIssue.Kind.MISSING_ASSET), plan.issues.map { it.kind })
        val segs = plan.tracks[0].segments
        assertEquals(listOf("a", "d"), segs.filterIsInstance<RenderSegment.Media>().map { it.clipId })
        assertEquals(RenderSegment.Gap(2_000_000, 2_000_000), segs[1])
    }

    @Test
    fun captionsBecomeSortedSpansAndExtendDuration() {
        val v = Track("v", TrackKind.VIDEO, "V", listOf(clip("c", 0, 1_000_000)))
        val caps =
            Track(
                "cap",
                TrackKind.CAPTION,
                "C",
                listOf(
                    CaptionClip("k2", 1_000_000, 2_000_000, "second"),
                    CaptionClip("k1", 0, 1_000_000, "first"),
                    CaptionClip("blank", 3_000_000, 1_000_000, "   "),
                ),
            )
        val plan = RenderPlanner.plan(project(v, caps))
        assertEquals(listOf("k1", "k2"), plan.captions.map { it.clipId })
        assertEquals(3_000_000L, plan.durationUs)
        assertEquals(1, plan.tracks.size) // caption track is not a media sequence
        assertEquals("first", plan.captions.activeAt(999_999)?.text)
        assertEquals("second", plan.captions.activeAt(1_000_000)?.text)
        assertNull(plan.captions.activeAt(3_000_000))
    }

    @Test
    fun captionOnlyProjectHasNoMediaTracks() {
        val plan =
            RenderPlanner.plan(
                project(Track("cap", TrackKind.CAPTION, "C", listOf(CaptionClip("k", 0, 1_000_000, "hi")))),
            )
        assertTrue(plan.isEmpty)
        assertEquals(1, plan.captions.size)
    }

    @Test
    fun mediaAtFindsSegmentOrGap() {
        val t =
            Track("t", TrackKind.VIDEO, "V", listOf(clip("a", 1_000_000, 1_000_000), clip("b", 3_000_000, 1_000_000)))
        val track = RenderPlanner.plan(project(t)).tracks[0]
        assertNull(track.mediaAt(500_000))
        assertEquals("a", track.mediaAt(1_000_000)?.clipId)
        assertEquals("a", track.mediaAt(1_999_999)?.clipId)
        assertNull(track.mediaAt(2_500_000))
        assertEquals("b", track.mediaAt(3_500_000)?.clipId)
        assertNull(track.mediaAt(4_000_000))
    }

    @Test
    fun revisionKeyAndOutputPassThrough() {
        val p = project(Track("t", TrackKind.VIDEO, "V", listOf(clip("c", 0, 1_000_000))))
        val plan = RenderPlanner.plan(p, revisionKey = "42")
        assertEquals("42", plan.revisionKey)
        assertEquals(p.output, plan.output)
    }
}
