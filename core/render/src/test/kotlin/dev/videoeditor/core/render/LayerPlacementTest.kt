package dev.videoeditor.core.render

import dev.videoeditor.timeline.model.Transform
import org.junit.Assert.assertEquals
import org.junit.Test

class LayerPlacementTest {
    @Test
    fun identityForOutputSizedSource() {
        val p = LayerPlacement.forMedia(Transform(), 1920, 1080, 1920, 1080)
        assertEquals(LayerPlacement(1f, 1f, 0f, 0f, 0f, 1f), p)
    }

    @Test
    fun aspectFitsLargerSource() {
        assertEquals(0.5f, LayerPlacement.forMedia(Transform(), 3840, 2160, 1920, 1080).scaleX, 1e-6f)
        // Portrait into landscape: height-bound.
        assertEquals(0.5625f, LayerPlacement.forMedia(Transform(), 1080, 1920, 1920, 1080).scaleY, 1e-6f)
        // Unknown size: no fit.
        assertEquals(2f, LayerPlacement.forMedia(Transform(scale = 2.0), null, null, 1920, 1080).scaleX, 1e-6f)
    }

    @Test
    fun translateRotateOpacityConvertToNdc() {
        val p =
            LayerPlacement.forCaption(
                Transform(translateX = 0.25, translateY = 0.35, rotationDeg = 90.0, opacity = 1.5),
            )
        assertEquals(0.5f, p.anchorX, 1e-6f)
        assertEquals(-0.7f, p.anchorY, 1e-6f)
        assertEquals(270f, p.rotationDegCcw, 1e-6f)
        assertEquals(1f, p.alpha, 0f)
        assertEquals(0f, LayerPlacement.forCaption(Transform()).rotationDegCcw, 0f)
    }
}
