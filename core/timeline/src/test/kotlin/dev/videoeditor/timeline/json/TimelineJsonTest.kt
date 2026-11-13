package dev.videoeditor.timeline.json

import dev.videoeditor.timeline.command.ApplyCuts
import dev.videoeditor.timeline.command.Batch
import dev.videoeditor.timeline.command.ClipEdge
import dev.videoeditor.timeline.command.CommandError
import dev.videoeditor.timeline.command.DeleteClips
import dev.videoeditor.timeline.command.EntityKind
import dev.videoeditor.timeline.command.SplitClip
import dev.videoeditor.timeline.command.TrimClip
import dev.videoeditor.timeline.model.MediaClip
import dev.videoeditor.timeline.model.TimeRange
import dev.videoeditor.timeline.sample.SampleProjects
import dev.videoeditor.timeline.validate.InvariantCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TimelineJsonTest {
    private val designExample =
        """
        {"schemaVersion":1,"id":"3f0c","name":"Trip","nextId":6,"output":{"width":1920,"height":1080,"frameRate":30},
        "assets":[{"id":"asset_1","uri":"content://media/external/video/media/42","durationUs":12000000,
        "hasVideo":true,"hasAudio":true,"width":1920,"height":1080,"frameRate":29.97}],
        "tracks":[{"id":"track_2","kind":"VIDEO","name":"Video 1","clips":[
        {"type":"media","id":"clip_4","assetId":"asset_1","startUs":0,"durationUs":5000000,"sourceInUs":1000000,
        "speed":1.0,"volume":1.0,"transform":{"translateX":0.0,"translateY":0.0,"scale":1.0,"rotationDeg":0.0,"opacity":1.0}}]},
        {"id":"track_3","kind":"CAPTION","name":"Captions 1","clips":[
        {"type":"caption","id":"clip_5","startUs":500000,"durationUs":2000000,"text":"Hello",
        "style":{"fontSizeFrac":0.05,"color":"#FFFFFFFF","bold":false,"italic":false,"align":"CENTER"},
        "transform":{"translateX":0.0,"translateY":0.35,"scale":1.0,"rotationDeg":0.0,"opacity":1.0}}]}]}
        """.trimIndent().replace("\n", "")

    @Test
    fun `design example loads and re-encodes byte-identically`() {
        val r = TimelineJson.decodeProject(designExample) as ProjectLoadResult.Success
        assertEquals(designExample, TimelineJson.encodeProject(r.project))
    }

    @Test
    fun `sample project round trip`() {
        val p = SampleProjects.twoClipDemo()
        assertEquals(ProjectLoadResult.Success(p), TimelineJson.decodeProject(TimelineJson.encodeProject(p)))
    }

    @Test
    fun `version handling and migration hook`() {
        fun withVersion(v: String) = designExample.replace("\"schemaVersion\":1", v)
        assertEquals(
            ProjectLoadResult.Failure(ProjectLoadError.UnsupportedVersion(2, 1)),
            TimelineJson.decodeProject(withVersion("\"schemaVersion\":2")),
        )
        assertMalformed(TimelineJson.decodeProject(withVersion("\"schemaVersion\":0")))
        assertMalformed(TimelineJson.decodeProject(withVersion("\"schemaVersion\":\"1\"")))
        assertMalformed(TimelineJson.decodeProject(withVersion("\"v\":1")))
        assertMalformed(TimelineJson.decodeProject("[1]"))
        assertMalformed(TimelineJson.decodeProject("{"))
        // v0 without a registered migration
        val v0 = withVersion("\"schemaVersion\":0").replace("\"name\":\"Trip\"", "\"title\":\"Trip\"")
        val fake =
            object : Migration {
                override val fromVersion = 0

                override fun migrate(project: JsonObject): JsonObject {
                    val title = project.getValue("title")
                    return JsonObject(project - "title" + ("name" to title))
                }
            }
        // version 0 is rejected as malformed before migration (versions start at 1); test the hook from a fake current-1
        assertMalformed(TimelineJson.decodeProject(v0, listOf(fake)))
        val migrated =
            TimelineJson.decodeProjectFrom(
                v0.replace("\"schemaVersion\":0", "\"schemaVersion\":1"),
                current = 2,
                listOf(shift(fake)),
            )
        assertEquals(1, (migrated as ProjectLoadResult.Success).migratedFrom)
        assertEquals("Trip", migrated.project.name)
        assertEquals(
            ProjectLoadResult.Failure(ProjectLoadError.UnsupportedVersion(1, 2)),
            TimelineJson.decodeProjectFrom(designExample, current = 2, emptyList()),
        )
    }

    private fun shift(m: Migration) =
        object : Migration {
            override val fromVersion = m.fromVersion + 1

            override fun migrate(project: JsonObject) = m.migrate(project)
        }

    private fun assertMalformed(r: ProjectLoadResult) {
        assertInstanceOf(ProjectLoadError.Malformed::class.java, (r as ProjectLoadResult.Failure).error)
    }

    @Test
    fun `load normalizes order and nextId, rejects overlap`() {
        val p = SampleProjects.twoClipDemo()
        val shuffled = p.copy(nextId = 1, tracks = p.tracks.map { it.copy(clips = it.clips.reversed()) })
        assertEquals(ProjectLoadResult.Success(p), TimelineJson.decodeProject(TimelineJson.encodeProject(shuffled)))
        val bad =
            p.copy(
                tracks =
                    p.tracks.map { t ->
                        t.copy(
                            clips =
                                t.clips.map { c ->
                                    if (c.id ==
                                        "clip_5"
                                    ) {
                                        (c as MediaClip).copy(startUs = 3_000_000)
                                    } else {
                                        c
                                    }
                                },
                        )
                    },
            )
        val r = TimelineJson.decodeProject(TimelineJson.encodeProject(bad)) as ProjectLoadResult.Failure
        assertTrue((r.error as ProjectLoadError.Invalid).violations.any { it.code == InvariantCode.NO_OVERLAP })
    }

    @Test
    fun `commands encode and decode, unknown field is malformed`() {
        val cmds =
            listOf(
                SplitClip(listOf("clip_4"), 2_500_000),
                TrimClip("clip_4", ClipEdge.END, 4_000_000, ripple = true),
                ApplyCuts(listOf(TimeRange(1_200_000, 1_850_000))),
                Batch(listOf(DeleteClips(listOf("clip_9"), ripple = true)), "Tighten intro"),
            )
        for (c in cmds) {
            assertEquals(CommandDecodeResult.Success(c), TimelineJson.decodeCommand(TimelineJson.encodeCommand(c)))
        }
        assertEquals("split_clip", (TimelineJson.encodeCommand(cmds[0])["type"] as JsonPrimitive).content)
        assertEquals(
            CommandDecodeResult.Success(SplitClip(listOf("clip_4"), 1)),
            TimelineJson.decodeCommand("""{"type":"split_clip","clipIds":["clip_4"],"atUs":1}"""),
        )
        assertInstanceOf(
            CommandDecodeResult.Failure::class.java,
            TimelineJson.decodeCommand("""{"type":"split_clip","clipIds":["a"],"atUs":1,"at":2}"""),
        )
        assertInstanceOf(CommandDecodeResult.Failure::class.java, TimelineJson.decodeCommand("""{"type":"nope"}"""))
        assertInstanceOf(CommandDecodeResult.Failure::class.java, TimelineJson.decodeCommand("not json"))
    }

    @Test
    fun `errors encode with message and decode back`() {
        val e = CommandError.BatchFailed(1, "delete_clips", CommandError.NotFound(EntityKind.CLIP, "x"))
        val o = TimelineJson.encodeError(e)
        assertEquals("batch_failed", (o["type"] as JsonPrimitive).content)
        assertEquals(e.message, (o["message"] as JsonPrimitive).content)
        assertEquals("not_found", (o.getValue("cause").jsonObject["type"] as JsonPrimitive).content)
        assertEquals(e, TimelineJson.decodeError(o))
        assertEquals(
            CommandError.TransactionInProgress,
            TimelineJson.decodeError(TimelineJson.encodeError(CommandError.TransactionInProgress)),
        )
        assertEquals("Invalid atUs: bad", CommandError.InvalidArgument("atUs", "bad").message)
    }
}
