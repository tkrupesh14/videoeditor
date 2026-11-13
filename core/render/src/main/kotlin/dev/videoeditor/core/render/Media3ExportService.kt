package dev.videoeditor.core.render

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import dev.videoeditor.timeline.host.ExportRequest
import dev.videoeditor.timeline.host.ExportService
import dev.videoeditor.timeline.host.ExportState
import dev.videoeditor.timeline.host.ExportStatus
import dev.videoeditor.timeline.model.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Where an export is written. Media3 Transformer only writes to a file path, so a content Uri gets a temp-file copy. */
sealed interface ExportTarget {
    data class FileTarget(
        val file: File,
    ) : ExportTarget

    /** A writable content Uri (e.g. from SAF `CreateDocument` or a MediaStore insert). */
    data class ContentUriTarget(
        val uri: Uri,
    ) : ExportTarget
}

/** Caller-supplied output location for a job; receives the sanitized `.mp4` file name. */
fun interface ExportTargetResolver {
    fun resolve(
        jobId: String,
        fileName: String,
    ): ExportTarget
}

/**
 * [ExportService] over Media3 [Transformer]: MP4, H.264 video + AAC audio, size/fps from the project's output
 * settings (overridable per request). Progress is polled from `Transformer.getProgress` into [progress].
 * DEVICE-UNTESTED: the Media3 calls here have only been compiled, never executed.
 */
