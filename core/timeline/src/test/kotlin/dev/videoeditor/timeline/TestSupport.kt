package dev.videoeditor.timeline

import dev.videoeditor.timeline.command.Command
import dev.videoeditor.timeline.command.CommandError
import dev.videoeditor.timeline.command.CommandExecutor
import dev.videoeditor.timeline.command.CommandResult
import dev.videoeditor.timeline.model.CaptionClip
import dev.videoeditor.timeline.model.Clip
import dev.videoeditor.timeline.model.MediaAsset
import dev.videoeditor.timeline.model.MediaClip
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.model.Track
import dev.videoeditor.timeline.model.TrackKind
import dev.videoeditor.timeline.validate.ProjectValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail

const val S = 1_000_000L

fun ok(
    p: Project,
    c: Command,
): CommandResult.Success {
    val r = CommandExecutor.execute(p, c)
    if (r !is CommandResult.Success) fail<Unit>("expected success for $c, got $r")
    r as CommandResult.Success
    assertTrue(ProjectValidator.isValid(r.project), "result invalid: ${ProjectValidator.validate(r.project)}")
    return r
}

fun err(
    p: Project,
    c: Command,
): CommandError {
    val r = CommandExecutor.execute(p, c)
    if (r !is CommandResult.Failure) fail<Unit>("expected failure for $c, got $r")
    return (r as CommandResult.Failure).error
}

fun media(
    id: String,
    start: Long,
    dur: Long,
    sourceIn: Long = 0,
    speed: Double = 1.0,
    asset: String = "asset_1",
) = MediaClip(id, asset, start, dur, sourceIn, speed)

fun caption(
    id: String,
    start: Long,
    dur: Long,
    text: String = "text $id",
) = CaptionClip(id, start, dur, text)

/** asset_1: 20 s video+audio; asset_2: 20 s audio only; track_3 VIDEO, track_4 AUDIO, track_5 CAPTION. */
fun fixture(
    video: List<Clip> = emptyList(),
    audio: List<Clip> = emptyList(),
    captions: List<Clip> = emptyList(),
    nextId: Long = 100,
): Project {
    val p =
        Project(
            id = "fx",
            name = "Fixture",
            nextId = nextId,
            assets =
                listOf(
                    MediaAsset(
                        "asset_1",
                        "content://v",
                        20 * S,
                        hasVideo = true,
                        hasAudio = true,
                        width = 1920,
                        height = 1080,
                    ),
                    MediaAsset("asset_2", "content://a", 20 * S, hasVideo = false, hasAudio = true),
                ),
            tracks =
                listOf(
                    Track("track_3", TrackKind.VIDEO, "Video 1", video),
                    Track("track_4", TrackKind.AUDIO, "Audio 1", audio),
                    Track("track_5", TrackKind.CAPTION, "Captions 1", captions),
                ),
        )
    assertEquals(emptyList<Any>(), ProjectValidator.validate(p))
    return p
}

fun Project.clipById(id: String): Clip = clip(id) ?: error("no clip $id")

fun Project.media(id: String): MediaClip = clipById(id) as MediaClip
