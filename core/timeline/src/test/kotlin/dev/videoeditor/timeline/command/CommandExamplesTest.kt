package dev.videoeditor.timeline.command

import dev.videoeditor.timeline.S
import dev.videoeditor.timeline.caption
import dev.videoeditor.timeline.clipById
import dev.videoeditor.timeline.err
import dev.videoeditor.timeline.fixture
import dev.videoeditor.timeline.media
import dev.videoeditor.timeline.model.CaptionClip
import dev.videoeditor.timeline.model.CaptionStylePatch
import dev.videoeditor.timeline.model.Limits
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.model.TimeMath
import dev.videoeditor.timeline.model.TimeRange
import dev.videoeditor.timeline.model.TrackKind
import dev.videoeditor.timeline.model.TransformPatch
import dev.videoeditor.timeline.model.endUs
import dev.videoeditor.timeline.ok
import dev.videoeditor.timeline.sample.SampleProjects
import dev.videoeditor.timeline.validate.ProjectValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

private const val MIN = Limits.MIN_CLIP_DURATION_US

class CommandExamplesTest {
    @Nested
    inner class TimeMathTests {
        @Test
        fun `speed tables floor`() {
            assertEquals(1_234_567, TimeMath.timelineToSource(1_234_567, 100))
            assertEquals(4_111_108, TimeMath.timelineToSource(1_234_567, 333))
            assertEquals(123_456, TimeMath.timelineToSource(1_234_567, 10))
            assertEquals(370_740, TimeMath.sourceToTimeline(1_234_567, 333))
            assertEquals(-4, TimeMath.timelineToSource(-1, 333))
            assertEquals(1.5, TimeMath.normalizeSpeed(1.4999999))
            assertEquals(0.1, TimeMath.normalizeSpeed(0.096))
            assertEquals(0.09, TimeMath.normalizeSpeed(0.094))
            assertEquals("1:02.500", TimeMath.formatTimecode(62_500_000))
            assertEquals("1:00:00.000", TimeMath.formatTimecode(3600 * S))
        }

        @Test
        fun `speed 0_094 is rejected and 0_096 accepted`() {
            val p = fixture(video = listOf(media("clip_10", 0, 2 * S)))
            assertInstanceOf(
                CommandError.InvalidArgument::class.java,
                err(p, SetClipProperties(listOf("clip_10"), speed = 0.094)),
            )
            assertEquals(0.1, ok(p, SetClipProperties(listOf("clip_10"), speed = 0.096)).project.media("clip_10").speed)
        }
    }

    @Nested
    inner class Split {
        private val p = fixture(video = listOf(media("clip_10", 1 * S, 4 * S, sourceIn = 2 * S, speed = 2.0)))

        @Test
        fun `middle of a 2x clip`() {
            val r = ok(p, SplitClip(listOf("clip_10"), 2_500_000))
            assertEquals(listOf("clip_100"), r.createdIds)
            val left = r.project.media("clip_10")
            val right = r.project.media("clip_100")
            assertEquals(1_500_000, left.durationUs)
            assertEquals(2_500_000, right.startUs)
            assertEquals(2_500_000, right.durationUs)
            assertEquals(2 * S + 3 * S, right.sourceInUs)
            assertEquals(left.sourceOutUs, right.sourceInUs)
            assertEquals(101, r.project.nextId)
            assertEquals("Split clip_10 at 0:02.500", r.summary)
        }

        @Test
        fun `split at boundary is rejected with bounds`() {
            for (at in listOf(1 * S, 5 * S, 1 * S + MIN - 1)) {
                val e = err(p, SplitClip(listOf("clip_10"), at))
                assertEquals(CommandError.SplitOutOfRange("clip_10", at, 1 * S + MIN, 5 * S - MIN), e)
            }
            ok(p, SplitClip(listOf("clip_10"), 1 * S + MIN))
        }

        @Test
        fun `clip shorter than twice the minimum cannot be split`() {
            val q = fixture(video = listOf(media("clip_10", 0, 2 * MIN - 1)))
            val e = err(q, SplitClip(listOf("clip_10"), MIN)) as CommandError.SplitOutOfRange
            assertTrue(e.minUs > e.maxUs)
        }

        @Test
        fun `caption split copies text and style`() {
            val q = fixture(captions = listOf(caption("clip_10", 0, 2 * S, "Hello")))
            val r = ok(q, SplitClip(listOf("clip_10"), S))
            val right = r.project.clipById("clip_100") as CaptionClip
            assertEquals("Hello", right.text)
            assertEquals((q.clipById("clip_10") as CaptionClip).style, right.style)
        }

        @Test
        fun `two clips at one playhead get ids in list order`() {
            val q =
                fixture(
                    video = listOf(media("clip_10", 0, 4 * S)),
                    audio = listOf(media("clip_11", 0, 4 * S, asset = "asset_2")),
                )
            val r = ok(q, SplitClip(listOf("clip_11", "clip_10"), 2 * S))
            assertEquals(listOf("clip_100", "clip_101"), r.createdIds)
            assertEquals(2 * S, r.project.clipById("clip_100").startUs)
            assertEquals(
                r.project
                    .track("track_4")!!
                    .clips[1]
                    .id,
                "clip_100",
            )
        }

        @Test
        fun `unknown id rejects whole list and empty list is invalid`() {
            assertEquals(
                CommandError.NotFound(EntityKind.CLIP, "nope"),
                err(
                    p,
                    SplitClip(
                        listOf("clip_10", "nope"),
                        2 * S,
                    ),
                ),
            )
            assertInstanceOf(CommandError.InvalidArgument::class.java, err(p, SplitClip(emptyList(), 2 * S)))
        }
    }

