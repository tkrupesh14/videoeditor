package dev.videoeditor.core.ai.key

import dev.videoeditor.core.ai.key.Fixtures.CANARY
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.NotSerializableException
import java.io.ObjectOutputStream
import java.io.PrintWriter
import java.io.Serializable
import java.io.StringWriter

class SecretStringTest {
    @Test
    fun toStringIsRedacted() {
        val s = SecretString.of(CANARY)
        assertEquals("SecretString(REDACTED)", s.toString())
    }

    @Test
    fun stringTemplatesAndConcatenationAreRedacted() {
        val s = SecretString.of(CANARY)
        val templated = "key=$s list=${listOf(s)} map=${mapOf("k" to s)} pair=${s to 1}"
        assertFalse(templated.contains(CANARY))
        assertFalse(templated.contains("CANARY"))
        assertTrue(templated.contains("REDACTED"))
        assertFalse(StringBuilder().append(s).toString().contains("CANARY"))
        assertFalse(String.format("%s", s).contains("CANARY"))
    }

    @Test
    fun exceptionMessagesAndStackTracesAreRedacted() {
        val s = SecretString.of(CANARY)
        val e = IllegalStateException("failed for $s", RuntimeException("cause $s"))
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        assertFalse(sw.toString().contains("CANARY"))
        assertFalse(e.toString().contains("CANARY"))
    }

    @Test
    fun useGivesTheValueAndCloseZeroes() {
        val s = SecretString.of(CANARY)
        assertEquals(CANARY, s.use { it.toString() })
        var leaked: CharSequence? = null
        s.use { leaked = it }
        s.close()
        assertTrue(leaked!!.all { it == '\u0000' })
        val ex = assertThrows<IllegalStateException> { s.use { it.length } }
        assertFalse(ex.message!!.contains("CANARY"))
    }

    @Test
    fun ofCopiesTheInput() {
        val source = StringBuilder(CANARY)
        val s = SecretString.of(source)
        source.setLength(0)
        assertEquals(CANARY.length, s.use { it.length })
    }

    @Test
    fun equalityIsIdentityOnly() {
        val a = SecretString.of(CANARY)
        val b = SecretString.of(CANARY)
        assertNotEquals(a, b)
        assertEquals(a, a)
        assertEquals(System.identityHashCode(a), a.hashCode())
        assertEquals(1, setOf(a, a).size)
        assertEquals(2, setOf(a, b).size)
    }

    @Test
    fun viewIsReadOnly() {
        val s = SecretString.of(CANARY)
        s.use { cs ->
            assertThrows<java.nio.ReadOnlyBufferException> { (cs as java.nio.CharBuffer).put(0, 'x') }
        }
    }

    @Test
    fun notJavaSerializable() {
        val s = SecretString.of(CANARY)
        assertFalse(Serializable::class.java.isAssignableFrom(SecretString::class.java))
        assertThrows<NotSerializableException> { ObjectOutputStream(ByteArrayOutputStream()).writeObject(s) }
    }

    @Test
    fun noKotlinDataClassMembers() {
        val names = SecretString::class.java.declaredMethods.map { it.name }
        assertFalse(names.any { it == "copy" || it.startsWith("component") })
    }
}
