package dev.videoeditor.core.ai.assistant

import dev.videoeditor.core.ai.spend.MicroUsd
import dev.videoeditor.core.ai.spend.SpendDecision
import dev.videoeditor.core.ai.spend.SpendSnapshot
import dev.videoeditor.core.ai.spend.Usage
import dev.videoeditor.timeline.diff.ProjectDiff
import dev.videoeditor.timeline.session.EditPreview

/**
 * A typed, key-free error (docs/design/security.md, A2.2). [message] is built from the API's `error.message`
 * (with anything key-shaped redacted) or from a fixed text; never from request objects, headers or URLs.
 * Returned, never thrown.
 */
data class AiError(
    val kind: Kind,
    val message: String,
    val httpStatus: Int? = null,
    val anthropicErrorType: String? = null,
    val requestId: String? = null,
    /** From the `retry-after` header on 429/529, in seconds. */
    val retryAfterSeconds: Long? = null,
) {
    enum class Kind {
        /** No key saved, or the key store is unavailable. */
        NoApiKey,

        /** 401: the key was rejected. */
        Auth,

        /** 403. */
        PermissionDenied,

        /** 429; see [retryAfterSeconds]. */
        RateLimited,

        /** 529 `overloaded_error`. */
        Overloaded,

        /** Other 5xx. */
        Server,

        /** Other 4xx (400, 404, 413, ...). */
        BadRequest,

        /** No HTTP response: DNS, TLS, connection reset, timeout. */
        Network,

        /** A response arrived but could not be read. */
        InvalidResponse,

        /** Claude declined (`stop_reason: "refusal"`). */
        Refusal,

        /** A tool call that the timeline rejected; sent back to Claude as an `is_error` tool result. */
        InvalidToolInput,
    }
}

/**
 * An edit suggestion. [preview] is a detached copy: the live project is untouched until the caller passes
 * it to `EditorSession.commitPreview` after the user accepts.
 */
data class AiSuggestion(
    val preview: EditPreview,
    /** Claude's closing text, or the edit summaries when Claude wrote none. */
    val summary: String,
    /** One line per successful edit tool call, in order. */
    val editSummaries: List<String>,
    /** Tool calls the timeline rejected (Claude was told and could correct them). */
    val toolErrors: List<AiError>,
    val usage: Usage,
    val cost: MicroUsd,
    /** False when the loop stopped early: max_tokens, context window, or the iteration cap. */
    val complete: Boolean,
    /** The last `stop_reason` seen, as the API spelled it. */
    val stopReason: String?,
    /** Number of Messages API round trips. */
    val rounds: Int,
) {
    val diff: ProjectDiff get() = preview.diff()

    val hasEdits: Boolean get() = preview.commands.isNotEmpty()
}

sealed interface AiResult {
    /** Totals over every round trip of this instruction. */
    val usage: Usage
    val cost: MicroUsd

    data class Suggested(
        val suggestion: AiSuggestion,
    ) : AiResult {
        override val usage: Usage get() = suggestion.usage
        override val cost: MicroUsd get() = suggestion.cost
    }

    /** Claude declined. Nothing is applied and no preview is returned. Show [category] to the user. */
    data class Refused(
        val category: String?,
        val explanation: String?,
        override val usage: Usage,
        override val cost: MicroUsd,
    ) : AiResult {
        val error: AiError
            get() =
                AiError(
                    AiError.Kind.Refusal,
                    "Claude declined this request" + (category?.let { " (category: $it)" } ?: "") +
                        ". Nothing was changed.",
                )
    }

    /**
     * The spend guard denied the next request, so it was not sent. [partial] holds edits from earlier rounds
     * of this instruction, if any; it is still only a preview.
     */
    data class CapReached(
        val reason: SpendDecision.Denied.Reason,
        val snapshot: SpendSnapshot,
        val partial: AiSuggestion?,
        override val usage: Usage,
        override val cost: MicroUsd,
    ) : AiResult

    data class Failed(
        val error: AiError,
        val partial: AiSuggestion?,
        override val usage: Usage,
        override val cost: MicroUsd,
    ) : AiResult
}