    @Nested
    inner class Trim {
        // clip_10 [1 s, 3 s) source 5 s; clip_11 [4 s, 6 s); clip_12 [7 s, 8 s); earlier clip_9 [0, 0.5 s)
        private val p =
            fixture(
                video =
                    listOf(
                        media("clip_9", 0, S / 2),
                        media("clip_10", 1 * S, 2 * S, sourceIn = 5 * S),
                        media("clip_11", 4 * S, 2 * S),
                        media("clip_12", 7 * S, 1 * S),
                    ),
            )

        @Test
        fun `end no ripple`() {
            val r = ok(p, TrimClip("clip_10", ClipEdge.END, 3_500_000))
            assertEquals(2_500_000, r.project.clipById("clip_10").durationUs)
            assertEquals(4 * S, r.project.clipById("clip_11").startUs)
        }

        @Test
        fun `end ripple shifts two later clips and not an earlier one`() {
            val r = ok(p, TrimClip("clip_10", ClipEdge.END, 3_500_000, ripple = true))
            assertEquals(4_500_000, r.project.clipById("clip_11").startUs)
            assertEquals(7_500_000, r.project.clipById("clip_12").startUs)
            assertEquals(0, r.project.clipById("clip_9").startUs)
        }

        @Test
        fun `start no ripple`() {
            val r = ok(p, TrimClip("clip_10", ClipEdge.START, 1_500_000))
            val c = r.project.media("clip_10")
            assertEquals(1_500_000, c.startUs)
            assertEquals(1_500_000, c.durationUs)
            assertEquals(5_500_000, c.sourceInUs)
            assertEquals(5 * S + 2 * S, c.sourceOutUs)
        }

        @Test
        fun `start ripple keeps start and pulls followers`() {
            val r = ok(p, TrimClip("clip_10", ClipEdge.START, 1_500_000, ripple = true))
            val c = r.project.media("clip_10")
            assertEquals(1 * S, c.startUs)
            assertEquals(1_500_000, c.durationUs)
            assertEquals(5_500_000, c.sourceInUs)
            assertEquals(3_500_000, r.project.clipById("clip_11").startUs)
            assertEquals(6_500_000, r.project.clipById("clip_12").startUs)
            assertEquals(0, r.project.clipById("clip_9").startUs)
        }

        @Test
        fun `extending past source bounds is rejected`() {
            val q = fixture(video = listOf(media("clip_10", 1 * S, 2 * S, sourceIn = 18 * S)))
            assertInstanceOf(
                CommandError.SourceOutOfRange::class.java,
                err(q, TrimClip("clip_10", ClipEdge.END, 3_500_000)),
            )
            val q2 = fixture(video = listOf(media("clip_10", 1 * S, 2 * S, sourceIn = 100)))
            assertEquals(
                CommandError.SourceOutOfRange("clip_10", "asset_1", -1 * S + 100, 2 * S + 100, 20 * S),
                err(q2, TrimClip("clip_10", ClipEdge.START, 0)),
            )
        }

        @Test
        fun `extending into a neighbour is an overlap, touching is fine`() {
            val e = err(p, TrimClip("clip_10", ClipEdge.END, 4 * S + 1)) as CommandError.Overlap
            assertEquals(listOf("clip_11"), e.conflictingClipIds)
            ok(p, TrimClip("clip_10", ClipEdge.END, 4 * S))
            val e2 = err(p, TrimClip("clip_10", ClipEdge.START, S / 2 - 1)) as CommandError.Overlap
            assertEquals(listOf("clip_9"), e2.conflictingClipIds)
        }

        @Test
        fun `trim below minimum is rejected, same position is unchanged`() {
            assertEquals(
                CommandError.ClipTooShort("clip_10", MIN - 1, MIN),
                err(p, TrimClip("clip_10", ClipEdge.END, 1 * S + MIN - 1)),
            )
            assertInstanceOf(CommandError.ClipTooShort::class.java, err(p, TrimClip("clip_10", ClipEdge.END, S / 2)))
            val r = ok(p, TrimClip("clip_10", ClipEdge.END, 3 * S))
            assertFalse(r.changed)
            assertSame(p, r.project)
        }
    }

