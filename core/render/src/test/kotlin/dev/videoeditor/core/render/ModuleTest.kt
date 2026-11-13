package dev.videoeditor.core.render

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleTest {
    @Test
    fun moduleNameIsStable() {
        assertEquals("core:render", CoreRenderModule.NAME)
    }
}
