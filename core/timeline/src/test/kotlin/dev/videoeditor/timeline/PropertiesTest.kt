package dev.videoeditor.timeline

import dev.videoeditor.timeline.command.AddClip
import dev.videoeditor.timeline.command.ApplyCuts
import dev.videoeditor.timeline.command.Batch
import dev.videoeditor.timeline.command.ClipEdge
import dev.videoeditor.timeline.command.Command
import dev.videoeditor.timeline.command.CommandError
import dev.videoeditor.timeline.command.CommandExecutor
import dev.videoeditor.timeline.command.CommandResult
import dev.videoeditor.timeline.command.DeleteClips
import dev.videoeditor.timeline.command.MoveClip
import dev.videoeditor.timeline.command.RippleDeleteRange
import dev.videoeditor.timeline.command.SetClipProperties
import dev.videoeditor.timeline.command.SplitClip
import dev.videoeditor.timeline.command.TrimClip
import dev.videoeditor.timeline.command.typeName
import dev.videoeditor.timeline.diff.ProjectDiff
import dev.videoeditor.timeline.gen.Arbs
import dev.videoeditor.timeline.gen.MIN
import dev.videoeditor.timeline.json.CommandDecodeResult
import dev.videoeditor.timeline.json.ProjectLoadResult
import dev.videoeditor.timeline.json.TimelineJson
import dev.videoeditor.timeline.model.Clip
import dev.videoeditor.timeline.model.MediaClip
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.model.TimeMath
import dev.videoeditor.timeline.model.TimeRange
import dev.videoeditor.timeline.model.TrackKind
import dev.videoeditor.timeline.model.endUs
import dev.videoeditor.timeline.query.EditLimits
import dev.videoeditor.timeline.query.SnapKind
import dev.videoeditor.timeline.query.SnapPoint
import dev.videoeditor.timeline.query.Snapping
import dev.videoeditor.timeline.schema.ToolRegistry
import dev.videoeditor.timeline.session.EditorSession
import dev.videoeditor.timeline.validate.ProjectValidator
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Property tests of design section 8.2 (numbers in names refer to the P-table). */
@OptIn(ExperimentalKotest::class)
class PropertiesTest {
    private val iterations = 400
    private val cfg = PropTestConfig(seed = 20261113L)

    private fun prop(block: suspend (Project, Random) -> Unit) =
        runBlocking {
            checkAll(iterations, cfg, Arbs.scenario()) { s -> block(s.project, s.random) }
        }

    private fun assertInvariants(p: Project) {
        assertEquals(emptyList<Any>(), ProjectValidator.validate(p))
        for (t in p.tracks) {
            for (i in 1 until t.clips.size) assertTrue(t.clips[i - 1].endUs <= t.clips[i].startUs, "overlap on ${t.id}")
            for (c in t.clips.filterIsInstance<MediaClip>()) {
                val a = p.asset(c.assetId)!!
                assertTrue(c.sourceInUs >= 0 && c.sourceOutUs <= a.durationUs, "source of ${c.id} outside asset")
                assertEquals(TimeMath.timelineToSource(c.durationUs, c.speedCenti), c.sourceOutUs - c.sourceInUs)
            }
        }
    }

    @Test
    fun `generator meta - generated projects are valid and non-trivial`() {
        var withClips = 0
        var successes = 0
        prop { p, r ->
            assertInvariants(p)
            if (p.tracks.any { it.clips.size >= 2 }) withClips++
            if (CommandExecutor
                    .execute(
                        p,
                        Arbs.command(r, p),
                    ).let { it is CommandResult.Success && it.changed }
            ) {
                successes++
            }
        }
        assertTrue(withClips > iterations / 4, "only $withClips projects with 2+ clips on a track")
        assertTrue(successes > iterations / 10, "only $successes changing commands")
    }

