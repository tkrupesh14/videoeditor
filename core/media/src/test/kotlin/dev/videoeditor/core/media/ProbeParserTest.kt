package dev.videoeditor.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeParserTest {
    private fun raw(
        durationMs: String? = "2500",
        hasVideo: String? = "yes",
        hasAudio: String? = "yes",
        width: String? = "1920",
        height: String? = "1080",
        rotation: String? = "0",
    ) = RawProbe(durationMs, hasVideo, hasAudio, width, height, rotation, "video/mp4", extractorFrameRate = 30)

    @Test
    fun parsesVideoWithAudio() {
        val r = ProbeParser.parse("content://x/1", raw(), "a.mp4")
        assertEquals(2_500_000L, r.durationUs)
        assertTrue(r.hasVideo)
        assertTrue(r.hasAudio)
        assertEquals(1920, r.width)
        assertEquals(1080, r.height)
        assertEquals(30.0, r.frameRate!!, 0.0)
        val asset = r.toAsset("a1")
        assertEquals("a1", asset.id)
        assertEquals("content://x/1", asset.uri)
        assertEquals("a.mp4", asset.displayName)
    }

    @Test
    fun rotationSwapsDimensions() {
        val r = ProbeParser.parse("u", raw(rotation = "90"), null)
        assertEquals(1080, r.width)
        assertEquals(1920, r.height)
        assertEquals(270, ProbeParser.normalizeRotation(-90))
    }

    @Test
    fun audioOnlyHasNoSizeOrFps() {
        val r = ProbeParser.parse("u", raw(hasVideo = null, width = null, height = null), null)
        assertFalse(r.hasVideo)
        assertTrue(r.hasAudio)
        assertNull(r.width)
        assertNull(r.frameRate)
    }

    @Test
    fun extractorFallbacks() {
        val r =
            ProbeParser.parse(
                "u",
                RawProbe(
                    null,
                    null,
                    null,
                    "640",
                    "480",
                    null,
                    null,
                    extractorDurationUs = 1_234_567,
                    captureFrameRate = "24.0",
                    extractorHasVideo = true,
                ),
                null,
            )
        assertEquals(1_234_567L, r.durationUs)
        assertTrue(r.hasVideo)
        assertFalse(r.hasAudio)
        assertEquals(24.0, r.frameRate!!, 0.0)
    }

    @Test
    fun noStreamsOrDurationFails() {
        assertThrows(MediaProbeException::class.java) {
            ProbeParser.parse("u", raw(hasVideo = "no", hasAudio = null), null)
        }
        assertThrows(MediaProbeException::class.java) {
            ProbeParser.parse("u", raw(durationMs = "0"), null)
        }
    }
}
