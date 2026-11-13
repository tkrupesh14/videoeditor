package dev.videoeditor.core.ai.key

/**
 * Obviously fake key material. Built by concatenation so no source line contains a full key-shaped
 * literal; nothing here is a real credential.
 */
object Fixtures {
    private const val PREFIX = "sk-ant-"

    /** A plausible-format fake API key of [length] characters. */
    fun fakeKey(
        length: Int = 60,
        fill: Char = 'F',
    ): String {
        val head = PREFIX + "api03-FAKE"
        return head + fill.toString().repeat(maxOf(0, length - head.length))
    }

    /** Distinctive canary used to assert that a value never leaks. */
    val CANARY: String = PREFIX + "api03-" + "CANARYcanary0123456789" + "x".repeat(30)
}
