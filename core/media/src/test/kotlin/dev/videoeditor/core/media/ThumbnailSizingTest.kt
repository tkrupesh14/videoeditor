package dev.videoeditor.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ThumbnailSizingTest {
    @Test
    fun downscalesLongestEdge() {
        assertEquals(256 to 144, ThumbnailSizing.fit(1920, 1080, 256))
        assertEquals(144 to 256, ThumbnailSizing.fit(1080, 1920, 256))
    }

    @Test
    fun neverUpscales() {
        assertEquals(100 to 50, ThumbnailSizing.fit(100, 50, 256))
    }

    @Test
    fun unknownSizeUsesBox() {
        assertEquals(256 to 256, ThumbnailSizing.fit(0, 0, 256))
    }

    @Test
    fun keyBucketsTime() {
        assertEquals(
            ThumbnailSizing.key("u", 1_050_000, 128, 100_000),
            ThumbnailSizing.key("u", 1_099_999, 128, 100_000),
        )
        assertNotEquals(
            ThumbnailSizing.key("u", 1_050_000, 128, 100_000),
            ThumbnailSizing.key("u", 1_100_000, 128, 100_000),
        )
    }
}
