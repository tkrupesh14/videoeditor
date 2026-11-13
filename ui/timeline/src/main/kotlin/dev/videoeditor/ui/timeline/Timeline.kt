package dev.videoeditor.ui.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.videoeditor.timeline.command.ClipEdge
import dev.videoeditor.timeline.model.CaptionClip
import dev.videoeditor.timeline.model.MediaClip
import dev.videoeditor.timeline.model.TrackKind
import dev.videoeditor.timeline.model.endUs
import kotlin.math.abs
import kotlin.math.roundToInt
import android.view.PointerIcon as AndroidPointerIcon

/** Minimum touch target (Material guidance); also the width of the edge-trim handles. */
val TimelineTouchTarget = 48.dp
private val RulerHeight = 48.dp
private val TrackHeight = 56.dp
private val SnapThreshold = 12.dp
private val MinTickSpacing = 72.dp
private const val WHEEL_SCROLL_PX = 48f
private const val WHEEL_ZOOM_STEP = 1.15f

private enum class DragMode { PENDING, SCROLL, SCRUB, MOVE, TRIM, PINCH }

private data class MenuAnchor(
    val clipId: String,
    val position: Offset,
)

/**
 * Stateless multi-track timeline: ruler, track rows, clip blocks, playhead. State in ([state]), events out
 * ([onEvent]). Only transient pointer feedback (hover, open context menu, snap guide) is kept locally.
 *
 * Touch: tap selects a clip (or seeks on empty space/ruler), long-press toggles multi-select, drag a clip body to
 * move, drag within 24dp of an edge to trim (48dp targets), drag empty space to scroll, drag the ruler to scrub,
 * pinch to zoom. Pointer: hover highlight, Ctrl/Shift-click multi-select, right-click context menu, wheel scroll,
 * Ctrl+wheel zoom. Drags are reported as GestureStart / GestureUpdate / GestureEnd so the caller can wrap them in
 * one `EditorSession` transaction (one undo step).
 *
 * @param clipContent draws inside each clip block (thumbnails etc.); defaults to the clip's label.
 */