    @Test
    fun `P1 P2 P3 closure, determinism, changed flag over command sequences`() =
        prop { p0, r ->
            var p = p0
            repeat(6) {
                val c = Arbs.command(r, p)
                val copy = p.copy()
                val res = CommandExecutor.execute(p, c)
                assertEquals(res, CommandExecutor.execute(p, c), "determinism for $c")
                assertEquals(copy, p)
                when (res) {
                    is CommandResult.Failure ->
                        assertFalse(
                            res.error is CommandError.Internal,
                            "internal error for $c: ${res.error}",
                        )
                    is CommandResult.Success -> {
                        assertInvariants(res.project)
                        assertEquals(res.changed, res.project != p, "changed flag for $c")
                        p = res.project
                    }
                }
            }
        }

    @Test
    fun `P4 batch equals the fold and reports the first failing index`() =
        prop { p, r ->
            val cmds = List(r.nextInt(1, 5)) { Arbs.command(r, p, allowBatch = false) }
            var cur = p
            var failAt = -1
            for ((i, c) in cmds.withIndex()) {
                when (val res = CommandExecutor.execute(cur, c)) {
                    is CommandResult.Success -> cur = res.project
                    is CommandResult.Failure -> {
                        failAt = i
                        break
                    }
                }
            }
            val b = CommandExecutor.execute(p, Batch(cmds))
            if (failAt < 0) {
                assertEquals(cur, (b as CommandResult.Success).project)
            } else {
                val e = (b as CommandResult.Failure).error as CommandError.BatchFailed
                assertEquals(failAt, e.index)
                assertEquals(cmds[failAt].typeName, e.commandType)
            }
        }

    private fun anyClip(
        p: Project,
        r: Random,
    ): Clip? = p.tracks.flatMap { it.clips }.randomOrNull(r)

    @Test
    fun `P5 split pieces`() =
        prop { p, r ->
            val c = anyClip(p, r) ?: return@prop
            if (c.durationUs < 2 * MIN) return@prop
            val at = r.nextLong(c.startUs + MIN, c.endUs - MIN + 1)
            val res = ok(p, SplitClip(listOf(c.id), at))
            val left = res.project.clipById(c.id)
            val right = res.project.clipById(res.createdIds.single())
            assertEquals(at, left.endUs)
            assertEquals(at, right.startUs)
            assertEquals(c.durationUs, left.durationUs + right.durationUs)
            if (c is MediaClip) assertTrue((right as MediaClip).sourceOutUs <= c.sourceOutUs)
            assertEquals(p.tracks.sumOf { it.clips.size } + 1, res.project.tracks.sumOf { it.clips.size })
            val others = p.tracks.flatMap { it.clips }.filter { it.id != c.id }
            others.forEach { assertEquals(it, res.project.clipById(it.id)) }
        }

    private fun sourceWithin(
        before: Project,
        after: Project,
    ) {
        val orig = before.tracks.flatMap { it.clips }.filterIsInstance<MediaClip>()
        for (c in after.tracks.flatMap { it.clips }.filterIsInstance<MediaClip>()) {
            assertTrue(
                orig.any { o ->
                    o.assetId == c.assetId && o.sourceInUs <= c.sourceInUs && c.sourceOutUs <= o.sourceOutUs
                },
                "source of ${c.id} grew",
            )
        }
    }

    @Test
    fun `P10 source never grows under split, inward trim, speed change and cuts`() =
        prop { p, r ->
            val c = anyClip(p, r) ?: return@prop
            val cmds: List<Command> =
                listOf(
                    SplitClip(listOf(c.id), c.startUs + c.durationUs / 2),
                    TrimClip(c.id, ClipEdge.START, c.startUs + r.nextLong(0, c.durationUs)),
                    TrimClip(c.id, ClipEdge.END, c.endUs - r.nextLong(0, c.durationUs), ripple = r.nextBoolean()),
                    SetClipProperties(listOf(c.id), speed = r.nextInt(10, 1001) / 100.0, ripple = true),
                    ApplyCuts(Arbs.cutRanges(r, p)),
                )
            for (cmd in cmds) {
                val res = CommandExecutor.execute(p, cmd)
                if (res is CommandResult.Success) sourceWithin(p, res.project)
            }
        }

