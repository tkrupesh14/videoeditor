package dev.videoeditor.core.media

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleTest {
    @Test
    fun moduleNameIsStable() {
        assertEquals("core:media", CoreMediaModule.NAME)
    }
}
