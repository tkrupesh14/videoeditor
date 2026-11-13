package dev.videoeditor.timeline.schema

import dev.videoeditor.timeline.S
import dev.videoeditor.timeline.fixture
import dev.videoeditor.timeline.host.AssetTranscript
import dev.videoeditor.timeline.host.ExportRequest
import dev.videoeditor.timeline.host.ExportService
import dev.videoeditor.timeline.host.ExportState
import dev.videoeditor.timeline.host.ExportStatus
import dev.videoeditor.timeline.host.TranscriptSegment
import dev.videoeditor.timeline.host.TranscriptService
import dev.videoeditor.timeline.host.TranscriptWord
import dev.videoeditor.timeline.media
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.sample.SampleProjects
import dev.videoeditor.timeline.session.EditorSession
import dev.videoeditor.timeline.session.Origin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolDispatcherTest {
    private val none = ToolDispatcher(null, null)

    private fun splitArgs(
        id: String,
        at: Long,
    ) = buildJsonObject {
        putJsonArray("clipIds") { add(JsonPrimitive(id)) }
        put("atUs", at)
    }

    private fun prim(
        o: JsonObject,
        k: String,
    ) = (o[k] as JsonPrimitive).content

    @Test
    fun `split on a session and on a preview`() =
        runBlocking {
            val session = EditorSession(SampleProjects.twoClipDemo())
            val r = none.call(session.asTarget(Origin.MCP), "split", splitArgs("clip_4", 2 * S))
            assertFalse(r.isError)
            assertEquals("true", prim(r.content, "ok"))
            assertEquals(
                listOf(JsonPrimitive("clip_7")),
                r.content
                    .getValue("createdIds")
                    .jsonArray
                    .toList(),
            )
            assertEquals(1, session.undoHistory.size)

            val preview = session.newPreview()
            val p = none.call(preview, "split", splitArgs("clip_5", 7 * S))
            assertFalse(p.isError)
            assertEquals(1, session.undoHistory.size)
            assertEquals(1, preview.commands.size)

            val bad = none.call(preview, "split", splitArgs("clip_5", 5 * S))
            assertTrue(bad.isError)
            val err = bad.content.getValue("error").jsonObject
            assertEquals("split_out_of_range", prim(err, "type"))
            assertTrue(prim(err, "message").isNotBlank())
        }

    @Test
    fun `unknown tool, malformed args, unavailable host`() =
        runBlocking {
            val target = EditorSession(SampleProjects.twoClipDemo()).asTarget(Origin.MCP)
            assertTrue(none.call(target, "nope", JsonObject(emptyMap())).isError)
            val m = none.call(target, "split", buildJsonObject { put("atUs", 1) })
            assertEquals("malformed", prim(m.content.getValue("error").jsonObject, "type"))
            assertTrue(none.call(target, "list_clips", buildJsonObject { put("bogus", 1) }).isError)
            val ex = none.call(target, "export", JsonObject(emptyMap()))
            assertEquals("not_available", prim(ex.content.getValue("error").jsonObject, "type"))
        }

    @Test
    fun `queries`() =
        runBlocking {
            val target = EditorSession(SampleProjects.twoClipDemo()).asTarget(Origin.MCP)
            val summary = none.call(target, "get_project", JsonObject(emptyMap())).content
            assertEquals(9 * S, (summary["durationUs"] as JsonPrimitive).long)
            val full = none.call(target, "get_project", buildJsonObject { put("full", true) }).content
            assertEquals("1", prim(full, "schemaVersion"))
            val clips = none.call(target, "list_clips", buildJsonObject { put("trackId", "track_2") }).content
            assertEquals(2, clips.getValue("clips").jsonArray.size)
        }

    @Test
    fun `export with a fake service`() =
        runBlocking {
            val svc =
                object : ExportService {
                    var request: ExportRequest? = null

                    override suspend fun startExport(
                        project: Project,
                        request: ExportRequest,
                    ): ExportStatus {
                        this.request = request
                        return ExportStatus("job1", ExportState.QUEUED)
                    }

                    override suspend fun exportStatus(jobId: String) =
                        if (jobId ==
                            "job1"
                        ) {
                            ExportStatus("job1", ExportState.RUNNING, 0.5)
                        } else {
                            null
                        }
                }
            val d = ToolDispatcher(svc, null)
            val target = EditorSession(SampleProjects.twoClipDemo()).asTarget(Origin.MCP)
            assertEquals(
                "job1",
                prim(d.call(target, "export", buildJsonObject { put("fileName", "out.mp4") }).content, "jobId"),
            )
            assertEquals("out.mp4", svc.request!!.fileName)
            assertEquals(
                "RUNNING",
                prim(d.call(target, "export", buildJsonObject { put("jobId", "job1") }).content, "state"),
            )
            assertTrue(d.call(target, "export", buildJsonObject { put("jobId", "x") }).isError)
        }

    @Test
    fun `transcript is mapped through a 2x clip and a trimmed clip`() =
        runBlocking {
            // clip_10: timeline [0, 2 s) at 2x from source 0 -> covers source [0, 4 s)
            // clip_11: timeline [5 s, 7 s) at 1x from source 10 s -> covers source [10 s, 12 s)
            val p =
                fixture(
                    video =
                        listOf(
                            media("clip_10", 0, 2 * S, speed = 2.0),
                            media(
                                "clip_11",
                                5 * S,
                                2 * S,
                                sourceIn =
                                    10 * S,
                            ),
                        ),
                )
            val words =
                listOf(
                    TranscriptWord(1 * S, 1_500_000, "one"),
                    TranscriptWord(3 * S, 3_400_000, "two"),
                    TranscriptWord(9 * S, 10_200_000, "late"),
                    TranscriptWord(11 * S, 11_500_000, "three"),
                )
            val svc =
                object : TranscriptService {
                    override suspend fun transcript(assetId: String) =
                        if (assetId == "asset_1") {
                            AssetTranscript(
                                assetId,
                                "en",
                                listOf(TranscriptSegment(0, 12 * S, "one two late three", words)),
                            )
                        } else {
                            null
                        }
                }
            val d = ToolDispatcher(null, svc)
            val target = EditorSession(p).asTarget(Origin.MCP)
            val wl = d.call(target, "get_transcript", buildJsonObject { put("wordLevel", true) }).content
            val entries = wl.getValue("entries").jsonArray.map { it.jsonObject }
            assertEquals(listOf("one", "two", "three"), entries.map { prim(it, "text") })
            assertEquals(listOf(500_000L, 1_500_000L, 6 * S), entries.map { (it["startUs"] as JsonPrimitive).long })
            assertEquals(listOf(750_000L, 1_700_000L, 6_500_000L), entries.map { (it["endUs"] as JsonPrimitive).long })
            val seg =
                d
                    .call(target, "get_transcript", JsonObject(emptyMap()))
                    .content
                    .getValue("entries")
                    .jsonArray
            assertEquals(listOf("one two", "three"), seg.map { prim(it.jsonObject, "text") })
            val windowed = d.call(target, "get_transcript", buildJsonObject { put("fromUs", 5 * S) }).content
            assertEquals(1, windowed.getValue("entries").jsonArray.size)
        }
}
