package dev.videoeditor.core.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.SpeedParameters
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.AlphaScale
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import dev.videoeditor.timeline.model.TextAlign
import dev.videoeditor.timeline.model.TrackKind
import kotlin.math.ceil

/**
 * Thin Media3 1.11 adapter: [RenderPlan] -> [Composition]. DEVICE-UNTESTED (Media3 runtime cannot run on the JVM).
 *
 * Mapping:
 * - one [EditedMediaItemSequence] per plan track, top-most project track first (Media3's DefaultVideoCompositor is
 *   assumed to draw input 0 on top); gaps via `addGap` with explicit track types;
 * - clip in/out via `ClippingConfiguration` (source time); speed via `EditedMediaItem.setSpeed(SpeedParameters)`
 *   (pitch preserved); volume via a per-item `ChannelMixingAudioProcessor` gain;
 * - per-clip transform/opacity via a time-varying [VideoCompositorSettings] (multi-sequence) or per-item
 *   scale/rotate/alpha effects (single video sequence; translate is NOT applied there - Media3 has no per-item
 *   translate effect that composes cleanly with the fit-to-output presentation);
 * - output size via a composition-level [Presentation]; captions via a composition-level [OverlayEffect] whose
 *   bitmap changes with composition time.
 */
@UnstableApi
object CompositionMapper {
    fun toComposition(plan: RenderPlan): Composition {
        require(!plan.isEmpty) { "RenderPlan has no media; nothing to render" }
        val ordered = plan.tracks.asReversed()
        val videoTrackCount = ordered.count { it.hasVideo }
        val sequences = ordered.map { track -> toSequence(track, plan, applyItemTransforms = videoTrackCount == 1) }

        val compositionVideoEffects =
            mutableListOf<Effect>(
                Presentation.createForWidthAndHeight(
                    plan.output.width,
                    plan.output.height,
                    Presentation.LAYOUT_SCALE_TO_FIT,
                ),
            )
        if (plan.captions.isNotEmpty()) {
            compositionVideoEffects += OverlayEffect(listOf(CaptionOverlay(plan)))
        }
        return Composition
            .Builder(sequences)
            .setVideoCompositorSettings(PlanCompositorSettings(plan, ordered))
            .setEffects(Effects(emptyList(), compositionVideoEffects))
            .build()
    }

    private fun toSequence(
        track: RenderTrack,
        plan: RenderPlan,
        applyItemTransforms: Boolean,
    ): EditedMediaItemSequence {
        val trackTypes =
            buildSet {
                if (track.hasVideo) add(C.TRACK_TYPE_VIDEO)
                if (track.hasAudio) add(C.TRACK_TYPE_AUDIO)
            }
        val builder = EditedMediaItemSequence.Builder(trackTypes)
        for (segment in track.segments) {
            when (segment) {
                is RenderSegment.Gap -> builder.addGap(segment.durationUs)
                is RenderSegment.Media -> builder.addItem(toItem(segment, track.kind, plan, applyItemTransforms))
            }
        }
        return builder.build()
    }

    private fun toItem(
        s: RenderSegment.Media,
        kind: TrackKind,
        plan: RenderPlan,
        applyItemTransforms: Boolean,
    ): EditedMediaItem {
        val mediaItem =
            MediaItem
                .Builder()
                .setUri(Uri.parse(s.uri))
                .setMediaId(s.clipId)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration
                        .Builder()
                        .setStartPositionUs(s.clipStartUs)
                        .setEndPositionUs(s.clipEndUs)
                        .build(),
                ).build()

        val audio = mutableListOf<AudioProcessor>()
        if (s.useAudio && s.volume != 1.0) audio += volumeProcessor(s.volume.toFloat())

        val video = mutableListOf<Effect>()
        if (s.useVideo && applyItemTransforms && kind != TrackKind.AUDIO) {
            val p = LayerPlacement.forMedia(s.transform, null, null, plan.output.width, plan.output.height)
            if (p.scaleX != 1f || p.rotationDegCcw != 0f) {
                video +=
                    ScaleAndRotateTransformation
                        .Builder()
                        .setScale(p.scaleX, p.scaleY)
                        .setRotationDegrees(p.rotationDegCcw)
                        .build()
            }
            if (p.alpha < 1f) video += AlphaScale(p.alpha)
        }

        val builder =
            EditedMediaItem
                .Builder(mediaItem)
                .setRemoveVideo(!s.useVideo)
                .setRemoveAudio(!s.useAudio)
                .setEffects(Effects(audio, video))
        if (s.assetDurationUs > 0) builder.setDurationUs(s.assetDurationUs)
        if (s.speed != 1.0) {
            builder.setSpeed(SpeedParameters(ConstantSpeedProvider(s.speed.toFloat()), true))
        }
        return builder.build()
    }

    /** Linear gain for mono..8-channel input (ChannelMixingAudioProcessor needs a matrix per input count). */
    private fun volumeProcessor(gain: Float): ChannelMixingAudioProcessor =
        ChannelMixingAudioProcessor().apply {
            for (channels in 1..MAX_CHANNELS) {
                putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(channels, channels).scaleBy(gain))
            }
        }

    private const val MAX_CHANNELS = 8
}