@UnstableApi
class Media3ExportService(
    context: Context,
    private val targetResolver: ExportTargetResolver = defaultResolver(context),
    private val pollIntervalMs: Long = 250,
) : ExportService {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = ConcurrentHashMap<String, JobHandle>()

    private class JobHandle(
        val flow: MutableStateFlow<ExportProgress>,
        var transformer: Transformer? = null,
        var poller: Job? = null,
        var outFile: File? = null,
    )

    override suspend fun startExport(
        project: Project,
        request: ExportRequest,
    ): ExportStatus = start(project, request, null)

    /** Like [startExport] but writing to an explicit [target] instead of the resolver's. */
    suspend fun startExport(
        project: Project,
        request: ExportRequest,
        target: ExportTarget,
    ): ExportStatus = start(project, request, target)

    override suspend fun exportStatus(jobId: String): ExportStatus? = jobs[jobId]?.flow?.value?.toStatus()

    /** Live progress for [jobId]; null for an unknown job. Terminal values are final. */
    fun progress(jobId: String): StateFlow<ExportProgress>? = jobs[jobId]?.flow?.asStateFlow()

    /** Convenience: start and observe in one call. */
    suspend fun export(
        project: Project,
        request: ExportRequest,
        target: ExportTarget,
    ): Flow<ExportProgress> = progress(startExport(project, request, target).jobId)!!

    /** Cancels a running job (no-op if finished/unknown). Partial output is deleted. */
    suspend fun cancel(jobId: String) =
        withContext(Dispatchers.Main.immediate) {
            val handle = jobs[jobId] ?: return@withContext
            if (handle.flow.value.isTerminal) return@withContext
            handle.transformer?.cancel()
            handle.outFile?.delete()
            finish(handle, handle.flow.value.copy(state = ExportState.CANCELLED))
        }

    private suspend fun start(
        project: Project,
        request: ExportRequest,
        explicitTarget: ExportTarget?,
    ): ExportStatus =
        withContext(Dispatchers.Main.immediate) {
            val jobId = UUID.randomUUID().toString()
            val handle = JobHandle(MutableStateFlow(ExportProgress(jobId, ExportState.QUEUED)))
            jobs[jobId] = handle
            val settings = ExportSettings.resolve(project.output, request, jobId)
            val plan =
                RenderPlanner.plan(
                    project.copy(output = project.output.copy(settings.width, settings.height, settings.frameRate)),
                )
            if (plan.isEmpty) {
                finish(handle, handle.flow.value.copy(state = ExportState.FAILED, error = ExportError.EmptyProject()))
                return@withContext handle.flow.value.toStatus()
            }
            val target =
                try {
                    explicitTarget ?: targetResolver.resolve(jobId, settings.fileName)
                } catch (e: RuntimeException) {
                    finish(handle, failed(handle, ExportError.Output("Cannot resolve output: ${e.message}")))
                    return@withContext handle.flow.value.toStatus()
                }
            val outFile =
                when (target) {
                    is ExportTarget.FileTarget -> target.file
                    is ExportTarget.ContentUriTarget -> File(appContext.cacheDir, "export-$jobId.mp4")
                }
            val composition =
                try {
                    outFile.parentFile?.mkdirs()
                    CompositionMapper.toComposition(plan)
                } catch (e: RuntimeException) {
                    finish(handle, failed(handle, ExportError.InvalidRequest("Cannot build composition: ${e.message}")))
                    return@withContext handle.flow.value.toStatus()
                }

            val transformer =
                Transformer
                    .Builder(appContext)
                    .setVideoMimeType(settings.videoMimeType)
                    .setAudioMimeType(settings.audioMimeType)
                    .addListener(
                        object : Transformer.Listener {
                            override fun onCompleted(
                                composition: Composition,
                                exportResult: ExportResult,
                            ) {
                                scope.launch { onTransformerCompleted(handle, target, outFile) }
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException,
                            ) {
                                outFile.delete()
                                val err = ExportError.fromMedia3Code(exportException.errorCode, exportException.message)
                                finish(handle, failed(handle, err))
                            }
                        },
                    ).build()
            handle.transformer = transformer
            handle.outFile = outFile
            try {
                transformer.start(composition, outFile.absolutePath)
            } catch (e: RuntimeException) {
                finish(
                    handle,
                    failed(handle, ExportError.InvalidRequest("Transformer rejected the composition: ${e.message}")),
                )
                return@withContext handle.flow.value.toStatus()
            }
            handle.flow.update { it.copy(state = ExportState.RUNNING) }
            handle.poller = scope.launch { poll(handle, transformer) }
            handle.flow.value.toStatus()
        }

    private suspend fun poll(
        handle: JobHandle,
        transformer: Transformer,
    ) {
        val holder = ProgressHolder()
        while (scope.isActive && !handle.flow.value.isTerminal) {
            val state =
                when (transformer.getProgress(holder)) {
                    Transformer.PROGRESS_STATE_AVAILABLE -> TransformerProgressState.AVAILABLE
                    Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY ->
                        TransformerProgressState.WAITING_FOR_AVAILABILITY
                    Transformer.PROGRESS_STATE_UNAVAILABLE -> TransformerProgressState.UNAVAILABLE
                    else -> TransformerProgressState.NOT_STARTED
                }
            handle.flow.update { ProgressMapper.map(it, state, holder.progress) }
            delay(pollIntervalMs)
        }
    }

    private suspend fun onTransformerCompleted(
        handle: JobHandle,
        target: ExportTarget,
        outFile: File,
    ) {
        if (handle.flow.value.isTerminal) return
        val uri =
            when (target) {
                is ExportTarget.FileTarget -> Uri.fromFile(outFile).toString()
                is ExportTarget.ContentUriTarget ->
                    try {
                        withContext(Dispatchers.IO) { copyToUri(outFile, target.uri) }
                        target.uri.toString()
                    } catch (e: IOException) {
                        finish(handle, failed(handle, ExportError.Output("Copy to ${target.uri} failed: ${e.message}")))
                        return
                    } finally {
                        outFile.delete()
                    }
            }
        finish(handle, handle.flow.value.copy(state = ExportState.DONE, fraction = 1.0, outputUri = uri))
    }

    private fun copyToUri(
        file: File,
        uri: Uri,
    ) {
        val out =
            appContext.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open $uri for writing")
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
    }

    private fun failed(
        handle: JobHandle,
        error: ExportError,
    ) = handle.flow.value.copy(state = ExportState.FAILED, error = error)

    private fun finish(
        handle: JobHandle,
        terminal: ExportProgress,
    ) {
        if (handle.flow.value.isTerminal) return
        handle.flow.value = terminal
        handle.poller?.cancel()
        handle.transformer = null
    }

    companion object {
        /** Default: `<app files>/exports/<fileName>`. */
        fun defaultResolver(context: Context): ExportTargetResolver {
            val dir = File(context.applicationContext.filesDir, "exports")
            return ExportTargetResolver { _, fileName -> ExportTarget.FileTarget(File(dir, fileName)) }
        }
    }
}
