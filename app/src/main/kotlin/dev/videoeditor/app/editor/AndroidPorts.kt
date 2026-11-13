package dev.videoeditor.app.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import dev.videoeditor.core.media.MediaProbe
import dev.videoeditor.core.media.MediaProbeException
import dev.videoeditor.core.render.ExportTarget
import dev.videoeditor.core.render.ExportTargetResolver
import dev.videoeditor.core.render.Media3ExportService
import dev.videoeditor.core.render.PreviewController
import dev.videoeditor.timeline.command.ImportMedia
import dev.videoeditor.timeline.host.ExportRequest
import dev.videoeditor.timeline.host.ExportState
import dev.videoeditor.timeline.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** [PreviewPort] over the Media3 [PreviewController]. Main thread only. */
@OptIn(UnstableApi::class)
class Media3PreviewPort(
    context: Context,
) : PreviewPort {
    val controller = PreviewController(context)
    private val scope = MainScope()

    override val state: StateFlow<PlaybackState> =
        controller.state
            .map { PlaybackState(it.isPlaying, it.positionUs, it.durationUs, it.error) }
            .stateIn(scope, SharingStarted.Eagerly, PlaybackState())

    override fun setProject(
        project: Project,
        key: Long,
    ) = controller.setProject(project, key)

    override fun play() = controller.play()

    override fun pause() = controller.pause()

    override fun seekTo(positionUs: Long) = controller.seekTo(positionUs)

    override fun release() {
        controller.release()
        scope.cancel()
    }
}

/** [ExportPort] over [Media3ExportService], writing to the app-specific Movies directory. */
@OptIn(UnstableApi::class)
class Media3ExportPort(
    context: Context,
) : ExportPort {
    private val appContext = context.applicationContext
    private val service =
        Media3ExportService(
            appContext,
            ExportTargetResolver { _, fileName -> ExportTarget.FileTarget(uniqueFile(outputDir(), fileName)) },
        )

    private fun outputDir(): File =
        (appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(appContext.filesDir, "exports"))
            .also { it.mkdirs() }

    override suspend fun start(
        project: Project,
        fileName: String,
    ): String = service.startExport(project, ExportRequest(fileName = fileName)).jobId

    override fun progress(jobId: String): Flow<ExportUpdate> =
        service.progress(jobId)?.map { p ->
            ExportUpdate(
                state = p.state,
                fraction = p.fraction,
                outputPath = p.outputUri?.let { Uri.parse(it).path ?: it },
                error = p.error?.message,
            )
        } ?: flowOf(ExportUpdate(ExportState.FAILED, error = "Unknown export job"))

    override suspend fun cancel(jobId: String) = service.cancel(jobId)

    private companion object {
        fun uniqueFile(
            dir: File,
            name: String,
        ): File {
            var f = File(dir, name)
            var n = 1
            val stem = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.', "mp4")
            while (f.exists()) f = File(dir, "$stem-${n++}.$ext")
            return f
        }
    }
}

/** Current project JSON at `<files>/projects/current.json`, written atomically. */
class FileProjectStore(
    context: Context,
) : ProjectStore {
    private val file = File(context.applicationContext.filesDir, "projects/current.json")

    override suspend fun load(): String? = if (file.exists()) file.readText() else null

    override suspend fun save(json: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "current.json.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Cannot write ${file.path}")
        }
    }
}

/** Probes picked media; still images are reported as not supported yet. */
class AndroidMediaImporter(
    context: Context,
) : MediaImporter {
    private val appContext = context.applicationContext
    private val probe = MediaProbe(appContext)

    override suspend fun probe(uri: String): ImportOutcome {
        val parsed = Uri.parse(uri)
        val type =
            withContext(Dispatchers.IO) { runCatching { appContext.contentResolver.getType(parsed) }.getOrNull() }
        if (type?.startsWith("image/") == true) {
            return ImportOutcome.Unsupported("Still images can't be added yet. Pick a video instead.")
        }
        // Photo Picker grants survive restarts only if persisted; not every provider allows it.
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(parsed, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            val r = probe.probe(uri)
            if (!r.hasVideo && !r.hasAudio) {
                ImportOutcome.Unsupported("This file has no video or audio stream")
            } else {
                ImportOutcome.Ready(
                    ImportMedia(
                        uri = uri,
                        durationUs = r.durationUs,
                        hasVideo = r.hasVideo,
                        hasAudio = r.hasAudio,
                        width = r.width,
                        height = r.height,
                        frameRate = r.frameRate,
                        displayName = r.displayName,
                    ),
                )
            }
        } catch (e: MediaProbeException) {
            ImportOutcome.Failed(e.message ?: "cannot read the file")
        }
    }
}

/** Checks that an asset uri can be opened (content/file uris, `asset:///` uris and plain paths). */
class AndroidMediaAvailability(
    context: Context,
) : MediaAvailability {
    private val appContext = context.applicationContext

    override suspend fun isAvailable(uri: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                when {
                    uri.startsWith("/") -> File(uri).canRead()
                    uri.startsWith("asset:///") -> appContext.assets.open(uri.removePrefix("asset:///")).use { true }
                    else -> appContext.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
                }
            } catch (e: IOException) {
                false
            } catch (e: SecurityException) {
                false
            } catch (e: IllegalArgumentException) {
                false
            }
        }
}