    private fun normalized(
        ranges: List<TimeRange>,
        limit: Long,
    ): List<TimeRange> {
        val sorted = ranges.sortedBy { it.startUs }
        val out = mutableListOf<TimeRange>()
        for (x in sorted) {
            val l = out.lastOrNull()
            if (l != null &&
                x.startUs <= l.endUs
            ) {
                out[out.size - 1] = TimeRange(l.startUs, maxOf(l.endUs, x.endUs))
            } else {
                out += x
            }
        }
        return out.map { TimeRange(it.startUs.coerceIn(0, limit), it.endUs.coerceIn(0, limit)) }.filter {
            it.endUs >
                it.startUs
        }
    }

    private fun removedBefore(
        cuts: List<TimeRange>,
        t: Long,
    ) = cuts.sumOf { if (it.startUs >= t) 0L else minOf(it.endUs, t) - it.startUs }

    @Test
    fun `P6 P9 cuts preserve content and reduce duration`() =
        prop { p, r ->
            val ranges = Arbs.cutRanges(r, p)
            val res = CommandExecutor.execute(p, ApplyCuts(ranges)) as CommandResult.Success
            val q = res.project
            val cuts = normalized(ranges, p.durationUs)
            assertTrue(q.durationUs <= p.durationUs - removedBefore(cuts, p.durationUs))
            val firstCut = cuts.firstOrNull()?.startUs ?: Long.MAX_VALUE
            p.tracks.flatMap { it.clips }.filter { it.endUs <= firstCut }.forEach {
                assertEquals(
                    it,
                    q.clipById(it.id),
                )
            }
            // P6: a surviving original time maps to the same source time (within 1 us)
            repeat(5) {
                val track = p.tracks.randomOrNull(r) ?: return@repeat
                val c = track.clips.filterIsInstance<MediaClip>().randomOrNull(r) ?: return@repeat
                val t = r.nextLong(c.startUs, c.endUs)
                if (cuts.any { t >= it.startUs && t < it.endUs }) return@repeat
                val t2 = t - removedBefore(cuts, t)
                val hit = q.track(track.id)!!.clips.firstOrNull { it.startUs <= t2 && t2 < it.endUs } ?: return@repeat
                hit as MediaClip
                assertEquals(c.assetId, hit.assetId)
                assertTrue(Math.abs(TimeMath.sourceTimeAt(hit, t2) - TimeMath.sourceTimeAt(c, t)) <= 1)
            }
        }

    @Test
    fun `P7 cuts are order independent and split-range independent`() =
        prop { p, r ->
            val ranges = Arbs.cutRanges(r, p)
            val a = CommandExecutor.execute(p, ApplyCuts(ranges))
            assertEquals(a, CommandExecutor.execute(p, ApplyCuts(ranges.shuffled(r))))
            val split =
                ranges.flatMap {
                    if (it.durationUs <
                        2
                    ) {
                        listOf(it)
                    } else {
                        it.startUs.let { s ->
                            listOf(
                                TimeRange(s, s + it.durationUs / 2),
                                TimeRange(
                                    s + it.durationUs / 2,
                                    it.endUs,
                                ),
                            )
                        }
                    }
                }
            assertEquals(a, CommandExecutor.execute(p, ApplyCuts(split)))
        }

    @Test
    fun `P8 apply cuts equals descending ripple deletes`() =
        prop { p, r ->
            val ranges = Arbs.cutRanges(r, p)
            val a = (CommandExecutor.execute(p, ApplyCuts(ranges)) as CommandResult.Success).project
            var q = p
            for (cut in normalized(ranges, p.durationUs).sortedByDescending { it.startUs }) {
                q =
                    (
                        CommandExecutor.execute(
                            q,
                            RippleDeleteRange(cut.startUs, cut.endUs),
                        ) as CommandResult.Success
                    ).project
            }
            assertEquals(Arbs.eraseIds(a), Arbs.eraseIds(q))
        }

