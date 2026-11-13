package dev.videoeditor.core.ai.analysis

import kotlin.math.abs

/** A small grayscale frame: [luma] holds width × height 8-bit values, row-major. */
class LumaFrame(
    val timeUs: Long,
    val width: Int,
    val height: Int,
    val luma: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "frame size must be positive" }
        require(luma.size == width * height) { "luma must hold width * height bytes" }
    }
}

/**
 * Detects hard cuts in a sequence of small luma frames (for example 64×36 thumbnails decoded at a few fps).
 * Pure JVM, no I/O.
 *
 * Each consecutive pair gets a score in 0.0..1.0: [Method.MEAN_ABS_DIFF] is the mean absolute pixel
 * difference / 255; [Method.HISTOGRAM] is half the L1 distance of [HISTOGRAM_BINS]-bin normalized
 * histograms (robust to camera motion, blind to cuts between frames with similar tones). A pair scoring at or
 * above [threshold] is a cut at the later frame's timestamp, unless it is closer than [minSceneUs] to the
 * previous cut (suppresses flashes).
 */
class SceneChangeDetector(
    val threshold: Double = 0.35,
    val method: Method = Method.HISTOGRAM,
    val minSceneUs: Long = 500_000,
) {
    enum class Method { MEAN_ABS_DIFF, HISTOGRAM }

    init {
        require(threshold > 0.0 && threshold <= 1.0) { "threshold must be in (0, 1]" }
        require(minSceneUs >= 0) { "minSceneUs must be >= 0" }
    }

    fun detect(frames: Sequence<LumaFrame>): List<Long> {
        val cuts = mutableListOf<Long>()
        var previous: LumaFrame? = null
        for (frame in frames) {
            val prev = previous
            if (prev != null) {
                require(frame.timeUs > prev.timeUs) { "frames must be in increasing time order" }
                require(frame.width == prev.width && frame.height == prev.height) { "all frames must be the same size" }
                if (score(prev, frame) >= threshold) {
                    val last = cuts.lastOrNull()
                    if (last == null || frame.timeUs - last >= minSceneUs) cuts += frame.timeUs
                }
            }
            previous = frame
        }
        return cuts
    }

    fun detect(frames: List<LumaFrame>): List<Long> = detect(frames.asSequence())

    /** Difference score of two same-size frames, 0.0 (identical) to 1.0. */
    fun score(
        a: LumaFrame,
        b: LumaFrame,
    ): Double =
        when (method) {
            Method.MEAN_ABS_DIFF -> meanAbsDiff(a.luma, b.luma)
            Method.HISTOGRAM -> histogramDistance(a.luma, b.luma)
        }

    companion object {
        const val HISTOGRAM_BINS = 32

        fun meanAbsDiff(
            a: ByteArray,
            b: ByteArray,
        ): Double {
            var sum = 0L
            for (i in a.indices) sum += abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
            return sum.toDouble() / (a.size * 255.0)
        }

        fun histogramDistance(
            a: ByteArray,
            b: ByteArray,
        ): Double {
            val ha = histogram(a)
            val hb = histogram(b)
            var l1 = 0L
            for (i in 0 until HISTOGRAM_BINS) l1 += abs(ha[i] - hb[i])
            // Each histogram sums to size, so the L1 distance is at most 2 * size.
            return l1.toDouble() / (2.0 * a.size)
        }

        private fun histogram(luma: ByteArray): LongArray {
            val h = LongArray(HISTOGRAM_BINS)
            for (v in luma) h[(v.toInt() and 0xFF) * HISTOGRAM_BINS / 256]++
            return h
        }
    }
}
