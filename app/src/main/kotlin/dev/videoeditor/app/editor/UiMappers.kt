package dev.videoeditor.app.editor

import dev.videoeditor.timeline.model.CaptionClip
import dev.videoeditor.timeline.model.MediaClip
import dev.videoeditor.ui.shell.InspectorState
import dev.videoeditor.ui.shell.MediaBinState
import dev.videoeditor.ui.shell.MediaItemUi
import dev.videoeditor.ui.shell.PreviewPaneState
import dev.videoeditor.ui.shell.TopBarState
import dev.videoeditor.ui.timeline.TimelineUiState

// Pure projections of EditorUiState onto the stateless panes' inputs.

fun EditorUiState.toTimelineState(): TimelineUiState =
    TimelineUiState(
        project = project,
        playheadUs = playheadUs,
        selectedClipIds = selection,
        dpPerSecond = dpPerSecond,
        scrollUs = scrollUs,
        snappingEnabled = snapping,
        missingAssetIds = missingAssetIds,
    )

fun EditorUiState.toTopBarState(): TopBarState =
    TopBarState(
        title = project.name,
        canUndo = canUndo,
        canRedo = canRedo,
        undoLabel = undoLabel,
        redoLabel = redoLabel,
        canExport = loaded && export == null,
    )

fun EditorUiState.toMediaBinState(): MediaBinState =
    MediaBinState(
        items =
            project.assets.map { a ->
                MediaItemUi(
                    assetId = a.id,
                    name = a.displayName ?: a.uri.substringAfterLast('/'),
                    durationUs = a.durationUs,
                    hasVideo = a.hasVideo,
                    hasAudio = a.hasAudio,
                    missing = a.id in missingAssetIds,
                )
            },
        importing = importing,
    )

fun EditorUiState.toPreviewPaneState(): PreviewPaneState {
    val playable =
        project.tracks.any { t -> t.clips.any { it is MediaClip && it.assetId !in missingAssetIds } }
    return PreviewPaneState(
        isPlaying = isPlaying,
        positionUs = playheadUs,
        durationUs = project.durationUs,
        hasContent = playable,
        missingMediaCount = missingAssetIds.size,
        error = previewError,
    )
}

fun EditorUiState.toInspectorState(): InspectorState {
    if (selection.size > 1) return InspectorState.Multiple(selection.size)
    val clip = selection.firstOrNull()?.let { project.clip(it) } ?: return InspectorState.Nothing
    return when (clip) {
        is MediaClip -> {
            val asset = project.asset(clip.assetId)
            InspectorState.Media(
                clipId = clip.id,
                name = asset?.displayName ?: clip.assetId,
                startUs = clip.startUs,
                durationUs = clip.durationUs,
                speed = clip.speed,
                volume = clip.volume,
                opacity = clip.transform.opacity,
                hasAudio = asset?.hasAudio ?: true,
                hasVideo = asset?.hasVideo ?: true,
                missing = clip.assetId in missingAssetIds,
            )
        }
        is CaptionClip ->
            InspectorState.Caption(
                clipId = clip.id,
                startUs = clip.startUs,
                durationUs = clip.durationUs,
                text = clip.text,
                opacity = clip.transform.opacity,
            )
    }
}
