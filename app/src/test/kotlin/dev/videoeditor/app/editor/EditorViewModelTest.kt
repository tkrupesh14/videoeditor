package dev.videoeditor.app.editor

import dev.videoeditor.app.ai.FakeAiPorts
import dev.videoeditor.timeline.command.ImportMedia
import dev.videoeditor.timeline.command.MoveClip
import dev.videoeditor.timeline.host.ExportState
import dev.videoeditor.timeline.json.ProjectLoadResult
import dev.videoeditor.timeline.json.TimelineJson
import dev.videoeditor.timeline.model.CaptionClip
import dev.videoeditor.timeline.model.MediaClip
import dev.videoeditor.timeline.model.TrackKind
import dev.videoeditor.timeline.sample.SampleProjects
import dev.videoeditor.ui.shell.ClipProperty
import dev.videoeditor.ui.shell.EditorShortcut
import dev.videoeditor.ui.shell.ExportDialogState
import dev.videoeditor.ui.shell.InspectorState
import dev.videoeditor.ui.timeline.TimelineEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val preview = FakePreview()
    private val export = FakeExport()
    private val store = FakeStore()
    private val availability = FakeAvailability()
    private val demoUri = "asset:///demo.mp4"
    private val videoUri = "content://media/video/1"
    private val imageUri = "content://media/image/2"
    private val importer =
        FakeImporter(
            mapOf(
                videoUri to
                    ImportOutcome.Ready(
                        ImportMedia(videoUri, 3_000_000, true, true, 1920, 1080, 30.0, "v1.mp4"),
                    ),
                imageUri to ImportOutcome.Unsupported("Still images can't be added yet. Pick a video instead."),
            ),
        )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.newVm(): EditorViewModel {
        val vm =
            EditorViewModel(preview, export, store, importer, availability, FakeAiPorts(), ioDispatcher = dispatcher)
        advanceUntilIdle()
        return vm
    }

    private fun TestScope.sampleVm(mediaPresent: Boolean = true): EditorViewModel {
        if (mediaPresent) availability.available = setOf(demoUri)
        val vm = newVm()
        vm.openSampleProject()
        advanceUntilIdle()
        return vm
    }

    @Test
    fun startsWithBlankProjectWhenNothingIsSaved() =
        runTest(dispatcher) {
            val s = newVm().state.value
            assertTrue(s.loaded)
            assertEquals(listOf(TrackKind.VIDEO), s.project.tracks.map { it.kind })
            assertFalse(s.canUndo)
        }

    @Test
    fun restoresTheSavedProject() =
        runTest(dispatcher) {
            store.text = TimelineJson.encodeProject(SampleProjects.twoClipDemo(id = "saved"))
            availability.available = setOf(demoUri)
            val s = newVm().state.value
            assertEquals("saved", s.project.id)
            assertTrue(s.missingAssetIds.isEmpty())
            assertEquals(s.project, preview.projects.last().first)
        }

    @Test
    fun corruptSaveFallsBackToNewProjectWithMessage() =
        runTest(dispatcher) {
            store.text = "{ not json"
            val s = newVm().state.value
            assertEquals("project", s.project.id)
            assertTrue(s.message!!.contains("could not be opened"))
        }

    @Test
    fun importAddsAssetAndClipAsOneUndoStep() =
        runTest(dispatcher) {
            availability.available = setOf(videoUri)
            val vm = newVm()
            vm.onMediaPicked(listOf(videoUri))
            advanceUntilIdle()
            val s = vm.state.value
            assertEquals(1, s.project.assets.size)
            val clip =
                s.project.tracks
                    .single { it.kind == TrackKind.VIDEO }
                    .clips
                    .single() as MediaClip
            assertEquals(3_000_000L, clip.durationUs)
            assertEquals("Import v1.mp4", s.undoLabel)
            assertFalse(s.importing)
            assertTrue(
                preview.projects
                    .last()
                    .first.tracks
                    .any { t -> t.clips.isNotEmpty() },
            )
            vm.undo()
            assertTrue(
                vm.state.value.project.assets
                    .isEmpty(),
            )
            assertTrue(
                vm.state.value.project.tracks
                    .all { it.clips.isEmpty() },
            )
            assertFalse(vm.state.value.canUndo)
        }

    @Test
    fun imageImportShowsNotYetAvailableNotice() =
        runTest(dispatcher) {
            val vm = newVm()
            vm.onMediaPicked(listOf(imageUri))
            advanceUntilIdle()
            assertTrue(
                vm.state.value.project.assets
                    .isEmpty(),
            )
            assertTrue(
                vm.state.value.message!!
                    .contains("Still images"),
            )
        }

    @Test
    fun dragGestureIsOneUndoStepAndPreviewWaitsForCommit() =
        runTest(dispatcher) {
            val vm = sampleVm()
            val pushes = preview.projects.size
            vm.onTimelineEvent(TimelineEvent.GestureStart("Move clip"))
            vm.onTimelineEvent(TimelineEvent.GestureUpdate(MoveClip(listOf("clip_5"), toStartUs = 6_000_000)))
            vm.onTimelineEvent(TimelineEvent.GestureUpdate(MoveClip(listOf("clip_5"), toStartUs = 7_000_000)))
            assertEquals(
                7_000_000L,
                vm.state.value.project
                    .clip("clip_5")!!
                    .startUs,
            )
            assertFalse(vm.state.value.canUndo)
            assertEquals(pushes, preview.projects.size)
            vm.onTimelineEvent(TimelineEvent.GestureEnd(commit = true))
            assertEquals(pushes + 1, preview.projects.size)
            assertEquals("Move clip", vm.state.value.undoLabel)
            vm.undo()
            assertEquals(
                5_000_000L,
                vm.state.value.project
                    .clip("clip_5")!!
                    .startUs,
            )
            assertFalse(vm.state.value.canUndo)
        }

    @Test
    fun cancelledGestureRestoresTheProject() =
        runTest(dispatcher) {
            val vm = sampleVm()
            vm.onTimelineEvent(TimelineEvent.GestureStart("Move clip"))
            vm.onTimelineEvent(TimelineEvent.GestureUpdate(MoveClip(listOf("clip_5"), toStartUs = 7_000_000)))
            vm.onTimelineEvent(TimelineEvent.GestureEnd(commit = false))
            assertEquals(
                5_000_000L,
                vm.state.value.project
                    .clip("clip_5")!!
                    .startUs,
            )
            assertFalse(vm.state.value.canUndo)
        }

    @Test
    fun keyboardShortcutsSplitUndoRedoAndDelete() =
        runTest(dispatcher) {
            val vm = sampleVm()

            fun clipCount() =
                vm.state.value.project.tracks
                    .sumOf { it.clips.size }
            vm.seek(2_000_000)
            vm.onShortcut(EditorShortcut.SPLIT_AT_PLAYHEAD)
            assertEquals(5, clipCount()) // clip_4 and the caption were split
            vm.onShortcut(EditorShortcut.UNDO)
            assertEquals(3, clipCount())
            vm.onShortcut(EditorShortcut.REDO)
            assertEquals(5, clipCount())
            vm.onTimelineEvent(TimelineEvent.SelectClip("clip_5", additive = false))
            vm.onShortcut(EditorShortcut.DELETE_SELECTION)
            assertNull(
                vm.state.value.project
                    .clip("clip_5"),
            )
            assertTrue(
                vm.state.value.selection
                    .isEmpty(),
            )
        }

    @Test
    fun longPressSelectionToggles() =
        runTest(dispatcher) {
            val vm = sampleVm()
            vm.onTimelineEvent(TimelineEvent.SelectClip("clip_4", additive = false))
            vm.onTimelineEvent(TimelineEvent.SelectClip("clip_5", additive = true))
            assertEquals(setOf("clip_4", "clip_5"), vm.state.value.selection)
            assertEquals(InspectorState.Multiple(2), vm.state.value.toInspectorState())
            vm.onTimelineEvent(TimelineEvent.SelectClip("clip_4", additive = true))
            assertEquals(setOf("clip_5"), vm.state.value.selection)
        }

    @Test
    fun autosavesAfterDebounce() =
        runTest(dispatcher) {
            val vm = sampleVm()
            store.saves.clear()
            vm.onTimelineEvent(TimelineEvent.Execute(MoveClip(listOf("clip_5"), toStartUs = 6_000_000)))
            advanceTimeBy(100)
            assertTrue(store.saves.isEmpty())
            advanceUntilIdle()
            val saved = TimelineJson.decodeProject(store.saves.last()) as ProjectLoadResult.Success
            assertEquals(vm.state.value.project, saved.project)
        }

    @Test
    fun sampleWithMissingMediaIsFlaggedSkippedInPreviewAndNotExported() =
        runTest(dispatcher) {
            val vm = sampleVm(mediaPresent = false)
            val s = vm.state.value
            assertEquals(setOf("asset_1"), s.missingAssetIds)
            assertTrue(
                s
                    .toMediaBinState()
                    .items
                    .single()
                    .missing,
            )
            assertEquals(1, s.toPreviewPaneState().missingMediaCount)
            assertFalse(s.toPreviewPaneState().hasContent)
            val previewed = preview.projects.last().first
            assertTrue(previewed.tracks.flatMap { it.clips }.none { it is MediaClip })
            assertTrue(previewed.tracks.flatMap { it.clips }.any { it is CaptionClip })
            vm.startExport()
            assertNull(vm.state.value.export)
            assertTrue(
                vm.state.value.message!!
                    .contains("missing"),
            )
            assertTrue(export.started.isEmpty())
        }

    @Test
    fun exportReportsProgressAndResult() =
        runTest(dispatcher) {
            val vm = sampleVm()
            vm.startExport()
            advanceUntilIdle()
            assertEquals("Two_clip_demo.mp4", export.started.single().second)
            export.flow.value = ExportUpdate(ExportState.RUNNING, fraction = 0.5)
            advanceUntilIdle()
            assertEquals(ExportDialogState.Running(0.5), vm.state.value.export)
            export.flow.value = ExportUpdate(ExportState.DONE, 1.0, outputPath = "/movies/Two_clip_demo.mp4")
            advanceUntilIdle()
            assertEquals(ExportDialogState.Done("/movies/Two_clip_demo.mp4"), vm.state.value.export)
            vm.dismissExport()
            assertNull(vm.state.value.export)
        }

    @Test
    fun exportCanBeCancelled() =
        runTest(dispatcher) {
            val vm = sampleVm()
            vm.startExport()
            advanceUntilIdle()
            vm.cancelExport()
            advanceUntilIdle()
            assertEquals(listOf("job-1"), export.cancelled)
            assertEquals(ExportDialogState.Cancelled, vm.state.value.export)
        }

    @Test
    fun inspectorSliderDragIsOneUndoStep() =
        runTest(dispatcher) {
            val vm = sampleVm()
            vm.onTimelineEvent(TimelineEvent.SelectClip("clip_4", additive = false))
            assertTrue(vm.state.value.toInspectorState() is InspectorState.Media)
            vm.onPropertyChange(ClipProperty.SPEED, 1.5)
            vm.onPropertyChange(ClipProperty.SPEED, 2.0)
            vm.onPropertyChangeFinished()
            assertEquals(
                2.0,
                (
                    vm.state.value.project
                        .clip("clip_4") as MediaClip
                ).speed,
                1e-9,
            )
            assertEquals("Change speed", vm.state.value.undoLabel)
            vm.undo()
            assertEquals(
                1.0,
                (
                    vm.state.value.project
                        .clip("clip_4") as MediaClip
                ).speed,
                1e-9,
            )
            assertFalse(vm.state.value.canUndo)
            vm.onPropertyChange(ClipProperty.OPACITY, 0.25)
            vm.onPropertyChangeFinished()
            assertEquals(
                0.25,
                vm.state.value.project
                    .clip("clip_4")!!
                    .transform.opacity,
                1e-9,
            )
        }

    @Test
    fun captionTextIsEditedFromInspector() =
        runTest(dispatcher) {
            val vm = sampleVm()
            vm.onTimelineEvent(TimelineEvent.SelectClip("clip_6", additive = false))
            assertEquals("Hello", (vm.state.value.toInspectorState() as InspectorState.Caption).text)
            vm.onCaptionTextCommit("Hi there")
            assertEquals(
                "Hi there",
                (
                    vm.state.value.project
                        .clip("clip_6") as CaptionClip
                ).text,
            )
        }

    @Test
    fun playPauseDrivesPreviewAndPlayheadFollowsPlayback() =
        runTest(dispatcher) {
            val vm = sampleVm()
            vm.seek(1_000_000)
            vm.playPause()
            advanceUntilIdle()
            assertTrue(vm.state.value.isPlaying)
            preview.state.value = preview.state.value.copy(positionUs = 3_000_000)
            advanceUntilIdle()
            assertEquals(3_000_000L, vm.state.value.playheadUs)
            vm.onShortcut(EditorShortcut.PLAY_PAUSE)
            advanceUntilIdle()
            assertFalse(vm.state.value.isPlaying)
            vm.onShortcut(EditorShortcut.SHUTTLE_BACK)
            assertEquals(2_000_000L, vm.state.value.playheadUs)
            assertTrue(
                vm.state.value.message!!
                    .contains("not available yet"),
            )
        }

    @Test
    fun settingsScreenOpensAndCloses() =
        runTest(dispatcher) {
            val vm = newVm()
            vm.openSettings()
            assertTrue(vm.state.value.showSettings)
            vm.closeSettings()
            assertFalse(vm.state.value.showSettings)
        }
}