    @Nested
    inner class Move {
        private val p =
            fixture(
                video =
                    listOf(
                        media("clip_10", 0, 2 * S),
                        media("clip_11", 2 * S, 2 * S),
                        media("clip_12", 6 * S, 1 * S),
                    ),
                audio = listOf(media("clip_20", 0, 1 * S, asset = "asset_2")),
            )

        @Test
        fun `to free space, onto occupied, touching`() {
            assertEquals(4 * S, ok(p, MoveClip(listOf("clip_12"), 4 * S)).project.clipById("clip_12").startUs)
            val e = err(p, MoveClip(listOf("clip_12"), 3 * S)) as CommandError.Overlap
            assertEquals(listOf("clip_11"), e.conflictingClipIds)
            ok(p, MoveClip(listOf("clip_12"), 5 * S))
        }

        @Test
        fun `adjacent group shifted into its own old space`() {
            val r = ok(p, MoveClip(listOf("clip_10", "clip_11"), 1 * S))
            assertEquals(1 * S, r.project.clipById("clip_10").startUs)
            assertEquals(3 * S, r.project.clipById("clip_11").startUs)
        }

        @Test
        fun `track kind checks`() {
            val r = ok(p, MoveClip(listOf("clip_10"), 2 * S, "track_4"))
            assertEquals("track_4", r.project.trackOf("clip_10")!!.id)
            val q = fixture(audio = listOf(media("clip_20", 0, 1 * S, asset = "asset_2")))
            assertInstanceOf(
                CommandError.TrackKindMismatch::class.java,
                err(q, MoveClip(listOf("clip_20"), toTrackId = "track_3")),
            )
            assertInstanceOf(
                CommandError.TrackKindMismatch::class.java,
                err(p, MoveClip(listOf("clip_10"), toTrackId = "track_5")),
            )
        }

        @Test
        fun `clips from two tracks with a target track are invalid`() {
            assertInstanceOf(
                CommandError.InvalidArgument::class.java,
                err(p, MoveClip(listOf("clip_10", "clip_20"), toTrackId = "track_4")),
            )
            assertInstanceOf(CommandError.InvalidArgument::class.java, err(p, MoveClip(listOf("clip_10"))))
        }
    }

