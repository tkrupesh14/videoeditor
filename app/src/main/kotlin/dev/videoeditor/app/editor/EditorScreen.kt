package dev.videoeditor.app.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import dev.videoeditor.core.media.ThumbnailProvider
import dev.videoeditor.timeline.model.MediaClip
import dev.videoeditor.ui.shell.EditorLayoutMode
import dev.videoeditor.ui.shell.EditorScaffold
import dev.videoeditor.ui.shell.EditorTab
import dev.videoeditor.ui.shell.ExportDialog
import dev.videoeditor.ui.shell.InspectorActions
import dev.videoeditor.ui.shell.InspectorPane
import dev.videoeditor.ui.shell.MediaBinPane
import dev.videoeditor.ui.shell.PreviewPane
import dev.videoeditor.ui.shell.SettingsScreen
import dev.videoeditor.ui.shell.TopBarActions
import dev.videoeditor.ui.timeline.ClipBlock
import dev.videoeditor.ui.timeline.DefaultClipContent
import dev.videoeditor.ui.timeline.Timeline
import dev.videoeditor.ui.timeline.TimelineToolbar

/** Wires [EditorViewModel] to the shell and timeline. */
@Composable
fun EditorScreen(
    vm: EditorViewModel,
    layoutMode: EditorLayoutMode,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val thumbnails = remember { ThumbnailProvider(context) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            vm.onMediaPicked(uris.map { it.toString() })
        }
    val launchPicker = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            vm.messageShown()
            snackbar.showSnackbar(it)
        }
    }

    if (state.showSettings) {
        BackHandler { vm.closeSettings() }
        SettingsScreen(onBack = vm::closeSettings)
        return
    }
    BackHandler(enabled = state.tab == EditorTab.INSPECTOR) { vm.selectTab(EditorTab.PREVIEW) }

    val topBarActions =
        remember(vm) {
            TopBarActions(
                onUndo = vm::undo,
                onRedo = vm::redo,
                onExport = vm::startExport,
                onImport = { launchPicker() },
                onOpenSample = vm::openSampleProject,
                onNewProject = vm::newProject,
                onOpenSettings = vm::openSettings,
                onAddCaption = vm::addCaptionAtPlayhead,
            )
        }
    val inspectorActions =
        remember(vm) {
            InspectorActions(
                onPropertyChange = vm::onPropertyChange,
                onPropertyChangeFinished = vm::onPropertyChangeFinished,
                onCaptionTextCommit = vm::onCaptionTextCommit,
                onDeleteSelection = vm::deleteSelection,
            )
        }
    val output = state.project.output

    Surface {
        EditorScaffold(
            layoutMode = layoutMode,
            topBar = state.toTopBarState(),
            topBarActions = topBarActions,
            selectedTab = state.tab,
            onTabSelected = vm::selectTab,
            onShortcut = vm::onShortcut,
            snackbarHostState = snackbar,
            mediaBin = { m ->
                MediaBinPane(
                    state = state.toMediaBinState(),
                    onImport = { launchPicker() },
                    onAddToTimeline = vm::addAssetToTimeline,
                    onOpenSample = vm::openSampleProject,
                    modifier = m,
                )
            },
            preview = { m ->
                PreviewPane(
                    state = state.toPreviewPaneState(),
                    aspectRatio = output.width.toFloat() / output.height,
                    onPlayPause = vm::playPause,
                    onSeekToStart = { vm.seek(0L) },
                    modifier = m,
                    player = { pm -> PlayerSurface(vm.preview, pm) },
                )
            },
            inspector = { m -> InspectorPane(state.toInspectorState(), inspectorActions, m) },
            timeline = { m ->
                val tl = state.toTimelineState()
                Column(m) {
                    TimelineToolbar(tl, vm::onTimelineEvent)
                    Timeline(tl, vm::onTimelineEvent, clipContent = { ThumbnailClipContent(it, thumbnails) })
                }
            },
        )
        state.export?.let { ExportDialog(it, onCancel = vm::cancelExport, onDismiss = vm::dismissExport) }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(
    port: PreviewPort,
    modifier: Modifier,
) {
    val player = (port as? Media3PreviewPort)?.controller?.player ?: return
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                this.player = player
            }
        },
        modifier = modifier,
        onRelease = { it.player = null },
    )
}

/** Clip block with a first-frame thumbnail (video clips whose media is present) behind the default label. */
@Composable
private fun BoxScope.ThumbnailClipContent(
    block: ClipBlock,
    thumbnails: ThumbnailProvider,
) {
    val clip = block.clip
    val asset = block.asset
    if (clip is MediaClip && asset != null && asset.hasVideo && !block.missingMedia) {
        val bitmap by produceState<ImageBitmap?>(null, asset.uri, clip.sourceInUs) {
            value = runCatching { thumbnails.frameAt(asset.uri, clip.sourceInUs, 160)?.asImageBitmap() }.getOrNull()
        }
        bitmap?.let {
            Box(
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(16f / 9f)
                    .align(Alignment.CenterStart)
                    .alpha(0.85f),
            ) {
                Image(it, contentDescription = null, contentScale = ContentScale.Crop)
            }
        }
    }
    DefaultClipContent(block)
}