@UnstableApi
private class ConstantSpeedProvider(
    private val speed: Float,
) : SpeedProvider {
    override fun getSpeed(timeUs: Long): Float = speed

    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
}

private fun LayerPlacement.toOverlaySettings(): OverlaySettings =
    StaticOverlaySettings
        .Builder()
        .setScale(scaleX, scaleY)
        .setBackgroundFrameAnchor(anchorX, anchorY)
        .setRotationDegrees(rotationDegCcw)
        .setAlphaScale(alpha)
        .build()

/** Per-sequence, time-varying placement; hides a sequence while it is in a gap so blank frames don't cover lower layers. */
@UnstableApi
private class PlanCompositorSettings(
    private val plan: RenderPlan,
    private val orderedTracks: List<RenderTrack>,
) : VideoCompositorSettings {
    override fun getOutputSize(inputSizes: List<Size>): Size = Size(plan.output.width, plan.output.height)

    override fun getOverlaySettings(
        inputId: Int,
        presentationTimeUs: Long,
    ): OverlaySettings {
        val track =
            orderedTracks.filter { it.hasVideo }.getOrNull(inputId) ?: return LayerPlacement.HIDDEN.toOverlaySettings()
        val seg = track.mediaAt(presentationTimeUs) ?: return LayerPlacement.HIDDEN.toOverlaySettings()
        return LayerPlacement
            .forMedia(seg.transform, seg.sourceWidth, seg.sourceHeight, plan.output.width, plan.output.height)
            .toOverlaySettings()
    }
}

/** Renders the active caption (if any) as a bitmap at composition time. */
@UnstableApi
private class CaptionOverlay(
    private val plan: RenderPlan,
) : BitmapOverlay() {
    private val cache = HashMap<String, Bitmap>()
    private val empty: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val span = plan.captions.activeAt(presentationTimeUs) ?: return empty
        return cache.getOrPut(span.clipId) { render(span) }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val span = plan.captions.activeAt(presentationTimeUs) ?: return LayerPlacement.HIDDEN.toOverlaySettings()
        return LayerPlacement.forCaption(span.transform).toOverlaySettings()
    }

    override fun release() {
        super.release()
        cache.values.forEach { it.recycle() }
        cache.clear()
    }

    private fun render(span: CaptionSpan): Bitmap {
        val style = span.style
        val paint =
            TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                textSize = (style.fontSizeFrac * plan.output.height).toFloat().coerceAtLeast(1f)
                color = parseColor(style.color, Color.WHITE)
                val styleFlags = (if (style.bold) Typeface.BOLD else 0) or (if (style.italic) Typeface.ITALIC else 0)
                typeface = Typeface.create(style.fontFamily, styleFlags)
            }
        val maxWidth = (plan.output.width * CAPTION_MAX_WIDTH_FRAC).toInt().coerceAtLeast(1)
        val align =
            when (style.align) {
                TextAlign.START -> Layout.Alignment.ALIGN_NORMAL
                TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
                TextAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
            }
        val layout =
            StaticLayout.Builder
                .obtain(span.text, 0, span.text.length, paint, maxWidth)
                .setAlignment(align)
                .build()
        val pad = (paint.textSize * CAPTION_PAD_FRAC).toInt()
        var textWidth = 0f
        for (i in 0 until layout.lineCount) textWidth = maxOf(textWidth, layout.getLineWidth(i))
        val w = (ceil(textWidth).toInt().coerceAtMost(maxWidth) + 2 * pad).coerceAtLeast(1)
        val h = (layout.height + 2 * pad).coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        style.backgroundColor?.let { canvas.drawColor(parseColor(it, Color.TRANSPARENT)) }
        // The layout is maxWidth wide; shift so the drawn lines sit inside the trimmed bitmap.
        val shift =
            when (align) {
                Layout.Alignment.ALIGN_CENTER -> -(maxWidth - (w - 2 * pad)) / 2f
                Layout.Alignment.ALIGN_OPPOSITE -> -(maxWidth - (w - 2 * pad)).toFloat()
                else -> 0f
            }
        canvas.translate(pad + shift, pad.toFloat())
        layout.draw(canvas)
        return bitmap
    }

    private fun parseColor(
        value: String,
        fallback: Int,
    ): Int = runCatching { Color.parseColor(value) }.getOrDefault(fallback)

    private companion object {
        const val CAPTION_MAX_WIDTH_FRAC = 0.9
        const val CAPTION_PAD_FRAC = 0.25
    }
}
