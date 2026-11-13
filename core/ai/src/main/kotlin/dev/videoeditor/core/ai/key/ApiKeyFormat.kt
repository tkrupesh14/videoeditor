package dev.videoeditor.core.ai.key

/**
 * Offline plausibility check for an Anthropic Console API key (docs/design/security.md, A2.4).
 *
 * Pure: no I/O, no logging, never returns or copies the input. Rules are applied after trimming
 * leading and trailing whitespace:
 *
 * 1. Starts with `sk-ant-oat` -> [Problem.SubscriptionToken]. This is how the app enforces that it never
 *    uses a Claude subscription: only Console API keys are accepted.
 * 2. Starts with `sk-ant-admin` -> [Problem.AdminKey].
 * 3. Otherwise must start with `sk-ant-`, contain only `[A-Za-z0-9_-]`, and be 40 to 512 characters long.
 *
 * The rest of the format is deliberately not checked, so a new key generation does not lock users out.
 */
object ApiKeyFormat {
    const val REQUIRED_PREFIX = "sk-ant-"
    const val SUBSCRIPTION_TOKEN_PREFIX = "sk-ant-oat"
    const val ADMIN_KEY_PREFIX = "sk-ant-admin"
    const val MIN_LENGTH = 40
    const val MAX_LENGTH = 512

    enum class Problem(
        val userMessage: String,
    ) {
        Empty("Enter your Anthropic API key."),
        HasWhitespace("The key contains spaces or line breaks. Paste it again without them."),
        WrongPrefix(
            "That does not look like an Anthropic API key. Keys from the Anthropic Console start with \"sk-ant-\".",
        ),
        SubscriptionToken(
            "That is a Claude subscription token. This app only works with an API key from the Anthropic Console.",
        ),
        AdminKey("That is an Admin key. Create a regular API key instead."),
        BadCharacters(
            "The key contains characters an Anthropic API key never has. Copy it again from the Anthropic Console.",
        ),
        BadLength("The key is too short or too long. Copy the whole key from the Anthropic Console."),
    }

    /** Returns null when [candidate] looks plausible, otherwise the first problem found. */
    fun check(candidate: CharSequence): Problem? {
        val key = normalized(candidate)
        if (key.isEmpty()) return Problem.Empty
        // Subscription and admin prefixes are matched case-insensitively so that a re-cased token
        // still gets the specific explanation instead of slipping through rule 3.
        if (key.startsWith(SUBSCRIPTION_TOKEN_PREFIX, ignoreCase = true)) return Problem.SubscriptionToken
        if (key.startsWith(ADMIN_KEY_PREFIX, ignoreCase = true)) return Problem.AdminKey
        if (!key.startsWith(REQUIRED_PREFIX)) return Problem.WrongPrefix
        var whitespace = false
        var badChar = false
        for (i in key.indices) {
            val c = key[i]
            when {
                c.isWhitespace() -> whitespace = true
                !isAllowed(c) -> badChar = true
            }
        }
        if (whitespace) return Problem.HasWhitespace
        if (badChar) return Problem.BadCharacters
        if (key.length !in MIN_LENGTH..MAX_LENGTH) return Problem.BadLength
        return null
    }

    /**
     * [candidate] without leading and trailing whitespace, as a sub-view (no String copy is made when
     * the input is a CharBuffer). This is the form the stores persist.
     */
    fun normalized(candidate: CharSequence): CharSequence {
        var start = 0
        var end = candidate.length
        while (start < end && candidate[start].isWhitespace()) start++
        while (end > start && candidate[end - 1].isWhitespace()) end--
        return candidate.subSequence(start, end)
    }

    /** Last four characters of a plausible key, for display as `sk-ant-…ab12`. */
    fun hint(candidate: CharSequence): String {
        val key = normalized(candidate)
        val n = minOf(HINT_LENGTH, key.length)
        return buildString(n) { for (i in key.length - n until key.length) append(key[i]) }
    }

    private const val HINT_LENGTH = 4

    private fun isAllowed(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-'
}
