package dev.videoeditor.app.editor

import dev.videoeditor.app.ai.AiEditController
import dev.videoeditor.app.ai.FakeAiPorts
import dev.videoeditor.app.ai.FakeKeys
import dev.videoeditor.core.ai.assistant.AiError
import dev.videoeditor.core.ai.assistant.AiResult
import dev.videoeditor.core.ai.spend.MicroUsd
import dev.videoeditor.core.ai.spend.SpendDecision
import dev.videoeditor.core.ai.spend.SpendSnapshot
import dev.videoeditor.core.ai.spend.Usage
import dev.videoeditor.timeline.command.AddCaption
import dev.videoeditor.timeline.command.CaptionSpec
import dev.videoeditor.timeline.model.CaptionClip
import dev.videoeditor.timeline.model.TimeRange
import dev.videoeditor.ui.shell.AI_CREDITS_NOTE
import dev.videoeditor.ui.shell.AiPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val preview = FakePreview()
    private val ports = FakeAiPorts()
    private val twoCaptions =
        arrayOf(
            AddCaption(listOf(CaptionSpec(3_000_000, 4_000_000, "One"))),
            AddCaption(listOf(CaptionSpec(6_000_000, 7_000_000, "Two"))),
        )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(withKey: Boolean = true): EditorViewModel {
        val vm =
            EditorViewModel(
                preview,
                FakeExport(),
                FakeStore(),
                FakeImporter(emptyMap()),
                FakeAvailability(setOf("asset:///demo.mp4")),
                aiPorts = ports,
                ioDispatcher = dispatcher,
            )
        advanceUntilIdle()
        vm.openSampleProject()
        if (withKey) vm.saveApiKey(FakeKeys.console())
        advanceUntilIdle()
        return vm
    }

    private fun EditorViewModel.captions() =
        state.value.project.tracks
            .flatMap { it.clips }
            .filterIsInstance<CaptionClip>()
            .map { it.text }

    @Test
    fun suggestThenAcceptIsOneUndoStep() =
        runTest(dispatcher) {
            ports.respond = { AiResult.Suggested(FakeAiPorts.suggestion(it.newPreview(), *twoCaptions)) }
            val vm = vm()
            val before = vm.state.value.project
            val undoBefore = vm.state.value.undoLabel

            vm.aiSend("add two captions")
            advanceUntilIdle()

            val pending = vm.ai.value.panel.phase as AiPhase.Pending
            assertEquals(listOf("AddCaption", "AddCaption"), pending.edits)
            assertEquals("This request: about \$0.04", pending.costLine)
            assertTrue(vm.state.value.aiLocked)
            // The preview shows the suggestion while the session is untouched.
            assertEquals(listOf("Hello", "One", "Two"), vm.captions().sorted())
            assertEquals(
                listOf("Hello", "One", "Two"),
                preview.projects
                    .last()
                    .first.tracks
                    .flatMap {
                        it.clips
                    }.filterIsInstance<CaptionClip>()
                    .map { it.text }
                    .sorted(),
            )
            assertFalse(
                vm.state.value
                    .toTopBarState()
                    .canUndo,
            )

            // Read-only while pending.
            vm.addCaptionAtPlayhead()
            vm.undo()
            vm.deleteSelection()
            assertEquals(EditorViewModel.READ_ONLY_MESSAGE, vm.state.value.message)
            assertEquals(3, vm.captions().size)

            vm.aiAccept()
            advanceUntilIdle()
            assertFalse(vm.state.value.aiLocked)
            assertEquals(AiPhase.Idle, vm.ai.value.panel.phase)
            assertEquals(3, vm.captions().size)
            assertEquals("AI: add two captions", vm.state.value.undoLabel)

            vm.undo()
            advanceUntilIdle()
            assertEquals(before, vm.state.value.project)
            assertEquals(undoBefore, vm.state.value.undoLabel)
        }

    @Test
    fun rejectRestoresTheTimeline() =
        runTest(dispatcher) {
            ports.respond = { AiResult.Suggested(FakeAiPorts.suggestion(it.newPreview(), *twoCaptions)) }
            val vm = vm()
            val before = vm.state.value
            vm.aiSend("add captions")
            advanceUntilIdle()
            assertEquals(3, vm.captions().size)

            vm.aiReject()
            advanceUntilIdle()
            val after = vm.state.value
            assertEquals(before.project, after.project)
            assertEquals(before.revision, after.revision)
            assertEquals(before.canUndo, after.canUndo)
            assertFalse(after.aiLocked)
            assertNull(after.aiPendingTitle)
            assertEquals(before.project, preview.projects.last().first)
            // Editable again.
            vm.seek(7_000_000)
            vm.addCaptionAtPlayhead()
            assertEquals(2, vm.captions().size)
        }

    @Test
    fun failedShowsFriendlyMessageWithoutTheKey() =
        runTest(dispatcher) {
            val key = FakeKeys.console()
            ports.respond = {
                AiResult.Failed(
                    AiError(AiError.Kind.BadRequest, "bad thing near $key"),
                    null,
                    Usage(0, 0),
                    MicroUsd.ZERO,
                )
            }
            val vm = vm()
            vm.aiSend("do it")
            advanceUntilIdle()
            val m = vm.ai.value.panel.phase as AiPhase.Message
            assertTrue(m.isError)
            assertFalse(m.text.contains(key))
            assertFalse(m.text.contains("sk-ant-api"))
            assertFalse(vm.state.value.aiLocked)

            ports.respond =
                { AiResult.Failed(AiError(AiError.Kind.Auth, "invalid x-api-key"), null, Usage(0, 0), MicroUsd.ZERO) }
            vm.aiSend("again")
            advanceUntilIdle()
            assertEquals(
                AiEditController.friendly(AiError(AiError.Kind.Auth, "")),
                (vm.ai.value.panel.phase as AiPhase.Message).text,
            )
        }

    @Test
    fun refusedAndCapReachedStates() =
        runTest(dispatcher) {
            ports.respond = {
                AiResult.Refused("cyber", null, Usage(10, 0), MicroUsd.ZERO)
            }
            val vm = vm()
            vm.aiSend("something")
            advanceUntilIdle()
            assertEquals("Claude declined", (vm.ai.value.panel.phase as AiPhase.Message).title)

            val snap =
                SpendSnapshot(
                    MicroUsd.ofUsd(1),
                    MicroUsd.ofUsd(1),
                    MicroUsd.ofUsd(1),
                    MicroUsd.ofUsd(20),
                    MicroUsd.ZERO,
                    true,
                )
            ports.respond = {
                AiResult.CapReached(SpendDecision.Denied.Reason.SessionCap, snap, null, Usage(0, 0), MicroUsd.ZERO)
            }
            vm.aiSend("more")
            advanceUntilIdle()
            val m = vm.ai.value.panel.phase as AiPhase.Message
            assertTrue(m.text.startsWith("AI session limit reached (\$1.00 used)."))
            assertTrue(vm.ai.value.panel.capReached)
            assertFalse(vm.state.value.aiLocked)

            // With a partial suggestion: previewed, Accept still works as one step.
            ports.respond = {
                val partial = FakeAiPorts.suggestion(it.newPreview(), twoCaptions[0], complete = false)
                AiResult.CapReached(SpendDecision.Denied.Reason.SessionCap, snap, partial, Usage(0, 0), MicroUsd.ZERO)
            }
            vm.aiSend("partial")
            advanceUntilIdle()
            val p = vm.ai.value.panel.phase as AiPhase.Pending
            assertTrue(p.note!!.contains("partial suggestion"))
            vm.aiAccept()
            assertEquals(2, vm.captions().size)
            vm.undo()
            assertEquals(1, vm.captions().size)
        }

    @Test
    fun cancelWhileWaitingChangesNothing() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<AiResult>()
            ports.respond = { gate.await() }
            val vm = vm()
            val before = vm.state.value.project
            vm.aiSend("slow")
            advanceUntilIdle()
            assertTrue(vm.ai.value.panel.phase is AiPhase.Working)
            assertTrue(vm.state.value.aiLocked)
            vm.addCaptionAtPlayhead()
            assertEquals(before, vm.state.value.project)

            vm.aiCancel()
            advanceUntilIdle()
            assertEquals("Cancelled", (vm.ai.value.panel.phase as AiPhase.Message).title)
            assertFalse(vm.state.value.aiLocked)
            assertEquals(before, vm.state.value.project)
        }

    @Test
    fun consentIsAskedOnceBeforeTheFirstRequest() =
        runTest(dispatcher) {
            ports.preferences.consentVersion = 0
            val vm = vm()
            assertEquals(
                AI_CREDITS_NOTE,
                AiEditController.CONSENT.body
                    .lines()
                    .last(),
            )
            vm.aiSend("first")
            advanceUntilIdle()
            assertNotNull(vm.ai.value.panel.consent)
            assertTrue(ports.instructions.isEmpty())

            vm.aiConsent(false)
            advanceUntilIdle()
            assertNull(vm.ai.value.panel.consent)
            assertTrue(ports.instructions.isEmpty())

            vm.aiSend("first")
            vm.aiConsent(true)
            advanceUntilIdle()
            assertEquals(listOf("first"), ports.instructions)
            assertEquals(AiEditController.CONSENT_VERSION, ports.preferences.consentVersion)
            assertFalse(vm.ai.value.panel.showCreditsNote)
        }

    @Test
    fun sendWithoutKeyAsksForOne() =
        runTest(dispatcher) {
            val vm = vm(withKey = false)
            assertFalse(vm.ai.value.panel.hasKey)
            vm.aiSend("x")
            advanceUntilIdle()
            assertEquals("No API key", (vm.ai.value.panel.phase as AiPhase.Message).title)
            assertTrue(ports.instructions.isEmpty())
        }

    @Test
    fun removeSilencesPreviewsAndAcceptsAsOneStepWithoutApi() =
        runTest(dispatcher) {
            ports.analyzer.silences = listOf(TimeRange(1_000_000, 2_000_000), TimeRange(6_000_000, 6_500_000))
            val vm = vm(withKey = false)
            val before = vm.state.value.project
            assertEquals(9_000_000, before.durationUs)

            vm.removeSilences()
            advanceUntilIdle()
            val p = vm.ai.value.panel.phase as AiPhase.Pending
            assertEquals("Remove silences", p.title)
            assertEquals(2, p.edits.size)
            assertTrue(p.costLine.contains("no API call"))
            assertTrue(vm.state.value.aiLocked)
            assertEquals(7_500_000, vm.state.value.project.durationUs)

            vm.aiReject()
            assertEquals(before, vm.state.value.project)

            vm.removeSilences()
            advanceUntilIdle()
            vm.aiAccept()
            assertEquals(7_500_000, vm.state.value.project.durationUs)
            assertEquals("Remove silences", vm.state.value.undoLabel)
            vm.undo()
            assertEquals(before, vm.state.value.project)
            assertTrue(ports.instructions.isEmpty())
        }

    @Test
    fun removeSilencesWithNothingFound() =
        runTest(dispatcher) {
            val vm = vm()
            vm.removeSilences()
            advanceUntilIdle()
            assertEquals("No silences found", (vm.ai.value.panel.phase as AiPhase.Message).title)
            assertFalse(vm.state.value.aiLocked)
        }

    @Test
    fun keySettingsValidateAndNeverExposeTheKey() =
        runTest(dispatcher) {
            val vm = vm(withKey = false)
            assertEquals(
                "That is a Claude subscription token. This app only works with an API key from the Anthropic Console.",
                vm.validateApiKey(FakeKeys.subscription()),
            )
            assertEquals("That is an Admin key. Create a regular API key instead.", vm.validateApiKey(FakeKeys.admin()))
            assertNull(vm.validateApiKey(FakeKeys.console()))

            vm.saveApiKey(FakeKeys.subscription())
            advanceUntilIdle()
            assertTrue(
                vm.ai.value.settings.keyError!!
                    .contains("subscription token"),
            )
            assertNull(vm.ai.value.settings.keyHint)
            assertEquals(0, vm.ai.value.clipboardClearRequests)

            val key = FakeKeys.console()
            vm.saveApiKey(key)
            advanceUntilIdle()
            val s = vm.ai.value
            assertNotNull(s.settings.keyHint)
            assertTrue(s.panel.hasKey)
            assertEquals(1, s.clipboardClearRequests)
            assertFalse(s.toString().contains(key))
            assertFalse(s.settings.keyHint!!.length >= key.length)

            vm.removeApiKey()
            advanceUntilIdle()
            assertNull(vm.ai.value.settings.keyHint)
            assertFalse(vm.ai.value.panel.hasKey)
        }

    @Test
    fun modelAndCapsSettings() =
        runTest(dispatcher) {
            val vm = vm()
            assertEquals("claude-opus-5-5", vm.ai.value.settings.model)
            assertEquals(
                "claude-opus-5-5",
                vm.ai.value.settings.models
                    .first(),
            )
            vm.selectAiModel("claude-haiku-5-5")
            assertEquals("claude-haiku-5-5", ports.preferences.model)
            vm.selectAiModel("not-a-model")
            assertEquals("claude-haiku-5-5", vm.ai.value.settings.model)

            assertEquals("This session: about \$0.00 of \$1.00", vm.ai.value.panel.sessionCostLine)
            vm.saveSpendCaps("0.05", "20")
            assertNotNull(vm.ai.value.settings.capError)
            vm.saveSpendCaps("2.50", "600")
            assertNotNull(vm.ai.value.settings.capError)
            vm.saveSpendCaps("abc", null)
            assertNotNull(vm.ai.value.settings.capError)

            vm.saveSpendCaps("2.50", null)
            advanceUntilIdle()
            assertNull(vm.ai.value.settings.capError)
            assertEquals("2.50", vm.ai.value.settings.sessionCapUsd)
            assertNull(vm.ai.value.settings.rollingCapUsd)
            assertEquals("This session: about \$0.00 of \$2.50", vm.ai.value.panel.sessionCostLine)
        }

    @Test
    fun newProjectIsBlockedWhilePending() =
        runTest(dispatcher) {
            ports.respond = { AiResult.Suggested(FakeAiPorts.suggestion(it.newPreview(), *twoCaptions)) }
            val vm = vm()
            vm.aiSend("x")
            advanceUntilIdle()
            vm.newProject()
            assertEquals("Two clip demo", vm.state.value.project.name)
            assertEquals(EditorViewModel.READ_ONLY_MESSAGE, vm.state.value.message)
        }
}
