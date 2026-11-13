package dev.videoeditor.core.ai.analysis

import dev.videoeditor.timeline.model.TimeRange
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Finds silent stretches in mono PCM audio. Pure JVM, no I/O.
 *
 * Audio is measured in windows of [windowUs]; a window is silent when its RMS level is below [thresholdDb]
 * (dBFS, full scale = 0 dB). Consecutive silent windows form a run; runs of at least [minSilenceUs] are
 * reported, shrunk by [paddingUs] on each side so that a little room tone stays around the speech. A run that
 * touches the start or end of the audio is not shrunk on that side.
 *
 * Feed the timeline's mixed-down audio (or one clip's audio with [startUs] = the clip's timeline start) so
 * that the returned ranges are timeline time and can go straight to [SilenceCuts].
 */
class SilenceDetector(
    val thresholdDb: Double = -40.0,
    val minSilenceUs: Long = 500_000,
    val paddingUs: Long = 100_000,
    val windowUs: Long = 20_000,
) {
    init {
        require(thresholdDb < 0.0) { "thresholdDb must be negative (dBFS)" }
        require(minSilenceUs > 0) { "minSilenceUs must be positive" }
        require(paddingUs >= 0) { "paddingUs must be >= 0" }
        require(windowUs > 0) { "windowUs must be positive" }
    }

    /** [samples] are normalized floats in -1.0..1.0. */
    fun detect(
        samples: FloatArray,
        sampleRate: Int,
        startUs: Long = 0,
    ): List<TimeRange> = detect(samples.size, sampleRate, startUs) { samples[it].toDouble() }

    /** [samples] are signed 16-bit PCM. */
    fun detect(
        samples: ShortArray,
        sampleRate: Int,
        startUs: Long = 0,
    ): List<TimeRange> = detect(samples.size, sampleRate, startUs) { samples[it] / 32768.0 }

    private inline fun detect(
        count: Int,
        sampleRate: Int,
        startUs: Long,
        sample: (Int) -> Double,
    ): List<TimeRange> {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(startUs >= 0) { "startUs must be >= 0" }
        if (count == 0) return emptyList()
        val window = maxOf(1L, windowUs * sampleRate / US_PER_S).toInt()
        val totalUs = count.toLong() * US_PER_S / sampleRate
        val ranges = mutableListOf<TimeRange>()
        var runStart = -1
        var i = 0
        while (i < count) {
            val end = minOf(count, i + window)
            var sumSq = 0.0
            for (j in i until end) {
                val s = sample(j)
                sumSq += s * s
            }
            val rms = sqrt(sumSq / (end - i))
            val db = if (rms <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(rms)
            if (db < thresholdDb) {
                if (runStart < 0) runStart = i
            } else if (runStart >= 0) {
                addRun(ranges, runStart, i, sampleRate, totalUs)
                runStart = -1
            }
            i = end
        }
        if (runStart >= 0) addRun(ranges, runStart, count, sampleRate, totalUs)
        return ranges.map { TimeRange(it.startUs + startUs, it.endUs + startUs) }
    }

    private fun addRun(
        out: MutableList<TimeRange>,
        fromSample: Int,
        toSample: Int,
        sampleRate: Int,
        totalUs: Long,
    ) {
        val s = fromSample.toLong() * US_PER_S / sampleRate
        val e = minOf(totalUs, toSample.toLong() * US_PER_S / sampleRate)
        if (e - s < minSilenceUs) return
        val padStart = if (s == 0L) s else s + paddingUs
        val padEnd = if (e == totalUs) e else e - paddingUs
        if (padEnd > padStart) out += TimeRange(padStart, padEnd)
    }

    private companion object {
        const val US_PER_S = 1_000_000L
    }
}
