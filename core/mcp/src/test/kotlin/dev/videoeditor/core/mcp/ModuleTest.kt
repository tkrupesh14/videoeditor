package dev.videoeditor.core.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ModuleTest {
    @Test
    fun moduleNameIsStable() {
        assertEquals("core:mcp", CoremcpModule.NAME)
    }
}
