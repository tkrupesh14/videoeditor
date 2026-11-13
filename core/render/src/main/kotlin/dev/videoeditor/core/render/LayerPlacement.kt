package dev.videoeditor.core.render

import dev.videoeditor.timeline.model.Transform

/**
 * Where one layer lands in the output frame, in Media3 overlay terms (pure, no Android types):
 * anchors are normalized device coordinates (x right, y up, -1..1), rotation is counter-clockwise degrees,
 * scale is relative to the layer's own pixel size.
 */
data class LayerPlacement(
    val scaleX: Float,
    val scaleY: Float,
    val anchorX: Float,
    val anchorY: Float,
    val rotationDegCcw: Float,
    val alpha: Float,
) {
    companion object {
        /** Fully transparent placement used where a track has no clip (gaps must not cover lower tracks). */
        val HIDDEN = LayerPlacement(1f, 1f, 0f, 0f, 0f, 0f)

        /**
         * Media layer: aspect-fit the source into the output (unknown source size counts as already
         * output-sized), then apply the clip [Transform].
         */
        fun forMedia(
            transform: Transform,
            sourceWidth: Int?,
            sourceHeight: Int?,
            outputWidth: Int,
            outputHeight: Int,
        ): LayerPlacement {
            val fit =
                if (sourceWidth != null && sourceHeight != null && sourceWidth > 0 && sourceHeight > 0) {
                    minOf(outputWidth.toDouble() / sourceWidth, outputHeight.toDouble() / sourceHeight)
                } else {
                    1.0
                }
            return fromTransform(transform, fit)
        }

        /** Caption layer: rendered at natural size (font size already relative to output height). */
        fun forCaption(transform: Transform): LayerPlacement = fromTransform(transform, 1.0)

        private fun fromTransform(
            t: Transform,
            baseScale: Double,
        ): LayerPlacement {
            val s = (baseScale * t.scale).toFloat()
            val rot = ((360.0 - t.rotationDeg) % 360.0).toFloat() + 0f
            return LayerPlacement(
                scaleX = s,
                scaleY = s,
                // Model translate is a fraction of the frame size from the centre; NDC spans 2 units. `+ 0.0` drops -0.0.
                anchorX = (t.translateX * 2.0 + 0.0).toFloat(),
                anchorY = (-t.translateY * 2.0 + 0.0).toFloat(),
                rotationDegCcw = rot,
                alpha = t.opacity.coerceIn(0.0, 1.0).toFloat(),
            )
        }
    }
}
