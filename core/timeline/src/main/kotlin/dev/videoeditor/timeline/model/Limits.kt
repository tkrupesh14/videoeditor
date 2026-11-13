package dev.videoeditor.timeline.model

/** Hard limits of the timeline model. See docs/design/timeline.md section 1.1. */
object Limits {
    const val MIN_CLIP_DURATION_US: Long = 10_000
    const val MAX_TIMELINE_US: Long = 86_400_000_000
    const val MIN_SPEED_CENTI: Int = 10
    const val MAX_SPEED_CENTI: Int = 1000
    const val MIN_SPEED: Double = 0.10
    const val MAX_SPEED: Double = 10.0
    const val MAX_VOLUME: Double = 2.0
    const val MIN_SCALE: Double = 0.01
    const val MAX_SCALE: Double = 10.0
    const val MAX_TRANSLATE: Double = 2.0
    const val MAX_CAPTION_CHARS: Int = 2000
    const val MAX_FONT_SIZE_FRAC: Double = 0.5
    const val MIN_OUTPUT_DIM: Int = 16
    const val MAX_OUTPUT_DIM: Int = 8192
    const val MIN_FPS: Int = 1
    const val MAX_FPS: Int = 120
    const val CURRENT_SCHEMA_VERSION: Int = 1
}