    @Test
    fun `P11 ripple delete preserves order and closes exactly the deleted space`() =
        prop { p, r ->
            val ids =
                p.tracks
                    .flatMap { it.clips }
                    .filter { r.nextInt(3) == 0 }
                    .map { it.id }
            if (ids.isEmpty()) return@prop
            val q = ok(p, DeleteClips(ids, ripple = true)).project
            for (t in p.tracks) {
                val survivors = t.clips.filter { it.id !in ids }
                val after = q.track(t.id)!!.clips
                assertEquals(survivors.map { it.id }, after.map { it.id })
                assertEquals(survivors.sumOf { it.durationUs }, after.sumOf { it.durationUs })
                for (i in 1 until survivors.size) {
                    val between =
                        t.clips.filter {
                            it.id in ids &&
                                it.startUs >= survivors[i - 1].endUs &&
                                it.endUs <= survivors[i].startUs
                        }
                    val gapBefore = survivors[i].startUs - survivors[i - 1].endUs
                    val gapAfter = after[i].startUs - after[i - 1].endUs
                    assertEquals(gapBefore - between.sumOf { it.durationUs }, gapAfter)
                }
            }
        }

    @Test
    fun `P12 move there and back restores the project`() =
        prop { p, r ->
            val c = anyClip(p, r) ?: return@prop
            val to = r.nextLong(0, p.durationUs + 2 * MIN)
            val there = CommandExecutor.execute(p, MoveClip(listOf(c.id), to)) as? CommandResult.Success ?: return@prop
            val back = ok(there.project, MoveClip(listOf(c.id), c.startUs))
            assertEquals(p, back.project)
        }

    @Test
    fun `P13 trim and trim back`() =
        prop { p, r ->
            val c = anyClip(p, r) ?: return@prop
            val edge = if (r.nextBoolean()) ClipEdge.START else ClipEdge.END
            val range = EditLimits.trimRange(p, c.id, edge)!!
            val to = r.nextLong(range.first, range.last + 1)
            val there = ok(p, TrimClip(c.id, edge, to)).project
            val backRes =
                CommandExecutor.execute(
                    there,
                    TrimClip(
                        c.id,
                        edge,
                        if (edge ==
                            ClipEdge.START
                        ) {
                            c.startUs
                        } else {
                            c.endUs
                        },
                    ),
                )
            assertTrue(backRes is CommandResult.Success, "$backRes")
            val b = (backRes as CommandResult.Success).project.clipById(c.id)
            assertEquals(c.startUs, b.startUs)
            assertEquals(c.durationUs, b.durationUs)
            if (c is MediaClip) assertEquals(c.sourceInUs, (b as MediaClip).sourceInUs)
        }

    @Test
    fun `P14 trimRange agrees with the executor`() =
        prop { p, r ->
            val c = anyClip(p, r) ?: return@prop
            val edge = if (r.nextBoolean()) ClipEdge.START else ClipEdge.END
            val ripple = r.nextBoolean()
            val range = EditLimits.trimRange(p, c.id, edge, ripple)!!
            for (to in listOf(range.first, range.last)) ok(p, TrimClip(c.id, edge, to, ripple))
            for (to in listOf(range.first - 1, range.last + 1)) {
                assertTrue(
                    CommandExecutor.execute(p, TrimClip(c.id, edge, to, ripple)) is CommandResult.Failure,
                    "$edge $ripple to $to",
                )
            }
        }

    private fun compatible(
        kind: TrackKind,
        a: dev.videoeditor.timeline.model.MediaAsset,
    ) = when (kind) {
        TrackKind.VIDEO, TrackKind.OVERLAY -> a.hasVideo
        TrackKind.AUDIO -> a.hasAudio
        TrackKind.CAPTION -> false
    }

