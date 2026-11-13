package dev.videoeditor.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AppIdTest {
    @Test
    fun packageNameMatchesApplicationId() {
        assertEquals("dev.videoeditor.app", MainActivity::class.java.`package`?.name)
    }
}
