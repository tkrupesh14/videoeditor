package dev.videoeditor.ui.shell

import android.view.KeyEvent
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyShortcutsTest {
    @Test
    fun plainKeys() {
        assertEquals(EditorShortcut.PLAY_PAUSE, KeyShortcuts.map(KeyEvent.KEYCODE_SPACE))
        assertEquals(EditorShortcut.SHUTTLE_BACK, KeyShortcuts.map(KeyEvent.KEYCODE_J))
        assertEquals(EditorShortcut.SHUTTLE_STOP, KeyShortcuts.map(KeyEvent.KEYCODE_K))
        assertEquals(EditorShortcut.SHUTTLE_FORWARD, KeyShortcuts.map(KeyEvent.KEYCODE_L))
        assertEquals(EditorShortcut.SPLIT_AT_PLAYHEAD, KeyShortcuts.map(KeyEvent.KEYCODE_S))
        assertEquals(EditorShortcut.DELETE_SELECTION, KeyShortcuts.map(KeyEvent.KEYCODE_DEL))
        assertEquals(EditorShortcut.DELETE_SELECTION, KeyShortcuts.map(KeyEvent.KEYCODE_FORWARD_DEL))
        assertNull(KeyShortcuts.map(KeyEvent.KEYCODE_A))
    }

    @Test
    fun undoRedo() {
        assertEquals(EditorShortcut.UNDO, KeyShortcuts.map(KeyEvent.KEYCODE_Z, ctrl = true))
        assertEquals(EditorShortcut.REDO, KeyShortcuts.map(KeyEvent.KEYCODE_Z, ctrl = true, shift = true))
        assertEquals(EditorShortcut.REDO, KeyShortcuts.map(KeyEvent.KEYCODE_Y, ctrl = true))
        assertEquals(EditorShortcut.UNDO, KeyShortcuts.map(KeyEvent.KEYCODE_Z, meta = true))
        assertNull(KeyShortcuts.map(KeyEvent.KEYCODE_Z))
    }

    @Test
    fun modifiersDoNotTriggerPlainShortcuts() {
        assertNull(KeyShortcuts.map(KeyEvent.KEYCODE_S, ctrl = true))
        assertNull(KeyShortcuts.map(KeyEvent.KEYCODE_S, shift = true))
        assertNull(KeyShortcuts.map(KeyEvent.KEYCODE_SPACE, alt = true))
        assertNull(KeyShortcuts.map(KeyEvent.KEYCODE_Z, ctrl = true, alt = true))
    }

    @Test
    fun layoutModeFollowsWidthClass() {
        assertEquals(EditorLayoutMode.SINGLE_PANE, EditorLayoutMode.from(WindowWidthSizeClass.Compact))
        assertEquals(EditorLayoutMode.THREE_PANE, EditorLayoutMode.from(WindowWidthSizeClass.Medium))
        assertEquals(EditorLayoutMode.THREE_PANE, EditorLayoutMode.from(WindowWidthSizeClass.Expanded))
    }

    @Test
    fun formatsDurations() {
        assertEquals("1:02.5", formatDuration(62_500_000))
        assertEquals("0:00.0", formatDuration(-5))
    }
}
