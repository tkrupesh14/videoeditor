package dev.videoeditor.core.ai.assistant

import dev.videoeditor.timeline.host.TranscriptSegment
import dev.videoeditor.timeline.model.TimeRange

/** A downscaled JPEG still (never raw video). Must be at most [Keyframes.MAX_LONG_EDGE_PX] on the long edge. */
class Keyframe(
    val timeUs: Long,
    val jpeg: ByteArray,
) {
    override fun toString(): String = "Keyframe(timeUs=$timeUs, bytes=${jpeg.size})"
}

/**
 * On-device analysis sent with an instruction (docs/design/security.md, A2.5). All times are timeline
 * microseconds. Keyframes are sent only when the user turned on "Let AI see frames"; the caller decides by
 * leaving [keyframes] empty or not.
 */
data class AnalysisBundle(
    val transcript: List<TranscriptSegment> = emptyList(),
    /** Why [transcript] is empty, e.g. `NoTranscriptAvailable.MESSAGE`. Told to Claude as-is. */
    val transcriptUnavailableReason: String? = null,
    val silences: List<TimeRange> = emptyList(),
    val sceneChangesUs: List<Long> = emptyList(),
    val keyframes: List<Keyframe> = emptyList(),
)

/** Keyframe checks: JPEG only, long edge ≤ 512 px, at most [MAX_PER_REQUEST]. */
object Keyframes {
    const val MAX_PER_REQUEST = 8
    const val MAX_LONG_EDGE_PX = 512

    /** Accepted frames, in order, capped at min([max], [MAX_PER_REQUEST]). Anything else is dropped. */
    fun accept(
        frames: List<Keyframe>,
        max: Int,
    ): List<Keyframe> =
        frames
            .filter { f -> jpegSize(f.jpeg)?.let { (w, h) -> maxOf(w, h) <= MAX_LONG_EDGE_PX } == true }
            .take(minOf(max, MAX_PER_REQUEST).coerceAtLeast(0))

    /** (width, height) from the first SOF segment of a baseline or progressive JPEG, or null. */
    fun jpegSize(b: ByteArray): Pair<Int, Int>? {
        fun u(i: Int) = b[i].toInt() and 0xFF
        if (b.size < 4 || u(0) != 0xFF || u(1) != 0xD8) return null
        var i = 2
        while (i + 3 < b.size) {
            if (u(i) != 0xFF) return null
            val marker = u(i + 1)
            if (marker == 0xFF) {
                i++
                continue
            }
            if (marker in 0xD0..0xD9 || marker == 0x01) {
                i += 2
                continue
            }
            val len = (u(i + 2) shl 8) or u(i + 3)
            val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isSof) {
                if (i + 8 >= b.size) return null
                val h = (u(i + 5) shl 8) or u(i + 6)
                val w = (u(i + 7) shl 8) or u(i + 8)
                return if (w > 0 && h > 0) w to h else null
            }
            if (len < 2) return null
            i += 2 + len
        }
        return null
    }
}
