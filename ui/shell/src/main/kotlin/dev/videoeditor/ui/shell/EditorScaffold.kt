package dev.videoeditor.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.view.PointerIcon as AndroidPointerIcon

private const val MIN_SIDE_PANE_DP = 160f
private const val MAX_SIDE_PANE_DP = 480f

/**
 * Adaptive editor shell. Panes are slots so each can be a stateless composable fed with fake state in tests.
 *
 * - [EditorLayoutMode.SINGLE_PANE] (phones): bottom tabs Media | Preview | Inspector; the timeline is docked under
 *   the preview; the Inspector tab opens a modal bottom sheet over the preview.
 * - [EditorLayoutMode.THREE_PANE] (tablets, laptops): media bin | preview | inspector with draggable splitters and the
 *   timeline docked full-width at the bottom.
 *
 * Hardware-keyboard shortcuts ([KeyShortcuts]) are reported through [onShortcut] in both modes; key events a focused
 * text field consumes never reach it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScaffold(
    layoutMode: EditorLayoutMode,
    topBar: TopBarState,
    topBarActions: TopBarActions,
    selectedTab: EditorTab,
    onTabSelected: (EditorTab) -> Unit,
    onShortcut: (EditorShortcut) -> Unit,
    snackbarHostState: SnackbarHostState,
    mediaBin: @Composable (Modifier) -> Unit,
    preview: @Composable (Modifier) -> Unit,
    inspector: @Composable (Modifier) -> Unit,
    timeline: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Scaffold(
        modifier =
            modifier
                .focusRequester(focus)
                .focusable()
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val s =
                        KeyShortcuts.map(
                            e.key.nativeKeyCode,
                            ctrl = e.isCtrlPressed,
                            shift = e.isShiftPressed,
                            alt = e.isAltPressed,
                            meta = e.isMetaPressed,
                        ) ?: return@onKeyEvent false
                    onShortcut(s)
                    true
                },
        topBar = { EditorTopBar(topBar, topBarActions) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (layoutMode == EditorLayoutMode.SINGLE_PANE) {
                NavigationBar {
                    EditorTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == selectedTab,
                            onClick = { onTabSelected(tab) },
                            icon = { Text(tabGlyph(tab)) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (layoutMode) {
                EditorLayoutMode.SINGLE_PANE ->
                    SinglePane(
                        selectedTab,
                        onTabSelected,
                        mediaBin,
                        preview,
                        inspector,
                        timeline,
                    )
                EditorLayoutMode.THREE_PANE -> ThreePane(mediaBin, preview, inspector, timeline)
            }
        }
    }
}

private fun tabGlyph(tab: EditorTab): String =
    when (tab) {
        EditorTab.MEDIA -> "▦"
        EditorTab.PREVIEW -> "▶"
        EditorTab.INSPECTOR -> "☰"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SinglePane(
    selectedTab: EditorTab,
    onTabSelected: (EditorTab) -> Unit,
    mediaBin: @Composable (Modifier) -> Unit,
    preview: @Composable (Modifier) -> Unit,
    inspector: @Composable (Modifier) -> Unit,
    timeline: @Composable (Modifier) -> Unit,
) {
    if (selectedTab == EditorTab.MEDIA) {
        mediaBin(Modifier.fillMaxSize())
        return
    }
    PreviewWithTimeline(preview, timeline, timelineMaxFraction = 0.5f)
    if (selectedTab == EditorTab.INSPECTOR) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        ModalBottomSheet(onDismissRequest = { onTabSelected(EditorTab.PREVIEW) }, sheetState = sheetState) {
            inspector(Modifier.fillMaxWidth().padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun PreviewWithTimeline(
    preview: @Composable (Modifier) -> Unit,
    timeline: @Composable (Modifier) -> Unit,
    timelineMaxFraction: Float,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxTimeline = maxHeight * timelineMaxFraction
        Column(Modifier.fillMaxSize()) {
            preview(Modifier.weight(1f).fillMaxWidth())
            HorizontalDivider()
            Box(Modifier.fillMaxWidth().heightIn(max = maxTimeline).verticalScroll(rememberScrollState())) {
                timeline(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ThreePane(
    mediaBin: @Composable (Modifier) -> Unit,
    preview: @Composable (Modifier) -> Unit,
    inspector: @Composable (Modifier) -> Unit,
    timeline: @Composable (Modifier) -> Unit,
) {
    var leftDp by rememberSaveable { mutableFloatStateOf(260f) }
    var rightDp by rememberSaveable { mutableFloatStateOf(300f) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxTimeline = maxHeight * 0.45f
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                mediaBin(Modifier.width(leftDp.dp).fillMaxHeight())
                Splitter("Resize media bin") { leftDp = (leftDp + it).coerceIn(MIN_SIDE_PANE_DP, MAX_SIDE_PANE_DP) }
                preview(Modifier.weight(1f).fillMaxHeight())
                Splitter("Resize inspector") { rightDp = (rightDp - it).coerceIn(MIN_SIDE_PANE_DP, MAX_SIDE_PANE_DP) }
                inspector(Modifier.width(rightDp.dp).fillMaxHeight())
            }
            HorizontalDivider()
            Box(Modifier.fillMaxWidth().heightIn(max = maxTimeline).verticalScroll(rememberScrollState())) {
                timeline(Modifier.fillMaxWidth())
            }
        }
    }
}

/** Vertical splitter; reports drag deltas in dp. 16dp wide grab area with a resize cursor for pointers. */
@Composable
private fun Splitter(
    description: String,
    onDragDp: (Float) -> Unit,
) {
    val density = LocalDensity.current.density
    val state = rememberDraggableState { px -> onDragDp(px / density) }
    Box(
        Modifier
            .width(16.dp)
            .fillMaxHeight()
            .pointerHoverIcon(PointerIcon(AndroidPointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW))
            .draggable(state, Orientation.Horizontal)
            .semantics { contentDescription = description },
    ) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant)
                .align(Alignment.Center),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTopBar(
    state: TopBarState,
    actions: TopBarActions,
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = {
            IconButton(
                onClick = actions.onUndo,
                enabled = state.canUndo,
                modifier = Modifier.semantics { contentDescription = state.undoLabel?.let { "Undo $it" } ?: "Undo" },
            ) { Text("↶", style = MaterialTheme.typography.titleLarge) }
            IconButton(
                onClick = actions.onRedo,
                enabled = state.canRedo,
                modifier = Modifier.semantics { contentDescription = state.redoLabel?.let { "Redo $it" } ?: "Redo" },
            ) { Text("↷", style = MaterialTheme.typography.titleLarge) }
            TextButton(onClick = actions.onExport, enabled = state.canExport) { Text("Export") }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.semantics { contentDescription = "More options" },
                ) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Import media") }, onClick = {
                        menuOpen = false
                        actions.onImport()
                    })
                    DropdownMenuItem(text = { Text("Add caption at playhead") }, onClick = {
                        menuOpen = false
                        actions.onAddCaption()
                    })
                    DropdownMenuItem(text = { Text("New project") }, onClick = {
                        menuOpen = false
                        actions.onNewProject()
                    })
                    DropdownMenuItem(text = { Text("Open sample project") }, onClick = {
                        menuOpen = false
                        actions.onOpenSample()
                    })
                    DropdownMenuItem(text = { Text("Settings") }, onClick = {
                        menuOpen = false
                        actions.onOpenSettings()
                    })
                }
            }
        },
    )
}