    @Nested
    inner class DeleteAndCuts {
        @Test
        fun `delete with and without ripple keeps existing gaps`() {
            val p =
                fixture(
                    video =
                        listOf(
                            media("clip_10", 0, 1 * S),
                            media("clip_11", 2 * S, 1 * S),
                            media(
                                "clip_12",
                                5 * S,
                                1 * S,
                            ),
                        ),
                )
            val plain = ok(p, DeleteClips(listOf("clip_11"))).project
            assertEquals(5 * S, plain.clipById("clip_12").startUs)
            val rip = ok(p, DeleteClips(listOf("clip_11"), ripple = true)).project
            assertEquals(0, rip.clipById("clip_10").startUs)
            assertEquals(4 * S, rip.clipById("clip_12").startUs)
            assertNull(rip.clip("clip_11"))
        }

        @Test
        fun `apply cuts across three tracks`() {
            val p =
                fixture(
                    video =
                        listOf(
                            media("clip_10", 0, 4 * S, sourceIn = 1 * S),
                            media("clip_11", 4 * S, 2 * S),
                            media(
                                "clip_12",
                                6 * S,
                                2 * S,
                            ),
                        ),
                    audio = listOf(media("clip_20", 0, 8 * S, asset = "asset_2")),
                    captions = listOf(caption("clip_30", 3 * S, 2 * S)),
                )
            // Cut 1: inside clip_10 [1, 2). Cut 2: spans clip_10/clip_11 [3.5, 4.5). Cut 3: covers clip_12 [6, 8).
            val r =
                ok(
                    p,
                    ApplyCuts(
                        listOf(TimeRange(6 * S, 8 * S), TimeRange(1 * S, 2 * S), TimeRange(3_500_000, 4_500_000)),
                    ),
                )
            val q = r.project
            val v = q.track("track_3")!!.clips
            assertEquals(listOf("clip_10", "clip_100", "clip_11"), v.map { it.id })
            assertEquals(listOf(0L, 1 * S, 2_500_000L), v.map { it.startUs })
            assertEquals(listOf(1 * S, 1_500_000L, 1_500_000L), v.map { it.durationUs })
            assertEquals(
                listOf(1 * S, 3 * S, 500_000L),
                v.map { (it as dev.videoeditor.timeline.model.MediaClip).sourceInUs },
            )
            val a = q.track("track_4")!!.clips
            assertEquals(listOf("clip_20", "clip_101", "clip_102"), a.map { it.id })
            assertEquals(listOf(0L, 1 * S, 2_500_000L), a.map { it.startUs })
            assertEquals(listOf(1 * S, 1_500_000L, 1_500_000L), a.map { it.durationUs })
            val c = q.track("track_5")!!.clips
            assertEquals(listOf("clip_30", "clip_103"), c.map { it.id })
            assertEquals(listOf(2 * S, 2_500_000L), c.map { it.startUs })
            assertEquals(listOf(500_000L, 500_000L), c.map { it.durationUs })
            assertEquals(listOf("clip_100", "clip_101", "clip_102", "clip_103"), r.createdIds)
            assertEquals(4 * S, q.durationUs)
        }

        @Test
        fun `remnant under the minimum is dropped and reported`() {
            val p = fixture(video = listOf(media("clip_10", 0, 2 * S)))
            val r = ok(p, ApplyCuts(listOf(TimeRange(0, 2 * S - 5_000))))
            assertTrue(
                r.project
                    .track("track_3")!!
                    .clips
                    .isEmpty(),
            )
            assertTrue("dropped 1 fragment" in r.summary, r.summary)
        }

        @Test
        fun `ranges past the end change nothing and bad ranges are invalid`() {
            val p = fixture(video = listOf(media("clip_10", 0, 2 * S)))
            val r = ok(p, ApplyCuts(listOf(TimeRange(3 * S, 4 * S))))
            assertFalse(r.changed)
            assertEquals("No content in the given ranges", r.summary)
            assertInstanceOf(CommandError.InvalidArgument::class.java, err(p, ApplyCuts(listOf(TimeRange(2, 2)))))
            assertInstanceOf(CommandError.InvalidArgument::class.java, err(p, ApplyCuts(emptyList())))
        }

        @Test
        fun `ripple delete range on a track subset leaves other tracks untouched`() {
            val p =
                fixture(
                    video = listOf(media("clip_10", 0, 4 * S)),
                    audio = listOf(media("clip_20", 0, 4 * S, asset = "asset_2")),
                )
            val q = ok(p, RippleDeleteRange(1 * S, 2 * S, listOf("track_3"))).project
            assertEquals(p.track("track_4"), q.track("track_4"))
            assertEquals(3 * S, q.track("track_3")!!.endUs)
            assertEquals(CommandError.NotFound(EntityKind.TRACK, "x"), err(p, RippleDeleteRange(0, S, listOf("x"))))
            assertInstanceOf(CommandError.InvalidArgument::class.java, err(p, RippleDeleteRange(2 * S, S)))
        }
    }

