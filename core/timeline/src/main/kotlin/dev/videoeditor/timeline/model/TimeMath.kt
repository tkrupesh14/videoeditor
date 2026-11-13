package dev.videoeditor.timeline.model

/**
 * Integer time math. All timeline/source conversions floor (design D3), so source ranges can shrink by
 * at most 1 us per operation but never grow.
 */
object TimeMath {
    /** Rounds a speed to a multiple of 0.01. */
    fun normalizeSpeed(speed: Double): Double = Math.round(speed * 100) / 100.0

    /** Speed in hundredths, e.g. 1.5 -> 150. The caller must pass a finite, range-checked speed. */
    fun speedCenti(speed: Double): Int = Math.round(speed * 100).toInt()

    fun timelineToSource(
        timelineDeltaUs: Long,
        speedCenti: Int,
    ): Long = Math.floorDiv(timelineDeltaUs * speedCenti, 100L)

    fun sourceToTimeline(
        sourceDeltaUs: Long,
        speedCenti: Int,
    ): Long = Math.floorDiv(sourceDeltaUs * 100L, speedCenti.toLong())

    fun sourceTimeAt(
        clip: MediaClip,
        timelineUs: Long,
    ): Long = clip.sourceInUs + timelineToSource(timelineUs - clip.startUs, clip.speedCenti)

    /** Start time of the frame containing [timeUs]. Idempotent. */
    fun floorToFrame(
        timeUs: Long,
        frameRate: Int,
    ): Long {
        require(frameRate > 0) { "frameRate must be positive" }
        val frame = Math.floorDiv(timeUs * frameRate, 1_000_000L)
        return -Math.floorDiv(-frame * 1_000_000L, frameRate.toLong())
    }

    /** Reduces degrees to [0, 360). The input must be finite. */
    fun normalizeRotation(deg: Double): Double {
        var r = deg % 360.0
        if (r < 0) r += 360.0
        if (r >= 360.0) r = 0.0
        return r + 0.0 // turns -0.0 into 0.0
    }

    /** `m:ss.mmm`, or `h:mm:ss.mmm` from one hour. */
    fun formatTimecode(timeUs: Long): String {
        val sign = if (timeUs < 0) "-" else ""
        val totalMs = Math.abs(timeUs / 1000)
        val ms = totalMs % 1000
        val totalS = totalMs / 1000
        val s = totalS % 60
        val totalM = totalS / 60
        val m = totalM % 60
        val h = totalM / 60
        return if (h > 0) {
            "%s%d:%02d:%02d.%03d".format(sign, h, m, s, ms)
        } else {
            "%s%d:%02d.%03d".format(sign, totalM, s, ms)
        }
    }
}
