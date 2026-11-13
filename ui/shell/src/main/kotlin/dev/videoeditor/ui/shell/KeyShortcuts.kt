package dev.videoeditor.ui.shell

import android.view.KeyEvent

enum class EditorShortcut {
    PLAY_PAUSE,
    SHUTTLE_BACK,
    SHUTTLE_STOP,
    SHUTTLE_FORWARD,
    SPLIT_AT_PLAYHEAD,
    UNDO,
    REDO,
    DELETE_SELECTION,
}

/**
 * Hardware-keyboard shortcuts. Pure (Android key codes are compile-time constants) so it is unit-tested on the JVM.
 * Ctrl and Meta are treated alike so Cmd works on keyboards that send it.
 */
object KeyShortcuts {
    fun map(
        keyCode: Int,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
        meta: Boolean = false,
    ): EditorShortcut? {
        val command = ctrl || meta
        if (alt) return null
        if (command) {
            return when (keyCode) {
                KeyEvent.KEYCODE_Z -> if (shift) EditorShortcut.REDO else EditorShortcut.UNDO
                KeyEvent.KEYCODE_Y -> if (shift) null else EditorShortcut.REDO
                else -> null
            }
        }
        if (shift) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> EditorShortcut.PLAY_PAUSE
            KeyEvent.KEYCODE_J -> EditorShortcut.SHUTTLE_BACK
            KeyEvent.KEYCODE_K -> EditorShortcut.SHUTTLE_STOP
            KeyEvent.KEYCODE_L -> EditorShortcut.SHUTTLE_FORWARD
            KeyEvent.KEYCODE_S -> EditorShortcut.SPLIT_AT_PLAYHEAD
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> EditorShortcut.DELETE_SELECTION
            else -> null
        }
    }
}
