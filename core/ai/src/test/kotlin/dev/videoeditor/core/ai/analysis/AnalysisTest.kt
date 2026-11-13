package dev.videoeditor.core.ai.analysis

import dev.videoeditor.timeline.model.TimeRange
import dev.videoeditor.timeline.sample.SampleProjects
import dev.videoeditor.timeline.session.EditorSession
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin

class SilenceDetectorTest {
    private val rate = 16_000

    private fun tone(seconds: Double) =
        FloatArray((seconds * rate).toInt()) {
            (0.5 * sin(2 * PI * 440 * it / rate)).toFloat()
        }

    private fun silence(seconds: Double) = FloatArray((seconds * rate).toInt())

    @Test
    fun `reports padded silence between speech`() {
        val audio = tone(1.0) + silence(1.0) + tone(1.0)
        assertEquals(listOf(TimeRange(1_100_000, 1_900_000)), SilenceDetector().detect(audio, rate))
    }

    @Test
    fun `ignores silence shorter than the minimum`() {
        val audio = tone(1.0) + silence(0.3) + tone(1.0)
        assertEquals(emptyList<TimeRange>(), SilenceDetector().detect(audio, rate))
    }

    @Test
    fun `edges are not padded and startUs offsets the result`() {
        val audio = silence(1.0) + tone(1.0) + silence(1.0)
        val r = SilenceDetector().detect(audio, rate, startUs = 5_000_000)
        assertEquals(listOf(TimeRange(5_000_000, 5_900_000), TimeRange(7_100_000, 8_000_000)), r)
    }

    @Test
    fun `quiet noise below threshold counts as silence and short samples work`() {
        val quiet = FloatArray(rate) { if (it % 2 == 0) 0.001f else -0.001f } // about -60 dBFS
        val shorts = (tone(1.0) + quiet + tone(1.0)).map { (it * 32767).toInt().toShort() }.toShortArray()
        assertEquals(listOf(TimeRange(1_100_000, 1_900_000)), SilenceDetector().detect(shorts, rate))
        assertEquals(emptyList<TimeRange>(), SilenceDetector(thresholdDb = -70.0).detect(shorts, rate))
    }
}

class SceneChangeDetectorTest {
    private fun frame(
        i: Int,
        value: Int,
        noise: Int = 0,
    ) = LumaFrame(i * 100_000L, 8, 8, ByteArray(64) { ((value + (it % 3) * noise).coerceIn(0, 255)).toByte() })

    @Test
    fun `finds a hard cut with both methods`() {
        val frames = (0 until 10).map { frame(it, 20, 2) } + (10 until 20).map { frame(it, 200, 2) }
        for (m in SceneChangeDetector.Method.entries) {
            assertEquals(listOf(1_000_000L), SceneChangeDetector(method = m).detect(frames), m.name)
        }
    }

    @Test
    fun `small changes are not cuts and flashes are suppressed`() {
        val steady = (0 until 10).map { frame(it, 100 + it, 3) }
        assertEquals(emptyList<Long>(), SceneChangeDetector().detect(steady))
        val flash = listOf(frame(0, 20), frame(1, 230), frame(2, 20), frame(3, 20))
        assertEquals(listOf(100_000L), SceneChangeDetector(minSceneUs = 500_000).detect(flash))
        assertEquals(listOf(100_000L, 200_000L), SceneChangeDetector(minSceneUs = 0).detect(flash))
    }

    @Test
    fun `scores are normalized`() {
        val black = frame(0, 0)
        val white = frame(1, 255)
        assertEquals(1.0, SceneChangeDetector.meanAbsDiff(black.luma, white.luma))
        assertEquals(1.0, SceneChangeDetector.histogramDistance(black.luma, white.luma))
        assertEquals(0.0, SceneChangeDetector.histogramDistance(black.luma, black.luma))
    }
}

class SilenceCutsTest {
    @Test
    fun `merges clamps and drops short ranges`() {
        val cuts =
            SilenceCuts.toApplyCuts(
                listOf(
                    TimeRange(3_000_000, 4_000_000),
                    TimeRange(1_000_000, 2_000_000),
                    TimeRange(1_500_000, 2_500_000),
                    TimeRange(5_000_000, 5_005_000),
                    TimeRange(8_500_000, 12_000_000),
                ),
                timelineDurationUs = 9_000_000,
            )!!
        assertEquals(
            listOf(TimeRange(1_000_000, 2_500_000), TimeRange(3_000_000, 4_000_000), TimeRange(8_500_000, 9_000_000)),
            cuts.ranges,
        )
        assertNull(SilenceCuts.toApplyCuts(listOf(TimeRange(10_000_000, 11_000_000)), 9_000_000))
        assertNull(SilenceCuts.toApplyCuts(emptyList(), 9_000_000))
    }

    @Test
    fun `preview removes silences without touching the session`() {
        val project = SampleProjects.twoClipDemo()
        val session = EditorSession(project)
        val preview = SilenceCuts.preview(session, listOf(TimeRange(1_000_000, 2_000_000)))
        assertEquals(project.durationUs - 1_000_000, preview.project.durationUs)
        assertEquals(project, session.project)
        assertEquals(1, preview.commands.size)
    }
}

class TranscriptHookTest {
    @Test
    fun `no provider says so and never invents text`() =
        runBlocking {
            val r = NoTranscriptAvailable.transcribe("asset_1")
            assertEquals(TranscriptOutcome.Unavailable("No speech-to-text provider configured"), r)
            assertNull(NoTranscriptAvailable.asTranscriptService().transcript("asset_1"))
            assertTrue(NoTranscriptAvailable.MESSAGE.contains("speech-to-text"))
        }
}
