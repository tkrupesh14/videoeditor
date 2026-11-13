package dev.videoeditor.core.ai.key

import java.nio.CharBuffer

/**
 * Holder for a secret. Deliberately a final class, NOT a value class and NOT a data class:
 * a value class is erased to String in JVM signatures (Java callers, reflection and serializers see
 * the raw value), and a data class generates copy/componentN/toString. Not Serializable, no
 * kotlinx @Serializable, no Parcelable.
 *
 * Design: docs/design/security.md, A1.4.
 */
class SecretString private constructor(
    private val chars: CharArray,
) : AutoCloseable {
    @Volatile
    private var closed = false

    /**
     * The only way to read the value. Call sites are restricted (A2.1): only `AnthropicAuth` and the
     * key stores may call this. The [CharSequence] handed to [block] is a read-only view over the
     * backing array; it must not escape [block].
     *
     * @throws IllegalStateException if [close] was already called. The message never contains the value.
     */
    fun <T> use(block: (CharSequence) -> T): T {
        check(!closed) { "SecretString is closed" }
        return block(CharBuffer.wrap(chars).asReadOnlyBuffer())
    }

    /** Zeroes the backing array. Best effort; JVM strings made from it elsewhere are not covered. */
    override fun close() {
        closed = true
        chars.fill('\u0000')
    }

    override fun toString(): String = REDACTED

    /** Identity only, so equality can never be used as an oracle on the value. */
    override fun equals(other: Any?): Boolean = this === other

    /** Identity only; never derived from the value. */
    override fun hashCode(): Int = System.identityHashCode(this)

    companion object {
        private const val REDACTED = "SecretString(REDACTED)"

        /** Copies [value] into a private array. The caller remains responsible for its own copy. */
        fun of(value: CharSequence): SecretString = SecretString(CharArray(value.length) { value[it] })
    }
}
