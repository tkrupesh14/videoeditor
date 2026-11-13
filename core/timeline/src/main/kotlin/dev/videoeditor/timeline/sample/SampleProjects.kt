package dev.videoeditor.timeline.sample

import dev.videoeditor.timeline.model.CaptionClip
import dev.videoeditor.timeline.model.MediaAsset
import dev.videoeditor.timeline.model.MediaClip
import dev.videoeditor.timeline.model.OutputSettings
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.model.Track
import dev.videoeditor.timeline.model.TrackKind

/** Small valid projects for previews, demos and tests. Media URIs are placeholders; nothing is read from disk. */
object SampleProjects {
    /**
     * One 12 s video asset, a video track with two 4 s clips (0-4 s from source 0-4 s, 5-9 s from source
     * 6-10 s, leaving a 1 s gap) and a caption track with one caption at 0.5-2.5 s.
     */
    fun twoClipDemo(
        id: String = "demo",
        assetUri: String = "asset:///demo.mp4",
    ): Project =
        Project(
            id = id,
            name = "Two clip demo",
            nextId = 7,
            output = OutputSettings(1920, 1080, 30),
            assets =
                listOf(
                    MediaAsset(
                        "asset_1",
                        assetUri,
                        12_000_000,
                        hasVideo = true,
                        hasAudio = true,
                        width = 1920,
                        height = 1080,
                        frameRate = 30.0,
                        displayName = "demo.mp4",
                    ),
                ),
            tracks =
                listOf(
                    Track(
                        "track_2",
                        TrackKind.VIDEO,
                        "Video 1",
                        listOf(
                            MediaClip("clip_4", "asset_1", startUs = 0, durationUs = 4_000_000, sourceInUs = 0),
                            MediaClip(
                                "clip_5",
                                "asset_1",
                                startUs = 5_000_000,
                                durationUs = 4_000_000,
                                sourceInUs = 6_000_000,
                            ),
                        ),
                    ),
                    Track(
                        "track_3",
                        TrackKind.CAPTION,
                        "Captions 1",
                        listOf(CaptionClip("clip_6", startUs = 500_000, durationUs = 2_000_000, text = "Hello")),
                    ),
                ),
        )
}
