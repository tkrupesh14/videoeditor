package dev.videoeditor.core.ai.key

import dev.videoeditor.core.ai.key.ApiKeyFormat.Problem
import dev.videoeditor.core.ai.key.Fixtures.fakeKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.CharBuffer

class ApiKeyFormatTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun table(
        label: String,
        candidate: String,
        expected: Problem?,
    ) {
        assertEquals(expected, ApiKeyFormat.check(candidate), label)
    }

    @Test
    fun subscriptionTokenMessageIsExact() {
        assertEquals(
            "That is a Claude subscription token. This app only works with an API key from the Anthropic Console.",
            Problem.SubscriptionToken.userMessage,
        )
    }

    @Test
    fun adminKeyMessageIsExact() {
        assertEquals("That is an Admin key. Create a regular API key instead.", Problem.AdminKey.userMessage)
    }

    @Test
    fun worksOnCharBufferWithoutString() {
        assertNull(ApiKeyFormat.check(CharBuffer.wrap(("  " + fakeKey() + "\n").toCharArray())))
    }

    @Test
    fun normalizedAndHint() {
        val k = fakeKey(50, 'Z').dropLast(4) + "ab12"
        assertEquals(k, ApiKeyFormat.normalized("\t $k \n").toString())
        assertEquals("ab12", ApiKeyFormat.hint(" $k "))
        assertEquals("", ApiKeyFormat.hint("   "))
    }

    @Test
    fun disclosureSentenceIsUnshortened() {
        assertEquals("Uses your Anthropic API credits, not your Claude subscription.", API_CREDITS_DISCLOSURE)
    }

    companion object {
        private const val P = "sk-ant-"

        @JvmStatic
        fun cases(): List<Arguments> =
            listOf(
                Arguments.of("plausible", fakeKey(), null),
                Arguments.of("min length 40", fakeKey(40), null),
                Arguments.of("max length 512", fakeKey(512), null),
                Arguments.of("too short 39", fakeKey(39), Problem.BadLength),
                Arguments.of("too long 513", fakeKey(513), Problem.BadLength),
                Arguments.of("surrounding whitespace trimmed", "  \t" + fakeKey() + "\r\n", null),
                Arguments.of("empty", "", Problem.Empty),
                Arguments.of("blank", "   \n\t", Problem.Empty),
                Arguments.of("inner space", fakeKey().replaceRange(20, 21, " "), Problem.HasWhitespace),
                Arguments.of("inner newline", fakeKey().replaceRange(20, 21, "\n"), Problem.HasWhitespace),
                Arguments.of("subscription token", P + "oat01-" + "A".repeat(80), Problem.SubscriptionToken),
                Arguments.of("subscription token short", P + "oat", Problem.SubscriptionToken),
                Arguments.of(
                    "subscription token re-cased",
                    "SK-ANT-OAT01-" + "A".repeat(80),
                    Problem.SubscriptionToken,
                ),
                Arguments.of(
                    "subscription token padded",
                    "  " + P + "oat01-" + "A".repeat(80),
                    Problem.SubscriptionToken,
                ),
                Arguments.of("admin key", P + "admin01-" + "A".repeat(80), Problem.AdminKey),
                Arguments.of("admin key re-cased", P + "ADMIN01-" + "A".repeat(80), Problem.AdminKey),
                Arguments.of("other vendor", "sk-" + "A".repeat(60), Problem.WrongPrefix),
                Arguments.of("bearer prefix", "Bearer " + fakeKey(), Problem.WrongPrefix),
                Arguments.of("upper-case prefix", fakeKey().uppercase(), Problem.WrongPrefix),
                Arguments.of("quoted", "\"" + fakeKey() + "\"", Problem.WrongPrefix),
                Arguments.of("bad char dot", fakeKey().replaceRange(20, 21, "."), Problem.BadCharacters),
                Arguments.of("bad char unicode", fakeKey().replaceRange(20, 21, "é"), Problem.BadCharacters),
                Arguments.of("bad char equals", fakeKey().replaceRange(20, 21, "="), Problem.BadCharacters),
                Arguments.of("underscore and dash ok", fakeKey().replaceRange(20, 22, "_-"), null),
            )
    }
}
