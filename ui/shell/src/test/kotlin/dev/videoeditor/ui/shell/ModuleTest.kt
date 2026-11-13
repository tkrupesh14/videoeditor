package dev.videoeditor.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleTest {
    @Test
    fun moduleNameIsStable() {
        assertEquals("ui:shell", UiShellModule.NAME)
    }
}
