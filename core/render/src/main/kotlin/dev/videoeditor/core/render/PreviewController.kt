package dev.videoeditor.core.render

import android.content.Context
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import dev.videoeditor.timeline.model.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PreviewState(
    val revision: Long = -1,
    val isPlaying: Boolean = false,
    val positionUs: Long = 0,
    val durationUs: Long = 0,
    val isEmpty: Boolean = true,
    val isReleased: Boolean = false,
    val error: String? = null,
    val planIssues: List<PlanIssue> = emptyList(),
)

/**
 * Preview over Media3 [CompositionPlayer]. Main-thread only. DEVICE-UNTESTED.
 *
 * Call [setProject] whenever the editor revision changes (same revision is a no-op); the composition is rebuilt and
 * the playhead kept. Attach [player] to a `PlayerView` (or call `CompositionPlayer.setVideoSurface`). [release] is
 * idempotent and every call after it is a no-op, so it is safe from `onDestroy`/`DisposableEffect` paths.
 */
@UnstableApi
class PreviewController(
    context: Context,
    private val positionPollMs: Long = 33,
) {
    private val compositionPlayer: CompositionPlayer = CompositionPlayer.Builder(context.applicationContext).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PreviewState())
    private var pollJob: Job? = null
    private var released = false

    val state: StateFlow<PreviewState> = _state.asStateFlow()

    /** The underlying player, for binding a view. Do not release it directly; call [release]. */
    val player: Player get() = compositionPlayer

    private val listener =
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying, positionUs = currentPositionUs()) }
                if (isPlaying) startPolling() else stopPolling()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.update { it.copy(positionUs = currentPositionUs()) }
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.update { it.copy(error = "${error.errorCodeName}: ${error.message}", isPlaying = false) }
            }
        }

    init {
        compositionPlayer.addListener(listener)
    }

    fun setProject(
        project: Project,
        revision: Long,
    ) {
        if (released || revision == _state.value.revision) return
        checkMainThread()
        val plan = RenderPlanner.plan(project, revisionKey = revision.toString())
        val resumeUs = _state.value.positionUs.coerceIn(0, plan.durationUs)
        if (plan.isEmpty) {
            compositionPlayer.stop()
            _state.update {
                PreviewState(revision = revision, isEmpty = true, planIssues = plan.issues)
            }
            return
        }
        val wasPlaying = compositionPlayer.playWhenReady
        try {
            compositionPlayer.setComposition(CompositionMapper.toComposition(plan), resumeUs / 1000)
            compositionPlayer.prepare()
            compositionPlayer.playWhenReady = wasPlaying
            _state.update {
                it.copy(
                    revision = revision,
                    durationUs = plan.durationUs,
                    positionUs = resumeUs,
                    isEmpty = false,
                    error = null,
                    planIssues = plan.issues,
                )
            }
        } catch (e: RuntimeException) {
            _state.update { it.copy(revision = revision, error = "Preview build failed: ${e.message}") }
        }
    }

    fun play() {
        if (released || _state.value.isEmpty) return
        checkMainThread()
        if (compositionPlayer.playbackState == Player.STATE_ENDED) compositionPlayer.seekTo(0)
        compositionPlayer.play()
    }

    fun pause() {
        if (released) return
        checkMainThread()
        compositionPlayer.pause()
    }

    fun seekTo(positionUs: Long) {
        if (released || _state.value.isEmpty) return
        checkMainThread()
        val clamped = positionUs.coerceIn(0, _state.value.durationUs)
        compositionPlayer.seekTo(clamped / 1000)
        _state.update { it.copy(positionUs = clamped) }
    }

    fun release() {
        if (released) return
        released = true
        stopPolling()
        scope.cancel()
        compositionPlayer.removeListener(listener)
        compositionPlayer.release()
        _state.update { it.copy(isPlaying = false, isReleased = true) }
    }

    private fun currentPositionUs(): Long =
        if (released) {
            _state.value.positionUs
        } else {
            compositionPlayer.currentPosition *
                1000
        }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob =
            scope.launch {
                while (isActive && !released) {
                    _state.update { it.copy(positionUs = currentPositionUs()) }
                    delay(positionPollMs)
                }
            }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "PreviewController must be used on the main thread" }
    }
}