    @Nested
    inner class AddClipTests {
        private val p = fixture(video = listOf(media("clip_10", 0, 2 * S), media("clip_11", 3 * S, 2 * S)))

        @Test
        fun `append, explicit start, ripple insert, straddling`() {
            val a = ok(p, AddClip("track_3", "asset_1", sourceInUs = 0, sourceOutUs = S))
            assertEquals(listOf("clip_100"), a.createdIds)
            assertEquals(5 * S, a.project.clipById("clip_100").startUs)
            assertEquals(
                2 * S,
                ok(
                    p,
                    AddClip("track_3", "asset_1", startUs = 2 * S, sourceOutUs = S),
                ).project.clipById("clip_100").startUs,
            )
            assertInstanceOf(
                CommandError.Overlap::class.java,
                err(
                    p,
                    AddClip(
                        "track_3",
                        "asset_1",
                        startUs = 2 * S,
                        sourceOutUs =
                            2 * S,
                    ),
                ),
            )
            val ins = ok(p, AddClip("track_3", "asset_1", startUs = 3 * S, sourceOutUs = 2 * S, ripple = true)).project
            assertEquals(5 * S, ins.clipById("clip_11").startUs)
            assertEquals(0, ins.clipById("clip_10").startUs)
            assertInstanceOf(
                CommandError.Overlap::class.java,
                err(p, AddClip("track_3", "asset_1", startUs = S, sourceOutUs = S, ripple = true)),
            )
        }

        @Test
        fun `wrong kind, short at 10x, bad source`() {
            assertInstanceOf(CommandError.TrackKindMismatch::class.java, err(p, AddClip("track_5", "asset_1")))
            assertInstanceOf(CommandError.TrackKindMismatch::class.java, err(p, AddClip("track_3", "asset_2")))
            assertEquals(
                CommandError.ClipTooShort(null, 9_000, MIN),
                err(p, AddClip("track_3", "asset_1", sourceOutUs = 90_000, speed = 10.0)),
            )
            assertInstanceOf(
                CommandError.SourceOutOfRange::class.java,
                err(
                    p,
                    AddClip(
                        "track_3",
                        "asset_1",
                        sourceOutUs =
                            21 * S,
                    ),
                ),
            )
            assertEquals(CommandError.NotFound(EntityKind.ASSET, "asset_9"), err(p, AddClip("track_3", "asset_9")))
        }
    }

    @Nested
    inner class Properties {
        private val p =
            fixture(
                video =
                    listOf(
                        media("clip_10", 0, 2 * S),
                        media("clip_11", 2 * S, 2 * S),
                        media(
                            "clip_12",
                            5 * S,
                            S,
                        ),
                    ),
            )

        @Test
        fun `speed doubles halves duration, ripple shifts followers`() {
            val r = ok(p, SetClipProperties(listOf("clip_10"), speed = 2.0)).project
            assertEquals(S, r.clipById("clip_10").durationUs)
            assertEquals(2 * S, r.clipById("clip_11").startUs)
            val rr = ok(p, SetClipProperties(listOf("clip_10"), speed = 2.0, ripple = true)).project
            assertEquals(S, rr.clipById("clip_11").startUs)
            assertEquals(4 * S, rr.clipById("clip_12").startUs)
        }

        @Test
        fun `slowdown into a neighbour without ripple overlaps`() {
            assertInstanceOf(
                CommandError.Overlap::class.java,
                err(p, SetClipProperties(listOf("clip_10"), speed = 0.5)),
            )
            val rr = ok(p, SetClipProperties(listOf("clip_10"), speed = 0.5, ripple = true)).project
            assertEquals(4 * S, rr.clipById("clip_10").durationUs)
            assertEquals(4 * S, rr.clipById("clip_11").startUs)
        }

        @Test
        fun `speed on caption is invalid, transform patch, rotation normalized`() {
            val q = fixture(video = listOf(media("clip_10", 0, 2 * S)), captions = listOf(caption("clip_30", 0, S)))
            assertInstanceOf(
                CommandError.InvalidArgument::class.java,
                err(q, SetClipProperties(listOf("clip_30"), speed = 2.0)),
            )
            val t =
                ok(
                    q,
                    SetClipProperties(
                        listOf("clip_10", "clip_30"),
                        transform = TransformPatch(opacity = 0.5, rotationDeg = 450.0),
                    ),
                ).project
            val tr = t.clipById("clip_10").transform
            assertEquals(0.5, tr.opacity)
            assertEquals(90.0, tr.rotationDeg)
            assertEquals(1.0, tr.scale)
            assertEquals(0.35, t.clipById("clip_30").transform.translateY)
            assertInstanceOf(CommandError.InvalidArgument::class.java, err(q, SetClipProperties(listOf("clip_10"))))
        }
    }

