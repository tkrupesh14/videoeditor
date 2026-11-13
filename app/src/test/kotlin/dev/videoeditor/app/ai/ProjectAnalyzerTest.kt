package dev.videoeditor.app.ai

import dev.videoeditor.app.security.FakePrefs
import dev.videoeditor.core.ai.analysis.LumaFrame
import dev.videoeditor.core.ai.analysis.NoTranscriptAvailable
import dev.videoeditor.core.ai.analysis.SilenceDetector
import dev.videoeditor.timeline.model.MediaClip
import dev.videoeditor.timeline.model.TimeRange
import dev.videoeditor.timeline.sample.SampleProjects
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectAnalyzerTest {
    /** 1 kHz mono "audio" of the whole 12 s asset: loud except silent at source 1.0..3.0 s and 7.0..8.0 s. */
    private class FakeDecoder(
        private val hang: Boolean = false,
    ) : MediaDecoder {
        val audioCalls = mutableListOf<Pair<Long, Long>>()

        override suspend fun decodeAudio(
            uri: String,
            fromUs: Long,
            toUs: Long,
        ): PcmAudio {
            if (hang) awaitCancellation()
            audioCalls += fromUs to toUs
            val rate = 1_000
            val n = ((toUs - fromUs) * rate / 1_000_000).toInt()
            val samples =
                ShortArray(n) { i ->
                    val t = fromUs + i * 1_000L
                    val silent = t in 1_000_000 until 3_000_000 || t in 7_000_000 until 8_000_000
                    if (silent) 0 else (if (i % 2 == 0) 16_000 else -16_000).toShort()
                }
            return PcmAudio(samples, rate, fromUs)
        }

        /** A dark frame before source 2 s, bright after: one scene change at 2 s. */
        override suspend fun lumaFrame(
            uri: String,
            timeUs: Long,
        ): LumaFrame = LumaFrame(timeUs, 4, 4, ByteArray(16) { (if (timeUs < 2_000_000) 10 else 240).toByte() })

        override suspend fun jpegFrame(
            uri: String,
            timeUs: Long,
            maxEdge: Int,
        ): ByteArray? = null
    }

    private val detector = SilenceDetector(paddingUs = 0, minSilenceUs = 300_000)

    @Test
    fun silencesAreMappedToTimelineTime() =
        runTest {
            // clip_4: timeline 0..4 s = source 0..4 s. clip_5: timeline 5..9 s = source 6..10 s.
            val decoder = FakeDecoder()
            val a = ProjectAnalyzer(decoder, silenceDetector = detector)
            val r = a.silences(SampleProjects.twoClipDemo())
            assertEquals(listOf(TimeRange(1_000_000, 3_000_000), TimeRange(6_000_000, 7_000_000)), r)
            // Cached per source range.
            a.silences(SampleProjects.twoClipDemo())
            assertEquals(2, decoder.audioCalls.size)
        }

    @Test
    fun analyzeFillsTheBundleWithTheTranscriptStub() =
        runTest {
            val bundle =
                ProjectAnalyzer(
                    FakeDecoder(),
                    silenceDetector = detector,
                ).analyze(SampleProjects.twoClipDemo(), false)
            assertEquals(NoTranscriptAvailable.MESSAGE, bundle.transcriptUnavailableReason)
            assertTrue(bundle.transcript.isEmpty())
            assertEquals(2, bundle.silences.size)
            assertEquals(listOf(2_000_000L), bundle.sceneChangesUs)
            assertTrue(bundle.keyframes.isEmpty())
        }

    @Test
    fun analysisIsCancellable() =
        runTest {
            val job = async { ProjectAnalyzer(FakeDecoder(hang = true)).silences(SampleProjects.twoClipDemo()) }
            advanceUntilIdle()
            job.cancel()
            val e = runCatching { job.await() }.exceptionOrNull()
            assertTrue(e is CancellationException)
        }

    @Test
    fun speedIsHonouredWhenMapping() {
        val clip =
            MediaClip("c", "a", startUs = 10_000_000, durationUs = 2_000_000, sourceInUs = 4_000_000, speed = 2.0)
        assertEquals(
            TimeRange(10_500_000, 11_000_000),
            TimelineMapping.sourceToTimeline(clip, TimeRange(5_000_000, 6_000_000)),
        )
        assertEquals(null, TimelineMapping.sourceToTimeline(clip, TimeRange(0, 1_000_000)))
        assertEquals(
            TimeRange(11_500_000, 12_000_000),
            TimelineMapping.sourceToTimeline(clip, TimeRange(7_000_000, 9_000_000)),
        )
    }

    @Test
    fun overlappingClipsMustBothBeSilent() {
        val a = MediaClip("a", "x", startUs = 0, durationUs = 10_000_000, sourceInUs = 0)
        val b = MediaClip("b", "y", startUs = 4_000_000, durationUs = 4_000_000, sourceInUs = 0)
        val r =
            TimelineMapping.combineSilences(
                listOf(a, b),
                mapOf(
                    "a" to listOf(TimeRange(2_000_000, 9_000_000)),
                    "b" to listOf(TimeRange(5_000_000, 6_000_000)),
                ),
            )
        assertEquals(
            listOf(TimeRange(2_000_000, 4_000_000), TimeRange(5_000_000, 6_000_000), TimeRange(8_000_000, 9_000_000)),
            r,
        )
    }

    @Test
    fun sampleTimesAndSpreadAreBounded() {
        assertEquals(
            listOf(0L, 500_000L, 1_000_000L, 1_500_000L),
            TimelineMapping.sampleTimes(0, 2_000_000, 500_000, 100),
        )
        assertEquals(10, TimelineMapping.sampleTimes(0, 100_000_000, 500_000, 10).size)
        assertEquals(listOf(0, 2, 5, 7), TimelineMapping.spread((0 until 10).toList(), 4))
    }

    @Test
    fun preferencesPersistAndRejectUnknownModels() {
        val prefs = FakePrefs()
        val p = PrefsAiPreferences(prefs, AiPorts.PRICED_MODELS)
        assertEquals("claude-opus-5-5", p.model)
        p.model = "claude-sonnet-5-5"
        p.shareFrames = true
        p.consentVersion = 1
        val again = PrefsAiPreferences(prefs, AiPorts.PRICED_MODELS)
        assertEquals("claude-sonnet-5-5", again.model)
        assertTrue(again.shareFrames)
        assertEquals(1, again.consentVersion)
        assertTrue(runCatching { p.model = "gpt" }.isFailure)
    }
}
