package dev.videoeditor.ui.shell

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Immutable

/** Phone (single pane + bottom tabs) or tablet/laptop (three panes with splitters). */
enum class EditorLayoutMode {
    SINGLE_PANE,
    THREE_PANE,
    ;

    companion object {
        fun from(widthSizeClass: WindowWidthSizeClass): EditorLayoutMode =
            if (widthSizeClass == WindowWidthSizeClass.Compact) SINGLE_PANE else THREE_PANE
    }
}

/** Bottom-navigation tabs in [EditorLayoutMode.SINGLE_PANE]. */
enum class EditorTab(
    val label: String,
) {
    MEDIA("Media"),
    PREVIEW("Preview"),
    INSPECTOR("Inspector"),
    AI("AI"),
}

@Immutable
data class TopBarState(
    val title: String,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val undoLabel: String? = null,
    val redoLabel: String? = null,
    val canExport: Boolean = true,
)

class TopBarActions(
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onExport: () -> Unit,
    val onImport: () -> Unit,
    val onOpenSample: () -> Unit,
    val onNewProject: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onAddCaption: () -> Unit,
)

@Immutable
data class MediaItemUi(
    val assetId: String,
    val name: String,
    val durationUs: Long,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val missing: Boolean,
)

@Immutable
data class MediaBinState(
    val items: List<MediaItemUi> = emptyList(),
    val importing: Boolean = false,
)

@Immutable
data class PreviewPaneState(
    val isPlaying: Boolean = false,
    val positionUs: Long = 0L,
    val durationUs: Long = 0L,
    val hasContent: Boolean = false,
    val missingMediaCount: Int = 0,
    val error: String? = null,
)

enum class ClipProperty { SPEED, VOLUME, OPACITY }

@Immutable
sealed interface InspectorState {
    data object Nothing : InspectorState

    data class Multiple(
        val count: Int,
    ) : InspectorState

    data class Media(
        val clipId: String,
        val name: String,
        val startUs: Long,
        val durationUs: Long,
        val speed: Double,
        val volume: Double,
        val opacity: Double,
        val hasAudio: Boolean,
        val hasVideo: Boolean,
        val missing: Boolean,
    ) : InspectorState

    data class Caption(
        val clipId: String,
        val startUs: Long,
        val durationUs: Long,
        val text: String,
        val opacity: Double,
    ) : InspectorState
}

class InspectorActions(
    /** Live value while a slider is dragged; the caller wraps the drag in one undo step. */
    val onPropertyChange: (ClipProperty, Double) -> Unit,
    val onPropertyChangeFinished: () -> Unit,
    val onCaptionTextCommit: (String) -> Unit,
    val onDeleteSelection: () -> Unit,
)

@Immutable
sealed interface ExportDialogState {
    data class Running(
        val fraction: Double?,
    ) : ExportDialogState

    data class Done(
        val outputPath: String,
    ) : ExportDialogState

    data class Failed(
        val message: String,
    ) : ExportDialogState

    data object Cancelled : ExportDialogState
}

/** `m:ss.t` for UI labels. */
fun formatDuration(us: Long): String {
    val tenths = maxOf(0L, us) / 100_000
    val s = tenths / 10
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}.${tenths % 10}"
}
