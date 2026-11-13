package dev.videoeditor.app.editor

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
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
import androidx.compose.runtime.DisposableEffect
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
import dev.videoeditor.ui.shell.AiPanel
import dev.videoeditor.ui.shell.AiPanelActions
import dev.videoeditor.ui.shell.AiPendingBar
import dev.videoeditor.ui.shell.AiSettingsActions
import dev.videoeditor.ui.shell.EditorLayoutMode
import dev.videoeditor.ui.shell.EditorScaffold
import dev.videoeditor.ui.shell.EditorTab
import dev.videoeditor.ui.shell.ExportDialog
import dev.videoeditor.ui.shell.InspectorActions
import dev.videoeditor.ui.shell.InspectorPane
import dev.videoeditor.ui.shell.McpActions
import dev.videoeditor.ui.shell.McpApprovalDialog
import dev.videoeditor.ui.shell.McpExportConfirmDialog
import dev.videoeditor.ui.shell.McpPairingUi
import dev.videoeditor.ui.shell.McpScreen
import dev.videoeditor.ui.shell.McpStatus
import dev.videoeditor.ui.shell.McpStatusBanner
import dev.videoeditor.ui.shell.McpUiState
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
    val ai by vm.ai.collectAsStateWithLifecycle()
    val mcp by vm.mcp.collectAsStateWithLifecycle()
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

    LaunchedEffect(ai.clipboardClearRequests) {
        if (ai.clipboardClearRequests > 0) clearClipboard(context)
    }

    KeepScreenOn(mcp.status is McpStatus.Connected)
    McpDialogs(mcp, vm)
    if (state.showMcp) {
        BackHandler { vm.closeMcp() }
        SecureWindow() // the pairing code is on screen
        val mcpActions =
            remember(vm) {
                McpActions(
                    onAllowChanged = vm::mcpSetAllowed,
                    onStop = vm::mcpStop,
                    onBeginPairing = { vm.mcpBeginPairing() },
                    onCancelPairing = { vm.mcpCancelPairing() },
                    onAnswerPairing = { id, allow, scopes -> vm.mcpAnswerPairing(id, allow, scopes) },
                    onRevoke = { vm.mcpRevoke(it) },
                    onRevokeAll = { vm.mcpRevokeAll() },
                    onAnswerExport = vm::mcpAnswerExport,
                )
            }
        McpScreen(mcp, mcpActions, onBack = vm::closeMcp)
        return
    }
    if (state.showSettings) {
        BackHandler { vm.closeSettings() }
        SecureWindow()
        val settingsActions =
            remember(vm) {
                AiSettingsActions(
                    validateKey = vm::validateApiKey,
                    onSaveKey = vm::saveApiKey,
                    onRemoveKey = vm::removeApiKey,
                    onPaste = { readClipboard(context) },
                    onModelSelected = vm::selectAiModel,
                    onShareFramesChanged = vm::setShareFrames,
                    onSaveCaps = vm::saveSpendCaps,
                    onNewSession = vm::aiNewSession,
                )
            }
        SettingsScreen(ai.settings, settingsActions, onBack = vm::closeSettings)
        return
    }
    BackHandler(enabled = state.tab == EditorTab.INSPECTOR || state.tab == EditorTab.AI) {
        vm.selectTab(EditorTab.PREVIEW)
    }
    val aiActions =
        remember(vm) {
            AiPanelActions(
                onSend = vm::aiSend,
                onCancel = vm::aiCancel,
                onAccept = vm::aiAccept,
                onReject = vm::aiReject,
                onRemoveSilences = vm::removeSilences,
                onDismissMessage = vm::aiDismissMessage,
                onOpenSettings = vm::openSettings,
                onNewSession = vm::aiNewSession,
                onConsent = vm::aiConsent,
            )
        }

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
                onOpenMcp = if (vm.mcp.value.available) vm::openMcp else null,
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
                    state.aiPendingTitle?.let { AiPendingBar(it, vm::aiAccept, vm::aiReject) }
                    TimelineToolbar(tl, vm::onTimelineEvent)
                    Timeline(tl, vm::onTimelineEvent, clipContent = { ThumbnailClipContent(it, thumbnails) })
                }
            },
            ai = { m -> AiPanel(ai.panel, aiActions, m) },
            banner = { McpStatusBanner(mcp.status, onStop = vm::mcpStop, onOpen = vm::openMcp) },
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

/** The pairing approval and remote-export confirmation, shown over whatever screen is open. */
@Composable
private fun McpDialogs(
    mcp: McpUiState,
    vm: EditorViewModel,
) {
    (mcp.pairing as? McpPairingUi.AwaitingApproval)?.let { req ->
        McpApprovalDialog(req) { allow, scopes -> vm.mcpAnswerPairing(req.requestId, allow, scopes) }
    }
    mcp.exportPrompt?.let { p -> McpExportConfirmDialog(p) { allow -> vm.mcpAnswerExport(p.exportId, allow) } }
}

/** FLAG_KEEP_SCREEN_ON while a Claude client is connected (B2.1). */
@Composable
private fun KeepScreenOn(on: Boolean) {
    val context = LocalContext.current
    DisposableEffect(context, on) {
        val window = context.findActivity()?.window
        if (on) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { if (on) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/** FLAG_SECURE while composed (the API key screen): blocks screenshots, recording, casting and the recents thumbnail. */
@Composable
private fun SecureWindow() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/** One user-initiated clipboard read for the Paste button. */
private fun readClipboard(context: Context): CharSequence? {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
    return cm.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
}

/** After a key is saved: the app never leaves the key on the clipboard. */
private fun clearClipboard(context: Context) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        cm.clearPrimaryClip()
    } else {
        cm.setPrimaryClip(ClipData.newPlainText("", ""))
    }
}
