package dev.videoeditor.core.timeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ModuleTest {
    @Test
    fun moduleNameIsStable() {
        assertEquals("core:timeline", CoretimelineModule.NAME)
    }
}
