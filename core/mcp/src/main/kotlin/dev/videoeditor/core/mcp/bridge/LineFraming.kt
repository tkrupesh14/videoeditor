package dev.videoeditor.core.mcp.bridge

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class FrameTooLargeException(
    val limit: Int,
) : IOException("message larger than $limit bytes")

/**
 * Newline-delimited UTF-8 framing (B5, and MCP stdio). Reads raw bytes so the size limit is enforced before
 * anything is decoded or buffered beyond [limit].
 */
class LineReader(
    private val input: InputStream,
) {
    /**
     * Next line without its terminator (a trailing CR is dropped), or null at end of stream.
     * @throws FrameTooLargeException when the line exceeds [limit] bytes; the rest of that line is NOT consumed
     * unless [skipRest] is called.
     */
    fun readLine(limit: Int): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8)
            if (b == '\n'.code) {
                val bytes = buf.toByteArray()
                val n = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, n, Charsets.UTF_8)
            }
            if (buf.size() >= limit) throw FrameTooLargeException(limit)
            buf.write(b)
        }
    }

    /** Discards input up to and including the next newline. False at end of stream. */
    fun skipRest(): Boolean {
        while (true) {
            val b = input.read()
            if (b < 0) return false
            if (b == '\n'.code) return true
        }
    }
}

/** Writes one message per line. Thread-safe. */
class LineWriter(
    private val output: OutputStream,
) {
    @Synchronized
    fun writeLine(text: String) {
        require('\n' !in text) { "framed message must not contain a newline" }
        output.write(text.toByteArray(Charsets.UTF_8))
        output.write('\n'.code)
        output.flush()
    }
}
