package dev.videoeditor.core.media

import android.content.ContentResolver
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads duration/streams/size/fps of a `content://`, `file://` or plain-path media item into [ProbeResult]
 * (and from there a timeline `MediaAsset`). Runs on [Dispatchers.IO]. DEVICE-UNTESTED; the parsing is unit-tested
 * via [ProbeParser].
 */
class MediaProbe(
    context: Context,
) {
    private val appContext = context.applicationContext

    suspend fun probe(uri: String): ProbeResult =
        withContext(Dispatchers.IO) {
            val parsed = parseUri(uri)
            val retriever = MediaMetadataRetriever()
            val raw =
                try {
                    retriever.setDataSource(appContext, parsed)
                    val ex = readExtractor(parsed)
                    RawProbe(
                        durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),
                        hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO),
                        hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO),
                        width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                        height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                        rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
                        mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                        extractorDurationUs = ex.durationUs,
                        extractorFrameRate = ex.frameRate,
                        captureFrameRate =
                            retriever.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE,
                            ),
                        extractorHasVideo = ex.hasVideo,
                        extractorHasAudio = ex.hasAudio,
                    )
                } catch (e: IllegalArgumentException) {
                    throw MediaProbeException("Cannot open $uri: ${e.message}", e)
                } catch (e: SecurityException) {
                    throw MediaProbeException("No permission to read $uri", e)
                } catch (e: RuntimeException) {
                    throw MediaProbeException("Cannot read metadata of $uri: ${e.message}", e)
                } finally {
                    retriever.release()
                }
            ProbeParser.parse(uri, raw, displayName(parsed))
        }

    private class ExtractorInfo(
        val durationUs: Long?,
        val frameRate: Number?,
        val hasVideo: Boolean,
        val hasAudio: Boolean,
    )

    /** Best-effort: extractor failures only lose fps/fallbacks, the retriever result still stands. */
    private fun readExtractor(uri: Uri): ExtractorInfo {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(appContext, uri, null)
            var duration: Long? = null
            var fps: Number? = null
            var video = false
            var audio = false
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (f.containsKey(MediaFormat.KEY_DURATION)) {
                    duration = maxOf(duration ?: 0L, f.getLong(MediaFormat.KEY_DURATION))
                }
                if (mime.startsWith("video/")) {
                    video = true
                    if (fps == null && f.containsKey(MediaFormat.KEY_FRAME_RATE)) fps = frameRateOf(f)
                } else if (mime.startsWith("audio/")) {
                    audio = true
                }
            }
            ExtractorInfo(duration, fps, video, audio)
        } catch (e: java.io.IOException) {
            ExtractorInfo(null, null, false, false)
        } catch (e: RuntimeException) {
            ExtractorInfo(null, null, false, false)
        } finally {
            extractor.release()
        }
    }

    /** KEY_FRAME_RATE may be stored as an int or a float depending on the container. */
    private fun frameRateOf(f: MediaFormat): Number? =
        try {
            f.getInteger(MediaFormat.KEY_FRAME_RATE)
        } catch (e: ClassCastException) {
            runCatching { f.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrNull()
        }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return uri.lastPathSegment
        return runCatching {
            appContext.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }

    companion object {
        /** Plain absolute paths become `file://` Uris. */
        fun parseUri(uri: String): Uri = if (uri.startsWith("/")) Uri.fromFile(java.io.File(uri)) else Uri.parse(uri)
    }
}