    @Test
    fun `P15 canPlace agrees with AddClip and MoveClip`() {
        var added = 0
        var moved = 0
        prop { p, r ->
            // AddClip: speed 1.0, source range [0, d) so the duration equals the probed duration.
            val track = p.tracks.filter { it.kind != TrackKind.CAPTION }.randomOrNull(r)
            val asset = track?.let { t -> p.assets.filter { compatible(t.kind, it) }.randomOrNull(r) }
            if (track != null && asset != null) {
                val d = r.nextLong(MIN, asset.durationUs + 1)
                val start = Arbs.time(r, p).coerceAtLeast(0)
                val res = CommandExecutor.execute(p, AddClip(track.id, asset.id, start, 0, d))
                val can = EditLimits.canPlace(p, track.id, start, d)
                when {
                    res is CommandResult.Success -> assertTrue(can, "added but !canPlace $start $d")
                    (res as CommandResult.Failure).error is CommandError.Overlap ->
                        assertFalse(
                            can,
                            "overlap but canPlace",
                        )
                    else -> assertFalse(can, "$res but canPlace")
                }
                if (can) added++
            }
            // MoveClip of one clip within or across same-kind tracks.
            val c = anyClip(p, r) ?: return@prop
            val from = p.trackOf(c.id)!!
            val to = p.tracks.filter { it.kind == from.kind }.random(r)
            val toStart = Arbs.time(r, p).coerceAtLeast(0)
            val res = CommandExecutor.execute(p, MoveClip(listOf(c.id), toStart, to.id))
            val can = EditLimits.canPlace(p, to.id, toStart, c.durationUs, setOf(c.id))
            when {
                res is CommandResult.Success -> assertTrue(can, "moved but !canPlace")
                (res as CommandResult.Failure).error is CommandError.Overlap -> assertFalse(can, "overlap but canPlace")
                else -> assertFalse(can, "$res but canPlace")
            }
            if (can) moved++
        }
        assertTrue(added > 20 && moved > 20, "too few placeable cases: $added/$moved")
    }

