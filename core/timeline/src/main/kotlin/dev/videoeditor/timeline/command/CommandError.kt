package dev.videoeditor.timeline.command

import dev.videoeditor.timeline.model.TimeMath
import dev.videoeditor.timeline.model.TrackKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class EntityKind { ASSET, TRACK, CLIP }

/** Domain failure of a command. Every case has a stable `code` (its serial name) and a computed [message]. */
@Serializable
sealed interface CommandError {
    val message: String

    @Serializable
    @SerialName("malformed")
    data class Malformed(
        val detail: String,
    ) : CommandError {
        override val message: String get() = "Malformed command: $detail"
    }

    @Serializable
    @SerialName("invalid_argument")
    data class InvalidArgument(
        val field: String,
        val reason: String,
    ) : CommandError {
        override val message: String get() = "Invalid ${this.field}: $reason"
    }

    @Serializable
    @SerialName("not_found")
    data class NotFound(
        val entity: EntityKind,
        val id: String,
    ) : CommandError {
        override val message: String get() = "${entity.name.lowercase().replaceFirstChar {
            it.uppercase()
        }} $id not found"
    }

    @Serializable
    @SerialName("track_kind_mismatch")
    data class TrackKindMismatch(
        val trackId: String,
        val trackKind: TrackKind,
        val reason: String,
    ) : CommandError {
        override val message: String get() = "Track $trackId ($trackKind): $reason"
    }

    @Serializable
    @SerialName("overlap")
    data class Overlap(
        val trackId: String,
        val startUs: Long,
        val endUs: Long,
        val conflictingClipIds: List<String>,
    ) : CommandError {
        override val message: String
            get() =
                "Range ${TimeMath.formatTimecode(startUs)}-${TimeMath.formatTimecode(endUs)} on $trackId " +
                    "overlaps ${conflictingClipIds.joinToString(", ")}"
    }

    @Serializable
    @SerialName("clip_too_short")
    data class ClipTooShort(
        val clipId: String? = null,
        val durationUs: Long,
        val minDurationUs: Long,
    ) : CommandError {
        override val message: String
            get() = "${clipId ?: "Clip"} would be ${durationUs}us long; minimum is ${minDurationUs}us"
    }

    @Serializable
    @SerialName("split_out_of_range")
    data class SplitOutOfRange(
        val clipId: String,
        val atUs: Long,
        val minUs: Long,
        val maxUs: Long,
    ) : CommandError {
        override val message: String
            get() =
                if (minUs > maxUs) {
                    "$clipId is too short to split"
                } else {
                    "Cannot split $clipId at $atUs; split point must be in $minUs..$maxUs"
                }
    }

    @Serializable
    @SerialName("source_out_of_range")
    data class SourceOutOfRange(
        val clipId: String? = null,
        val assetId: String,
        val sourceInUs: Long,
        val sourceOutUs: Long,
        val assetDurationUs: Long,
    ) : CommandError {
        override val message: String
            get() =
                "Source range [$sourceInUs, $sourceOutUs) of ${clipId ?: "clip"} is outside " +
                    "$assetId (0..$assetDurationUs)"
    }

    @Serializable
    @SerialName("track_not_empty")
    data class TrackNotEmpty(
        val trackId: String,
        val clipCount: Int,
    ) : CommandError {
        override val message: String get() = "Track $trackId has $clipCount clips; set deleteClips to remove them"
    }

    @Serializable
    @SerialName("batch_failed")
    data class BatchFailed(
        val index: Int,
        val commandType: String,
        val cause: CommandError,
    ) : CommandError {
        override val message: String get() = "Batch command $index ($commandType) failed: ${cause.message}"
    }

    @Serializable
    @SerialName("transaction_in_progress")
    data object TransactionInProgress : CommandError {
        override val message: String get() = "An edit gesture is in progress; retry when it ends"
    }

    @Serializable
    @SerialName("stale_revision")
    data class StaleRevision(
        val expected: Long,
        val actual: Long,
    ) : CommandError {
        override val message: String get() = "Project changed: expected revision $expected, current is $actual"
    }

    @Serializable
    @SerialName("internal")
    data class Internal(
        val detail: String,
    ) : CommandError {
        override val message: String get() = "Internal error: $detail"
    }
}