    @Nested
    inner class Captions {
        @Test
        fun `add caption creates a caption track when none exists`() {
            val p = Project(id = "x", name = "x")
            val r = ok(p, AddCaption(listOf(CaptionSpec(0, S, "one"), CaptionSpec(S, 2 * S, "  two  "))))
            assertEquals(listOf("track_1", "clip_2", "clip_3"), r.createdIds)
            assertEquals(
                TrackKind.CAPTION,
                r.project.tracks
                    .single()
                    .kind,
            )
            assertEquals(
                "Captions 1",
                r.project.tracks
                    .single()
                    .name,
            )
            assertEquals("two", (r.project.clipById("clip_3") as CaptionClip).text)
        }

        @Test
        fun `overlapping specs and blank text are rejected`() {
            val p = fixture(captions = listOf(caption("clip_30", 5 * S, S)))
            assertInstanceOf(
                CommandError.Overlap::class.java,
                err(
                    p,
                    AddCaption(
                        listOf(
                            CaptionSpec(0, 2 * S, "a"),
                            CaptionSpec(
                                S,
                                3 * S,
                                "b",
                            ),
                        ),
                    ),
                ),
            )
            assertInstanceOf(
                CommandError.Overlap::class.java,
                err(p, AddCaption(listOf(CaptionSpec(5_500_000, 7 * S, "a")))),
            )
            assertInstanceOf(
                CommandError.InvalidArgument::class.java,
                err(p, AddCaption(listOf(CaptionSpec(0, S, "   ")))),
            )
            assertInstanceOf(
                CommandError.ClipTooShort::class.java,
                err(p, AddCaption(listOf(CaptionSpec(0, MIN - 1, "a")))),
            )
            assertEquals(listOf("clip_100"), ok(p, AddCaption(listOf(CaptionSpec(0, S, "a")))).createdIds)
        }

        @Test
        fun `edit caption text and style`() {
            val p =
                fixture(
                    video = listOf(media("clip_10", 0, S)),
                    captions = listOf(caption("clip_30", 0, S), caption("clip_31", S, S)),
                )
            assertInstanceOf(
                CommandError.InvalidArgument::class.java,
                err(p, EditCaption(listOf("clip_30", "clip_31"), text = "x")),
            )
            assertInstanceOf(
                CommandError.InvalidArgument::class.java,
                err(p, EditCaption(listOf("clip_10"), text = "x")),
            )
            val styled =
                ok(
                    p,
                    EditCaption(
                        listOf("clip_30", "clip_31"),
                        style = CaptionStylePatch(color = "#ffffff00", backgroundColor = "#80000000"),
                    ),
                ).project
            assertEquals("#FFFFFF00", (styled.clipById("clip_31") as CaptionClip).style.color)
            val cleared =
                ok(
                    styled,
                    EditCaption(listOf("clip_30"), style = CaptionStylePatch(backgroundColor = "")),
                ).project
            assertNull((cleared.clipById("clip_30") as CaptionClip).style.backgroundColor)
            assertEquals("#80000000", (cleared.clipById("clip_31") as CaptionClip).style.backgroundColor)
            assertEquals(
                "new",
                (ok(p, EditCaption(listOf("clip_30"), text = " new ")).project.clipById("clip_30") as CaptionClip).text,
            )
        }
    }

