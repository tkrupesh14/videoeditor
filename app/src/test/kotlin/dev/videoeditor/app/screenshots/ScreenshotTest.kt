package dev.videoeditor.app.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.videoeditor.app.ai.AiEditController
import dev.videoeditor.timeline.model.CaptionClip
import dev.videoeditor.timeline.model.MediaClip
import dev.videoeditor.timeline.sample.SampleProjects
import dev.videoeditor.ui.shell.AiPanel
import dev.videoeditor.ui.shell.AiPanelActions
import dev.videoeditor.ui.shell.AiPanelState
import dev.videoeditor.ui.shell.AiPhase
import dev.videoeditor.ui.shell.AiSettingsActions
import dev.videoeditor.ui.shell.AiSettingsState
import dev.videoeditor.ui.shell.EditorLayoutMode
import dev.videoeditor.ui.shell.EditorScaffold
import dev.videoeditor.ui.shell.EditorTab
import dev.videoeditor.ui.shell.EditorTheme
import dev.videoeditor.ui.shell.InspectorActions
import dev.videoeditor.ui.shell.InspectorPane
import dev.videoeditor.ui.shell.InspectorState
import dev.videoeditor.ui.shell.MediaBinPane
import dev.videoeditor.ui.shell.MediaBinState
import dev.videoeditor.ui.shell.MediaItemUi
import dev.videoeditor.ui.shell.PreviewPane
import dev.videoeditor.ui.shell.PreviewPaneState
import dev.videoeditor.ui.shell.SettingsScreen
import dev.videoeditor.ui.shell.TopBarActions
import dev.videoeditor.ui.shell.TopBarState
import dev.videoeditor.ui.timeline.Timeline
import dev.videoeditor.ui.timeline.TimelineToolbar
import dev.videoeditor.ui.timeline.TimelineUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshots (Roborazzi + Robolectric native graphics) of the editor with fake state. They only write PNGs when
 * run through `./gradlew :app:recordRoborazziDebug`; plain `test` skips capturing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val project = SampleProjects.twoClipDemo()
    private val clip =
        project.tracks
            .first()
            .clips
            .first() as MediaClip
    private val caption =
        project.tracks
            .last()
            .clips
            .first() as CaptionClip
    private val timelineState = TimelineUiState(project, playheadUs = 1_500_000, selectedClipIds = setOf(clip.id))

    private val media =
        MediaBinState(
            listOf(
                MediaItemUi("asset_1", "demo.mp4", 12_000_000, hasVideo = true, hasAudio = true, missing = false),
                MediaItemUi("asset_2", "beach-sunset.mov", 34_500_000, true, true, false),
                MediaItemUi("asset_3", "voiceover.m4a", 61_000_000, false, true, false),
            ),
        )
    private val inspector =
        InspectorState.Media(
            clipId = clip.id,
            name = "demo.mp4",
            startUs = clip.startUs,
            durationUs = clip.durationUs,
            speed = 1.0,
            volume = 0.8,
            opacity = 1.0,
            hasAudio = true,
            hasVideo = true,
            missing = false,
        )
    private val noActions = InspectorActions({ _, _ -> }, {}, {}, {})

    @Composable
    private fun Editor(
        mode: EditorLayoutMode,
        tab: EditorTab,
        dark: Boolean,
        ai: (@Composable (Modifier) -> Unit)? = null,
    ) {
        EditorTheme(darkTheme = dark) {
            Surface {
                EditorScaffold(
                    layoutMode = mode,
                    topBar = TopBarState("Two clip demo", canUndo = true, canRedo = false, undoLabel = "Split"),
                    topBarActions = TopBarActions({}, {}, {}, {}, {}, {}, {}, {}),
                    selectedTab = tab,
                    onTabSelected = {},
                    onShortcut = {},
                    snackbarHostState = SnackbarHostState(),
                    mediaBin = { m -> MediaBinPane(media, {}, {}, {}, m) },
                    preview = { m ->
                        PreviewPane(
                            PreviewPaneState(false, 1_500_000, 9_000_000, hasContent = true),
                            16f / 9f,
                            {},
                            {},
                            m,
                        ) { pm ->
                            Box(pm.background(Color(0xFF3A4A6B)), contentAlignment = Alignment.Center) {
                                Text("Video frame", color = Color.White)
                            }
                        }
                    },
                    inspector = { m -> InspectorPane(inspector, noActions, m) },
                    timeline = { m ->
                        Column(m) {
                            TimelineToolbar(timelineState, {})
                            Timeline(timelineState, {})
                        }
                    },
                    ai = ai,
                )
            }
        }
    }

    private fun shot(
        name: String,
        mode: EditorLayoutMode,
        tab: EditorTab,
        dark: Boolean = true,
    ) {
        rule.setContent { Editor(mode, tab, dark) }
        rule.waitForIdle()
        captureScreenRoboImage("../docs/screenshots/$name.png")
    }

    @Test fun phonePreviewDark() = shot("phone-preview-dark", EditorLayoutMode.SINGLE_PANE, EditorTab.PREVIEW)

    @Test fun phoneMediaDark() = shot("phone-media-dark", EditorLayoutMode.SINGLE_PANE, EditorTab.MEDIA)

    @Test fun phoneInspectorDark() =
        shot(
            "phone-inspector-sheet-dark",
            EditorLayoutMode.SINGLE_PANE,
            EditorTab.INSPECTOR,
        )

    @Test fun phonePreviewLight() =
        shot(
            "phone-preview-light",
            EditorLayoutMode.SINGLE_PANE,
            EditorTab.PREVIEW,
            dark = false,
        )

    private val aiActions = AiPanelActions({}, {}, {}, {}, {}, {}, {}, {}, {})

    @Test fun phoneAiPanelDark() {
        val state =
            AiPanelState(
                hasKey = true,
                showCreditsNote = false,
                sessionCostLine = "This session: about \$0.18 of \$1.00",
                analysisNote = AiEditController.ANALYSIS_NOTE,
                phase =
                    AiPhase.Pending(
                        title = "Claude's suggestion",
                        summary = "Trimmed the long pause after the intro and added a title caption.",
                        edits = listOf("Cut 0:04.0 to 0:05.0", "Add caption \"Beach day\" at 0:00.0"),
                        costLine = "This request: about \$0.04",
                    ),
            )
        rule.setContent {
            Editor(EditorLayoutMode.SINGLE_PANE, EditorTab.AI, dark = true, ai = { m -> AiPanel(state, aiActions, m) })
        }
        rule.waitForIdle()
        captureScreenRoboImage("../docs/screenshots/phone-ai-panel-dark.png")
    }

    @Test fun phoneAiSettingsNoKeyDark() {
        val state =
            AiSettingsState(
                keyStatus = "No key saved",
                models = listOf("claude-opus-5-5", "claude-haiku-5-5", "claude-sonnet-5-5"),
                model = "claude-opus-5-5",
                sessionSpentLine = "This session: about \$0.00 of \$1.00",
                rollingSpentLine = "Last 30 days: about \$0.00 of \$20.00",
            )
        val actions = AiSettingsActions({ null }, {}, {}, { null }, {}, {}, { _, _ -> }, {})
        rule.setContent { EditorTheme(darkTheme = true) { SettingsScreen(state, actions, onBack = {}) } }
        rule.waitForIdle()
        captureScreenRoboImage("../docs/screenshots/phone-ai-settings-no-key-dark.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi")
    fun tabletDark() = shot("tablet-three-pane-dark", EditorLayoutMode.THREE_PANE, EditorTab.PREVIEW)

    @Test
    @Config(sdk = [35], qualifiers = "w1440dp-h900dp-xhdpi")
    fun laptopDark() = shot("laptop-three-pane-dark", EditorLayoutMode.THREE_PANE, EditorTab.PREVIEW)
}
