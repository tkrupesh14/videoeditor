package dev.videoeditor.core.ai.assistant

import com.anthropic.errors.AnthropicServiceException

/** Maps SDK exceptions to [AiError] without touching the request, headers or URL (A2.2). */
internal object ErrorMapping {
    private val KEY_LIKE = Regex("sk-ant-[A-Za-z0-9_-]{8,}")

    fun redact(s: String): String = KEY_LIKE.replace(s, "[REDACTED]")

    fun fromService(e: AnthropicServiceException): AiError {
        val status = e.statusCode()
        val type = e.errorType().orElse(null)?.toString()
        val apiMessage = e.apiMessage().orElse(null)?.let(::redact)
        val retryAfter =
            e
                .headers()
                .values("retry-after")
                .firstOrNull()
                ?.trim()
                ?.toLongOrNull()
        val kind =
            when {
                status == 401 -> AiError.Kind.Auth
                status == 403 -> AiError.Kind.PermissionDenied
                status == 429 -> AiError.Kind.RateLimited
                status == 529 || type == "overloaded_error" -> AiError.Kind.Overloaded
                status >= 500 -> AiError.Kind.Server
                else -> AiError.Kind.BadRequest
            }
        val fallback =
            when (kind) {
                AiError.Kind.Auth -> "The API key was rejected. Check it in settings."
                AiError.Kind.PermissionDenied -> "This API key is not allowed to make this request."
                AiError.Kind.RateLimited -> "Rate limited by the Anthropic API."
                AiError.Kind.Overloaded -> "The Anthropic API is temporarily overloaded."
                AiError.Kind.Server -> "The Anthropic API returned a server error."
                else -> "The Anthropic API rejected the request."
            }
        return AiError(
            kind = kind,
            message = apiMessage ?: fallback,
            httpStatus = status,
            anthropicErrorType = type,
            requestId = e.requestId().orElse(null),
            retryAfterSeconds =
                if (kind == AiError.Kind.RateLimited ||
                    kind == AiError.Kind.Overloaded
                ) {
                    retryAfter
                } else {
                    null
                },
        )
    }

    fun network() = AiError(AiError.Kind.Network, "Could not reach the Anthropic API. Check the connection.")

    fun invalidResponse() =
        AiError(AiError.Kind.InvalidResponse, "The Anthropic API sent a response that could not be read.")
}