    @Test
    fun `P21 every generated command validates against its tool schema`() =
        prop { p, r ->
            repeat(5) {
                val c = Arbs.command(r, p)
                val encoded = TimelineJson.encodeCommand(c)
                val spec = ToolRegistry.all.single { it.commandType == c.typeName }
                val args = JsonObject(encoded.filterKeys { it != "type" })
                assertEquals(
                    emptyList<String>(),
                    TestSchemaValidator.validate(spec.inputSchema, args),
                    "$spec $encoded",
                )
                assertEquals(JsonPrimitive(c.typeName), encoded["type"])
                val required = (spec.inputSchema["required"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                for (k in required) assertTrue((k as JsonPrimitive).content in args, "required $k absent in $encoded")
            }
        }

    @Test
    fun `P16 P17 undo of every execute returns the original, redo replays`() =
        prop { p, r ->
            val s = EditorSession(p)
            val revisions = mutableListOf(s.revision)
            repeat(8) {
                val before = s.project
                val res = s.execute(Arbs.command(r, s.project))
                if (res is CommandResult.Success && res.changed) {
                    assertFalse(s.state.canRedo)
                    val e = s.undo()!!
                    assertEquals(before, s.project)
                    s.redo()
                    assertEquals(e.after, s.project)
                } else {
                    assertEquals(before, s.project)
                }
                revisions += s.revision
            }
            assertTrue(revisions.zipWithNext().all { (a, b) -> b >= a })
            val after = s.project
            val n = s.undoHistory.size
            repeat(n) { s.undo() }
            assertEquals(p, s.project)
            repeat(n) { s.redo() }
            assertEquals(after, s.project)
        }

    @Test
    fun `P18 transaction equals executing the last successful update on the base`() =
        prop { p, r ->
            val s = EditorSession(p)
            s.beginTransaction("drag")
            var last: CommandResult.Success? = null
            repeat(4) {
                val res = s.updateTransaction(Arbs.command(r, p, allowBatch = false))
                if (res is CommandResult.Success) last = res
            }
            val expected = last?.project ?: p
            assertEquals(expected, s.project)
            val e = s.commitTransaction()
            assertEquals(if (expected == p) 0 else 1, s.undoHistory.size)
            if (e != null) assertEquals(p, e.before)
        }

    @Test
    fun `P19 P22 preview commit and diff`() =
        prop { p, r ->
            val s = EditorSession(p)
            val pv = s.newPreview()
            val created = mutableListOf<String>()
            repeat(3) {
                val res = pv.submit(Arbs.command(r, pv.project))
                if (res is CommandResult.Success) created += res.createdIds
            }
            assertEquals(p, s.project)
            assertTrue(ProjectDiff.between(p, p).isEmpty)
            val d = pv.diff()
            assertEquals(
                d.clipsAdded
                    .map {
                        it.clipId
                    }.toSet(),
                ProjectDiff
                    .between(pv.project, p)
                    .clipsRemoved
                    .map { it.clipId }
                    .toSet(),
            )
            val clipIds =
                pv.project.tracks
                    .flatMap { t -> t.clips.map { it.id } }
                    .toSet() -
                    p.tracks.flatMap { t -> t.clips.map { it.id } }.toSet()
            assertEquals(clipIds, d.clipsAdded.map { it.clipId }.toSet())
            // replaying the flattened batch on the base reproduces the preview
            if (pv.commands.isNotEmpty()) {
                assertEquals(
                    pv.project,
                    (CommandExecutor.execute(p, pv.toBatch()) as CommandResult.Success).project,
                )
            }
            s.commitPreview(pv, "ai")
            assertEquals(pv.project, s.project)
            assertEquals(if (pv.project == p) 0 else 1, s.undoHistory.size)
        }

    @Test
    fun `P20 serialization round trip of projects, commands and errors`() =
        prop { p, r ->
            assertEquals(ProjectLoadResult.Success(p), TimelineJson.decodeProject(TimelineJson.encodeProject(p)))
            repeat(3) {
                val c = Arbs.command(r, p)
                assertEquals(
                    CommandDecodeResult.Success(c),
                    TimelineJson.decodeCommand(TimelineJson.encodeCommand(c).toString()),
                )
                val res = CommandExecutor.execute(p, c)
                if (res is CommandResult.Failure) {
                    assertEquals(
                        res.error,
                        TimelineJson.decodeError(TimelineJson.encodeError(res.error)),
                    )
                }
            }
        }

    @Test
    fun `P24 load normalization`() =
        prop { p, r ->
            val shuffled = p.copy(nextId = 1, tracks = p.tracks.map { it.copy(clips = it.clips.shuffled(r)) })
            assertEquals(
                ProjectLoadResult.Success(p.copy(nextId = TimelineJson.normalize(shuffled).nextId)),
                TimelineJson.decodeProject(TimelineJson.encodeProject(shuffled)),
            )
        }

    @Test
    fun `P23 snap returns the input or the closest candidate`() =
        runBlocking {
            checkAll(
                iterations,
                cfg,
                Arb.list(Arb.long(0, 1_000_000), 0..8),
                Arb.long(0, 1_000_000),
                Arb.int(0, 50_000),
            ) { pts, t, th ->
                val cands = pts.map { SnapPoint(it, SnapKind.MARKER) }
                val res = Snapping.snap(t, cands, th.toLong())
                if (res.snappedTo == null) {
                    assertEquals(t, res.timeUs)
                    assertTrue(cands.none { Math.abs(it.timeUs - t) <= th })
                } else {
                    val d = Math.abs(res.timeUs - t)
                    assertTrue(d <= th)
                    assertTrue(cands.none { Math.abs(it.timeUs - t) < d })
                }
            }
        }
}