@Composable
fun Timeline(
    state: TimelineUiState,
    onEvent: (TimelineEvent) -> Unit,
    modifier: Modifier = Modifier,
    clipContent: @Composable BoxScope.(ClipBlock) -> Unit = { DefaultClipContent(it) },
) {
    val currentState by rememberUpdatedState(state)
    val emit by rememberUpdatedState(onEvent)
    var hover by remember { mutableStateOf<TimelineHit.OnClip?>(null) }
    var menu by remember { mutableStateOf<MenuAnchor?>(null) }
    var snapGuideUs by remember { mutableStateOf<Long?>(null) }
    val textMeasurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val tracks = TimelineHitTest.displayTracks(state.project)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(RulerHeight + TrackHeight * maxOf(1, tracks.size))
            .clipToBounds()
            .background(colors.surfaceContainerLow)
            .semantics { contentDescription = "Timeline" },
    ) {
        val pxPerDp = LocalDensity.current.density
        val metrics =
            TimelineMetrics(
                rulerHeightPx = RulerHeight.value * pxPerDp,
                trackHeightPx = TrackHeight.value * pxPerDp,
                handleHalfPx = TimelineTouchTarget.value * pxPerDp / 2f,
                snapThresholdPx = SnapThreshold.value * pxPerDp,
            )
        val geometry = TimelineGeometry(state.dpPerSecond * pxPerDp, state.scrollUs)
        val widthPx = constraints.maxWidth.toFloat()

        fun geom() = TimelineGeometry(currentState.dpPerSecond * pxPerDp, currentState.scrollUs)

        fun thresholdUs() = if (currentState.snappingEnabled) geom().pxToDurationUs(metrics.snapThresholdPx) else 0L

        val gestureModifier =
            Modifier
                .pointerInput(pxPerDp) {
                    // Pointer-only: hover, wheel, right-click. Touch gestures are handled below.
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            val s = currentState
                            when (event.type) {
                                PointerEventType.Enter, PointerEventType.Move -> {
                                    if (!event.changes.any { it.pressed }) {
                                        hover =
                                            TimelineHitTest.hitTest(
                                                s.project,
                                                geom(),
                                                metrics,
                                                change.position.x,
                                                change.position.y,
                                            ) as? TimelineHit.OnClip
                                    }
                                }
                                PointerEventType.Exit -> hover = null
                                PointerEventType.Scroll -> {
                                    val d = change.scrollDelta
                                    if (event.keyboardModifiers.isCtrlPressed) {
                                        val factor = if (d.y < 0) WHEEL_ZOOM_STEP else 1f / WHEEL_ZOOM_STEP
                                        val (z, sc) =
                                            TimelineZoom.zoomAround(
                                                s.dpPerSecond,
                                                s.scrollUs,
                                                factor,
                                                change.position.x,
                                                pxPerDp,
                                                s.project.durationUs,
                                            )
                                        emit(TimelineEvent.ViewportChanged(z, sc))
                                    } else {
                                        val dx = (if (d.x != 0f) d.x else d.y) * WHEEL_SCROLL_PX
                                        val sc = s.scrollUs + geom().pxToDurationUs(dx)
                                        emit(
                                            TimelineEvent.ViewportChanged(
                                                s.dpPerSecond,
                                                TimelineZoom.clampScroll(sc, s.project.durationUs),
                                            ),
                                        )
                                    }
                                    change.consume()
                                }
                                PointerEventType.Press -> {
                                    if (event.buttons.isSecondaryPressed) {
                                        val id =
                                            TimelineHitTest.clipIdAt(
                                                s.project,
                                                geom(),
                                                metrics,
                                                change.position.x,
                                                change.position.y,
                                            )
                                        if (id != null) {
                                            if (id !in s.selectedClipIds) emit(TimelineEvent.SelectClip(id, false))
                                            menu = MenuAnchor(id, change.position)
                                        }
                                        change.consume()
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                }.pointerInput(pxPerDp) {
                    val longPressMs = viewConfiguration.longPressTimeoutMillis
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (currentEvent.buttons.isSecondaryPressed) return@awaitEachGesture
                        val mods = currentEvent.keyboardModifiers
                        val additiveClick = mods.isCtrlPressed || mods.isShiftPressed || mods.isMetaPressed
                        val base = currentState
                        val baseGeom = geom()
                        val hit =
                            TimelineHitTest.hitTest(
                                base.project,
                                baseGeom,
                                metrics,
                                down.position.x,
                                down.position.y,
                            )
                        var mode = DragMode.PENDING
                        var longPressed = false
                        var lastTime = down.uptimeMillis
                        var moveIds: Set<String> = emptySet()
                        var pinchZoom = base.dpPerSecond
                        var pinchScroll = base.scrollUs
                        var transactionOpen = false
                        try {
                            while (true) {
                                val remaining = down.uptimeMillis + longPressMs - lastTime
                                val event =
                                    if (mode == DragMode.PENDING && !longPressed) {
                                        withTimeoutOrNull(maxOf(0L, remaining)) { awaitPointerEvent() }
                                    } else {
                                        awaitPointerEvent()
                                    }
                                if (event == null) {
                                    longPressed = true
                                    if (hit is TimelineHit.OnClip) emit(TimelineEvent.SelectClip(hit.clipId, true))
                                    continue
                                }
                                lastTime = event.changes.maxOf { it.uptimeMillis }
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) {
                                    if (mode == DragMode.PENDING && !longPressed) {
                                        when (hit) {
                                            is TimelineHit.OnClip ->
                                                emit(TimelineEvent.SelectClip(hit.clipId, additiveClick))
                                            is TimelineHit.Ruler -> emit(TimelineEvent.Seek(hit.timeUs))
                                            is TimelineHit.Empty -> {
                                                emit(TimelineEvent.ClearSelection)
                                                emit(TimelineEvent.Seek(hit.timeUs))
                                            }
                                        }
                                    }
                                    break
                                }
                                if (pressed.size >= 2) {
                                    if (transactionOpen) {
                                        emit(TimelineEvent.GestureEnd(commit = false))
                                        transactionOpen = false
                                        snapGuideUs = null
                                    }
                                    mode = DragMode.PINCH
                                    val zoom = event.calculateZoom()
                                    val centroid = event.calculateCentroid(useCurrent = true)
                                    val pan = event.calculatePan()
                                    if (zoom != 1f) {
                                        val (z, sc) =
                                            TimelineZoom.zoomAround(
                                                pinchZoom,
                                                pinchScroll,
                                                zoom,
                                                centroid.x,
                                                pxPerDp,
                                                base.project.durationUs,
                                            )
                                        pinchZoom = z
                                        pinchScroll = sc
                                    }
                                    pinchScroll =
                                        TimelineZoom.clampScroll(
                                            pinchScroll -
                                                TimelineGeometry(
                                                    pinchZoom * pxPerDp,
                                                    0,
                                                ).pxToDurationUs(pan.x),
                                            base.project.durationUs,
                                        )
                                    emit(TimelineEvent.ViewportChanged(pinchZoom, pinchScroll))
                                    event.changes.forEach { it.consume() }
                                    continue
                                }
                                if (mode == DragMode.PINCH) continue
                                val change = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                                val dx = change.position.x - down.position.x
                                val dy = change.position.y - down.position.y
                                if (mode == DragMode.PENDING) {
                                    if (abs(dx) < slop && abs(dy) < slop) continue
                                    // Mostly-vertical drag off a clip: leave it unconsumed for a parent vertical scroll.
                                    if (hit !is TimelineHit.OnClip && abs(dy) > abs(dx)) break
                                    mode =
                                        when {
                                            hit is TimelineHit.Ruler -> DragMode.SCRUB
                                            hit is TimelineHit.OnClip && hit.part != ClipPart.BODY -> {
                                                emit(TimelineEvent.SelectClip(hit.clipId, false))
                                                emit(TimelineEvent.GestureStart("Trim clip"))
                                                transactionOpen = true
                                                DragMode.TRIM
                                            }
                                            hit is TimelineHit.OnClip -> {
                                                moveIds =
                                                    if (hit.clipId in base.selectedClipIds) {
                                                        base.selectedClipIds
                                                    } else {
                                                        emit(TimelineEvent.SelectClip(hit.clipId, false))
                                                        setOf(hit.clipId)
                                                    }
                                                emit(
                                                    TimelineEvent.GestureStart(
                                                        if (moveIds.size > 1) "Move clips" else "Move clip",
                                                    ),
                                                )
                                                transactionOpen = true
                                                DragMode.MOVE
                                            }
                                            else -> DragMode.SCROLL
                                        }
                                }
                                val deltaUs = baseGeom.pxToDurationUs(dx)
                                when (mode) {
                                    DragMode.SCROLL ->
                                        emit(
                                            TimelineEvent.ViewportChanged(
                                                base.dpPerSecond,
                                                TimelineZoom.clampScroll(
                                                    base.scrollUs - deltaUs,
                                                    base.project.durationUs,
                                                ),
                                            ),
                                        )
                                    DragMode.SCRUB ->
                                        emit(TimelineEvent.Seek(baseGeom.xToUs(change.position.x).coerceAtLeast(0L)))
                                    DragMode.MOVE -> {
                                        val target = TimelineHitTest.trackAtY(base.project, metrics, change.position.y)
                                        TimelineEdits
                                            .move(
                                                base.project,
                                                moveIds,
                                                deltaUs,
                                                target?.id,
                                                base.playheadUs,
                                                thresholdUs(),
                                            )?.let {
                                                snapGuideUs = it.snappedTo?.timeUs
                                                emit(TimelineEvent.GestureUpdate(it.command))
                                            }
                                    }
                                    DragMode.TRIM -> {
                                        val onClip = hit as TimelineHit.OnClip
                                        val edge =
                                            if (onClip.part ==
                                                ClipPart.START_EDGE
                                            ) {
                                                ClipEdge.START
                                            } else {
                                                ClipEdge.END
                                            }
                                        TimelineEdits
                                            .trim(
                                                base.project,
                                                onClip.clipId,
                                                edge,
                                                deltaUs,
                                                base.playheadUs,
                                                thresholdUs(),
                                            )?.let {
                                                snapGuideUs = it.snappedTo?.timeUs
                                                emit(TimelineEvent.GestureUpdate(it.command))
                                            }
                                    }
                                    else -> Unit
                                }
                                change.consume()
                            }
                            if (transactionOpen) {
                                emit(TimelineEvent.GestureEnd(commit = true))
                                transactionOpen = false
                            }
                        } finally {
                            snapGuideUs = null
                            if (transactionOpen) emit(TimelineEvent.GestureEnd(commit = false))
                        }
                    }
                }

        val hoverIcon =
            if (hover?.part == ClipPart.START_EDGE || hover?.part == ClipPart.END_EDGE) {
                PointerIcon(AndroidPointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW)
            } else {
                PointerIcon.Default
            }

        Box(Modifier.matchParentSize().then(gestureModifier).pointerHoverIcon(hoverIcon)) {
            // Ruler + row backgrounds.
            Canvas(Modifier.matchParentSize()) {
                val step = TimelineZoom.rulerStepUs(geometry.pxPerSecond, MinTickSpacing.toPx())
                val first = (geometry.scrollUs / step) * step
                var t = first
                val label = TextStyle(color = colors.onSurfaceVariant, fontSize = 11.sp)
                while (geometry.usToX(t) < size.width) {
                    val x = geometry.usToX(t)
                    if (x >= 0f) {
                        drawLine(
                            colors.outline,
                            Offset(x, metrics.rulerHeightPx * 0.55f),
                            Offset(x, metrics.rulerHeightPx),
                            strokeWidth = 1.dp.toPx(),
                        )
                        drawText(
                            textMeasurer,
                            TimelineZoom.formatTime(t, step),
                            Offset(x + 3.dp.toPx(), 4.dp.toPx()),
                            label,
                        )
                    }
                    t += step
                }
                tracks.forEachIndexed { i, _ ->
                    val top = metrics.rowTopPx(i)
                    drawLine(colors.outlineVariant, Offset(0f, top), Offset(size.width, top), 1.dp.toPx())
                }
            }
            tracks.forEachIndexed { row, track ->
                Text(
                    track.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier =
                        Modifier
                            .offset {
                                IntOffset(
                                    4.dp.roundToPx(),
                                    metrics.rowTopPx(row).roundToInt() + 2.dp.roundToPx(),
                                )
                            },
                )
                for (clip in track.clips) {
                    val sx = geometry.usToX(clip.startUs)
                    val ex = geometry.usToX(clip.endUs)
                    if (ex < 0f || sx > widthPx) continue
                    val left = sx.coerceAtLeast(-pxPerDp * 4)
                    val right = ex.coerceAtMost(widthPx + pxPerDp * 4)
                    val asset = (clip as? MediaClip)?.let { state.project.asset(it.assetId) }
                    val block =
                        ClipBlock(
                            clip = clip,
                            track = track,
                            asset = asset,
                            selected = clip.id in state.selectedClipIds,
                            hovered = hover?.clipId == clip.id,
                            missingMedia = asset != null && asset.id in state.missingAssetIds,
                        )
                    ClipBlockView(
                        block = block,
                        clipContent = clipContent,
                        modifier =
                            Modifier
                                .offset {
                                    IntOffset(
                                        left.roundToInt(),
                                        (metrics.rowTopPx(row) + 16.dp.toPx()).roundToInt(),
                                    )
                                }.width(((right - left) / pxPerDp).dp)
                                .height(TrackHeight - 20.dp),
                    )
                }
            }
            // Playhead + snap guide on top.
            Canvas(Modifier.matchParentSize()) {
                snapGuideUs?.let {
                    val x = geometry.usToX(it)
                    drawLine(colors.tertiary, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                }
                val px = geometry.usToX(state.playheadUs)
                if (px in 0f..size.width) {
                    drawLine(colors.primary, Offset(px, 0f), Offset(px, size.height), 2.dp.toPx())
                    drawCircle(colors.primary, 6.dp.toPx(), Offset(px, metrics.rulerHeightPx - 6.dp.toPx()))
                }
            }
            menu?.let { anchor ->
                Box(
                    Modifier
                        .offset {
                            IntOffset(
                                anchor.position.x.roundToInt(),
                                anchor.position.y.roundToInt(),
                            )
                        }.size(1.dp),
                ) {
                    ClipContextMenu(
                        state = state,
                        clipId = anchor.clipId,
                        onDismiss = { menu = null },
                        onEvent = {
                            menu = null
                            emit(it)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipContextMenu(
    state: TimelineUiState,
    clipId: String,
    onDismiss: () -> Unit,
    onEvent: (TimelineEvent) -> Unit,
) {
    val clip = state.project.clip(clipId) ?: return
    val ids = if (clipId in state.selectedClipIds) state.selectedClipIds else setOf(clipId)
    val canSplit = clip.startUs < state.playheadUs && state.playheadUs < clip.endUs
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Split at playhead") },
            enabled = canSplit,
            onClick = {
                TimelineEdits.splitAtPlayhead(state.project, ids, state.playheadUs)?.let {
                    onEvent(TimelineEvent.Execute(it))
                }
            },
        )
        DropdownMenuItem(
            text = { Text(if (ids.size > 1) "Delete ${ids.size} clips" else "Delete") },
            onClick = { TimelineEdits.deleteSelection(ids)?.let { onEvent(TimelineEvent.Execute(it)) } },
        )
        DropdownMenuItem(
            text = { Text("Move playhead here") },
            onClick = { onEvent(TimelineEvent.Seek(clip.startUs)) },
        )
    }
}

@Composable
private fun ClipBlockView(
    block: ClipBlock,
    clipContent: @Composable BoxScope.(ClipBlock) -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    val fill =
        when {
            block.missingMedia -> colors.errorContainer
            block.track.kind == TrackKind.CAPTION -> colors.tertiaryContainer
            block.track.kind == TrackKind.AUDIO -> colors.secondaryContainer
            else -> colors.primaryContainer
        }
    val borderColor =
        when {
            block.selected -> colors.primary
            block.hovered -> colors.outline
            else -> Color.Transparent
        }
    Box(
        modifier
            .clip(shape)
            .background(fill)
            .border(if (block.selected) 2.dp else 1.dp, borderColor, shape),
    ) {
        clipContent(block)
        if (block.selected || block.hovered) {
            val handle = if (block.selected) colors.primary else colors.outline
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(handle),
            )
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(handle),
            )
        }
    }
}

/** Default clip-block content: the caption text or the asset name, plus a "missing" tag. */
@Composable
fun BoxScope.DefaultClipContent(block: ClipBlock) {
    val label =
        when (val c = block.clip) {
            is CaptionClip -> c.text
            is MediaClip -> block.asset?.displayName ?: c.assetId
        }
    Text(
        text = if (block.missingMedia) "Missing: $label" else label,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.let { if (block.missingMedia) it.onErrorContainer else it.onSurface },
        modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 8.dp),
    )
}
