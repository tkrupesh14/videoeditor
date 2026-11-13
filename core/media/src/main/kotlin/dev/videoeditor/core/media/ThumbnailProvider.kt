package dev.videoeditor.core.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** Pure sizing math for thumbnails. */
object ThumbnailSizing {
    /** Scales (w, h) down so the longer edge is at most [maxEdge]; never upscales; result is at least 1x1. */
    fun fit(
        width: Int,
        height: Int,
        maxEdge: Int,
    ): Pair<Int, Int> {
        require(maxEdge > 0) { "maxEdge must be positive" }
        if (width <= 0 || height <= 0) return maxEdge to maxEdge
        val longest = max(width, height)
        if (longest <= maxEdge) return width to height
        val f = maxEdge.toDouble() / longest
        return (width * f).roundToInt().coerceAtLeast(1) to (height * f).roundToInt().coerceAtLeast(1)
    }

    /** Cache key: time is bucketed to [bucketUs] so scrubbing reuses nearby frames. */
    fun key(
        uri: String,
        timeUs: Long,
        maxEdge: Int,
        bucketUs: Long,
    ): String = "$uri|${if (bucketUs > 0) timeUs / bucketUs else timeUs}|$maxEdge"
}

/**
 * Frame-at-time thumbnails (timeline strips, later AI keyframes). Uses `getScaledFrameAtTime` on API 27+,
 * otherwise a full frame scaled down. Returns null when no frame can be decoded. DEVICE-UNTESTED.
 */
class ThumbnailProvider(
    context: Context,
    cacheBytes: Int = 16 * 1024 * 1024,
    private val bucketUs: Long = 100_000,
) {
    private val appContext = context.applicationContext
    private val cache =
        object : LruCache<String, Bitmap>(cacheBytes) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ): Int = value.byteCount
        }

    suspend fun frameAt(
        uri: String,
        timeUs: Long,
        maxEdge: Int = 256,
    ): Bitmap? {
        val key = ThumbnailSizing.key(uri, timeUs, maxEdge, bucketUs)
        cache.get(key)?.let { return it }
        val bmp = withContext(Dispatchers.IO) { decode(uri, timeUs.coerceAtLeast(0), maxEdge) } ?: return null
        cache.put(key, bmp)
        return bmp
    }

    fun clear() = cache.evictAll()

    private fun decode(
        uri: String,
        timeUs: Long,
        maxEdge: Int,
    ): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(appContext, MediaProbe.parseUri(uri))
            val option = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                // Fits inside the box with aspect ratio kept (rotation-safe, unlike sizing from raw metadata).
                r.getScaledFrameAtTime(timeUs, option, maxEdge, maxEdge)
            } else {
                val full = r.getFrameAtTime(timeUs, option) ?: return null
                val (tw, th) = ThumbnailSizing.fit(full.width, full.height, maxEdge)
                if (tw == full.width && th == full.height) {
                    full
                } else {
                    Bitmap.createScaledBitmap(full, tw, th, true).also { if (it !== full) full.recycle() }
                }
            }
        } catch (e: RuntimeException) {
            null
        } finally {
            r.release()
        }
    }
}
