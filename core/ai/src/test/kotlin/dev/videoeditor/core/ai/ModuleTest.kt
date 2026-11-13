package dev.videoeditor.core.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ModuleTest {
    @Test
    fun moduleNameIsStable() {
        assertEquals("core:ai", CoreaiModule.NAME)
    }
}
