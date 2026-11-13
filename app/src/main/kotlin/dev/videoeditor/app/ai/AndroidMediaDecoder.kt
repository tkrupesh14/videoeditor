package dev.videoeditor.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import dev.videoeditor.core.ai.analysis.LumaFrame
import dev.videoeditor.core.media.MediaProbe
import dev.videoeditor.core.media.ThumbnailProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder

/**
 * [MediaDecoder] on the platform: audio via MediaExtractor + MediaCodec (downmixed to mono and box-averaged down to
 * about [targetRateHz], which is plenty for RMS silence detection and keeps memory bounded), frames via the existing
 * [ThumbnailProvider] (MediaMetadataRetriever, nearest sync frame). DEVICE-UNTESTED.
 */
class AndroidMediaDecoder(
    context: Context,
    private val thumbnails: ThumbnailProvider,
    private val targetRateHz: Int = 8_000,
    private val maxSamples: Int = 8_000 * 60 * 60,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MediaDecoder {
    private val appContext = context.applicationContext

    override suspend fun decodeAudio(
        uri: String,
        fromUs: Long,
        toUs: Long,
    ): PcmAudio? =
        withContext(io) {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(appContext, MediaProbe.parseUri(uri), null)
                val track =
                    (0 until extractor.trackCount).firstOrNull {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    } ?: return@withContext null
                val format = extractor.getTrackFormat(track)
                extractor.selectTrack(track)
                extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                codec = c
                c.configure(format, null, null, 0)
                c.start()

                var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var isFloat = false
                var factor = maxOf(1, rate / targetRateHz)
                val out = ShortBuilder()
                var acc = 0.0
                var accN = 0
                var firstUs = -1L
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    coroutineContext.ensureActive()
                    if (!inputDone) {
                        val i = c.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = c.getInputBuffer(i)!!
                            val n = extractor.readSampleData(buf, 0)
                            val t = extractor.sampleTime
                            if (n < 0 || t >= toUs) {
                                c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                c.queueInputBuffer(i, 0, n, t, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = c.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = c.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            isFloat =
                                f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                            factor = maxOf(1, rate / targetRateHz)
                        }
                        o >= 0 -> {
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                            val buf = c.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val bytesPerSample = if (isFloat) 4 else 2
                            val frames = info.size / (bytesPerSample * channels)
                            // Skip audio before fromUs (the seek lands on the previous sync sample).
                            val skip =
                                if (info.presentationTimeUs >= fromUs) {
                                    0
                                } else {
                                    ((fromUs - info.presentationTimeUs) * rate / 1_000_000).toInt().coerceAtMost(frames)
                                }
                            for (fIdx in 0 until frames) {
                                var sum = 0.0
                                for (ch in 0 until channels) {
                                    sum += if (isFloat) buf.float.toDouble() else buf.short / 32768.0
                                }
                                if (fIdx < skip) continue
                                val pts = info.presentationTimeUs + fIdx * 1_000_000L / rate
                                if (pts >= toUs) {
                                    outputDone = true
                                    break
                                }
                                if (firstUs < 0) firstUs = pts
                                acc += sum / channels
                                if (++accN == factor) {
                                    out.add((acc / accN * 32767).toInt().coerceIn(-32768, 32767).toShort())
                                    acc = 0.0
                                    accN = 0
                                    if (out.size >= maxSamples) outputDone = true
                                }
                            }
                            c.releaseOutputBuffer(o, false)
                        }
                    }
                }
                if (out.size == 0 || firstUs < 0) null else PcmAudio(out.toArray(), maxOf(1, rate / factor), firstUs)
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                extractor.release()
            }
        }

    override suspend fun lumaFrame(
        uri: String,
        timeUs: Long,
    ): LumaFrame? {
        val bmp = thumbnails.frameAt(uri, timeUs, LUMA_EDGE) ?: return null
        return withContext(io) {
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val y =
                ByteArray(px.size) {
                    val p = px[it]
                    (((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) shr 8).toByte()
                }
            LumaFrame(timeUs, w, h, y)
        }
    }

    override suspend fun jpegFrame(
        uri: String,
        timeUs: Long,
        maxEdge: Int,
    ): ByteArray? {
        val bmp = thumbnails.frameAt(uri, timeUs, maxEdge) ?: return null
        return withContext(io) {
            // Re-encoding a decoded bitmap carries no EXIF or location metadata.
            ByteArrayOutputStream().use { os ->
                if (bmp.compress(Bitmap.CompressFormat.JPEG, 80, os)) os.toByteArray() else null
            }
        }
    }

    private class ShortBuilder {
        private var data = ShortArray(64 * 1024)
        var size = 0
            private set

        fun add(v: Short) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun toArray(): ShortArray = data.copyOf(size)
    }

    private companion object {
        const val LUMA_EDGE = 64
    }
}
