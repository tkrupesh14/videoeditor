package dev.videoeditor.ui.timeline

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleTest {
    @Test
    fun moduleNameIsStable() {
        assertEquals("ui:timeline", UiTimelineModule.NAME)
    }
}
