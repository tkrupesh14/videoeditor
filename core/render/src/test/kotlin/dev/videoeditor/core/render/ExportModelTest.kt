package dev.videoeditor.core.render

import dev.videoeditor.timeline.host.ExportRequest
import dev.videoeditor.timeline.host.ExportState
import dev.videoeditor.timeline.model.OutputSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportModelTest {
    @Test
    fun settingsDefaultToProjectOutput() {
        val s = ExportSettings.resolve(OutputSettings(1280, 720, 24), ExportRequest(), "job1")
        assertEquals(1280, s.width)
        assertEquals(720, s.height)
        assertEquals(24, s.frameRate)
        assertEquals("export-job1.mp4", s.fileName)
        assertEquals("video/avc", s.videoMimeType)
        assertEquals("audio/mp4a-latm", s.audioMimeType)
    }

    @Test
    fun requestOverridesAndDimsAreEvenAndClamped() {
        val s =
            ExportSettings.resolve(
                OutputSettings(),
                ExportRequest(width = 721, height = 99_999, frameRate = 500),
                "j",
            )
        assertEquals(720, s.width)
        assertEquals(8192, s.height)
        assertEquals(120, s.frameRate)
        assertEquals(16, ExportSettings.resolve(OutputSettings(), ExportRequest(width = 3, frameRate = 0), "j").width)
        assertEquals(1, ExportSettings.resolve(OutputSettings(), ExportRequest(frameRate = 0), "j").frameRate)
    }

    @Test
    fun fileNamesAreSanitized() {
        assertEquals("holiday.mp4", ExportSettings.sanitizeFileName("holiday.mp4", "j"))
        assertEquals("passwd.mp4", ExportSettings.sanitizeFileName("../../etc/passwd", "j"))
        assertEquals("my_cut__v2_.mp4", ExportSettings.sanitizeFileName("my cut (v2)", "j"))
        assertEquals("evil.mp4", ExportSettings.sanitizeFileName("C:\\x\\evil", "j"))
        assertEquals("export-j.mp4", ExportSettings.sanitizeFileName("...", "j"))
        assertEquals("export-j.mp4", ExportSettings.sanitizeFileName(null, "j"))
        assertEquals("export-a_b.mp4", ExportSettings.sanitizeFileName("", "a/b"))
    }

    @Test
    fun media3ErrorCodesMapToTypedErrors() {
        assertTrue(ExportError.fromMedia3Code(2005, "nf") is ExportError.Io)
        assertTrue(ExportError.fromMedia3Code(3003, null) is ExportError.Decoding)
        assertTrue(ExportError.fromMedia3Code(4001, null) is ExportError.Encoding)
        assertTrue(ExportError.fromMedia3Code(5001, null) is ExportError.Processing)
        assertTrue(ExportError.fromMedia3Code(6001, null) is ExportError.Processing)
        assertTrue(ExportError.fromMedia3Code(7002, null) is ExportError.Muxing)
        val u = ExportError.fromMedia3Code(1000, null)
        assertTrue(u is ExportError.Unknown)
        assertEquals(1000, u.code)
        assertEquals("Export failed (code 1000)", u.message)
    }

    @Test
    fun progressMapping() {
        val start = ExportProgress("j", ExportState.QUEUED)
        assertSame(start, ProgressMapper.map(start, TransformerProgressState.NOT_STARTED, 0))
        val waiting = ProgressMapper.map(start, TransformerProgressState.WAITING_FOR_AVAILABILITY, 0)
        assertEquals(ExportState.RUNNING, waiting.state)
        assertNull(waiting.fraction)
        val half = ProgressMapper.map(waiting, TransformerProgressState.AVAILABLE, 50)
        assertEquals(0.5, half.fraction!!, 0.0)
        // Never backwards, capped below 1 while running, garbage clamped.
        assertEquals(0.5, ProgressMapper.map(half, TransformerProgressState.AVAILABLE, 10).fraction!!, 0.0)
        assertEquals(0.99, ProgressMapper.map(half, TransformerProgressState.AVAILABLE, 100).fraction!!, 0.0)
        assertEquals(0.99, ProgressMapper.map(half, TransformerProgressState.AVAILABLE, 250).fraction!!, 0.0)
        assertEquals(half, ProgressMapper.map(half, TransformerProgressState.UNAVAILABLE, 0))
    }

    @Test
    fun terminalProgressIsStickyAndMapsToStatus() {
        val done = ExportProgress("j", ExportState.DONE, 0.99, outputUri = "file:///x.mp4")
        assertSame(done, ProgressMapper.map(done, TransformerProgressState.AVAILABLE, 10))
        val status = done.toStatus()
        assertEquals(1.0, status.progress, 0.0)
        assertEquals("file:///x.mp4", status.outputUri)
        val failed = ExportProgress("j", ExportState.FAILED, 0.3, error = ExportError.EmptyProject()).toStatus()
        assertEquals(ExportState.FAILED, failed.state)
        assertEquals(0.3, failed.progress, 0.0)
        assertTrue(failed.error!!.startsWith("EmptyProject"))
    }
}