    @Nested
    inner class ProjectLevel {
        @Test
        fun `import twice is a no-op naming the first id`() {
            val p = Project(id = "x", name = "x")
            val first = ok(p, ImportMedia("content://a", 5 * S, hasVideo = false, hasAudio = true))
            assertEquals(listOf("asset_1"), first.createdIds)
            val second =
                ok(
                    first.project,
                    ImportMedia("content://a", 9 * S, hasVideo = true, hasAudio = true, width = 2, height = 2),
                )
            assertFalse(second.changed)
            assertTrue("asset_1" in second.summary)
            assertInstanceOf(
                CommandError.InvalidArgument::class.java,
                err(p, ImportMedia("content://v", 5 * S, hasVideo = true, hasAudio = false)),
            )
        }

        @Test
        fun `add and remove tracks`() {
            val p = fixture(video = listOf(media("clip_10", 0, S)))
            val added = ok(p, AddTrack(TrackKind.VIDEO, index = 0))
            assertEquals("Video 2", added.project.tracks[0].name)
            assertInstanceOf(CommandError.InvalidArgument::class.java, err(p, AddTrack(TrackKind.AUDIO, index = 9)))
            assertEquals(CommandError.TrackNotEmpty("track_3", 1), err(p, RemoveTrack("track_3")))
            val removed = ok(p, RemoveTrack("track_3", deleteClips = true)).project
            assertNull(removed.track("track_3"))
            assertEquals(p.assets, removed.assets)
        }

        @Test
        fun `project settings`() {
            val p = fixture()
            val q = ok(p, SetProjectSettings(name = "New", width = 1280, height = 720, frameRate = 60)).project
            assertEquals("New", q.name)
            assertEquals(60, q.output.frameRate)
            assertInstanceOf(CommandError.InvalidArgument::class.java, err(p, SetProjectSettings(width = 1281)))
            assertInstanceOf(CommandError.InvalidArgument::class.java, err(p, SetProjectSettings()))
        }
    }

    @Nested
    inner class Batches {
        private val p = fixture(video = listOf(media("clip_10", 0, 4 * S)))

        @Test
        fun `split then delete the created clip`() {
            val r =
                ok(
                    p,
                    Batch(
                        listOf(SplitClip(listOf("clip_10"), 2 * S), DeleteClips(listOf("clip_100"))),
                        label = "Trim tail",
                    ),
                )
            assertEquals("Trim tail", r.summary)
            assertEquals(listOf("clip_100"), r.createdIds)
            assertEquals(2 * S, r.project.durationUs)
        }

        @Test
        fun `failure at index 1 applies nothing, nested batch rejected, empty unchanged`() {
            val e = err(p, Batch(listOf(SplitClip(listOf("clip_10"), 2 * S), DeleteClips(listOf("nope")))))
            assertEquals(CommandError.BatchFailed(1, "delete_clips", CommandError.NotFound(EntityKind.CLIP, "nope")), e)
            assertEquals(
                CommandError.InvalidArgument("commands[0]", "nested batch"),
                err(p, Batch(listOf(Batch(emptyList())))),
            )
            assertFalse(ok(p, Batch(emptyList())).changed)
            val r = ok(p, Batch(listOf(SplitClip(listOf("clip_10"), S), SplitClip(listOf("clip_10"), S / 2))))
            assertTrue(r.summary.startsWith("2 edits: "), r.summary)
        }
    }

    @Test
    fun `sample project is valid and every command type name is listed`() {
        assertTrue(ProjectValidator.isValid(SampleProjects.twoClipDemo()))
        assertEquals(15, CommandTypes.ALL.size)
        assertEquals(9 * S, SampleProjects.twoClipDemo().durationUs)
        assertEquals(4 * S, SampleProjects.twoClipDemo().clipById("clip_4").endUs)
    }
}
