package dev.videoeditor.ui.timeline

import androidx.compose.runtime.Immutable
import dev.videoeditor.timeline.command.Command
import dev.videoeditor.timeline.model.Clip
import dev.videoeditor.timeline.model.MediaAsset
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.model.Track

/** Everything the [Timeline] draws. Owned by the caller (the ViewModel); the timeline only emits [TimelineEvent]s. */
@Immutable
data class TimelineUiState(
    val project: Project,
    val playheadUs: Long = 0L,
    val selectedClipIds: Set<String> = emptySet(),
    val dpPerSecond: Float = TimelineZoom.DEFAULT_DP_PER_SECOND,
    val scrollUs: Long = 0L,
    val snappingEnabled: Boolean = true,
    val missingAssetIds: Set<String> = emptySet(),
)

sealed interface TimelineEvent {
    data class Seek(
        val timeUs: Long,
    ) : TimelineEvent

    /** [additive] toggles the clip in the selection (long-press, Ctrl/Shift-click) instead of replacing it. */
    data class SelectClip(
        val clipId: String,
        val additive: Boolean,
    ) : TimelineEvent

    data object ClearSelection : TimelineEvent

    data class ViewportChanged(
        val dpPerSecond: Float,
        val scrollUs: Long,
    ) : TimelineEvent

    data class SetSnapping(
        val enabled: Boolean,
    ) : TimelineEvent

    /** A one-shot edit (split, delete, ...); the caller executes it as one undo step. */
    data class Execute(
        val command: Command,
    ) : TimelineEvent

    /** A drag began: open an `EditorSession` transaction labelled [label]. */
    data class GestureStart(
        val label: String,
    ) : TimelineEvent

    /** Replace the transaction's pending command (always relative to the project at [GestureStart]). */
    data class GestureUpdate(
        val command: Command,
    ) : TimelineEvent

    /** The drag ended: commit (one undo step) or cancel the transaction. */
    data class GestureEnd(
        val commit: Boolean,
    ) : TimelineEvent
}

/** What the clip-content slot receives (e.g. to draw thumbnails). */
@Immutable
data class ClipBlock(
    val clip: Clip,
    val track: Track,
    val asset: MediaAsset?,
    val selected: Boolean,
    val hovered: Boolean,
    val missingMedia: Boolean,
)
