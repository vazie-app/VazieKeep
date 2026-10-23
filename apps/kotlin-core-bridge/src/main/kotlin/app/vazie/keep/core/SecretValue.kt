package app.vazie.keep.core

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class SecretValue(utf8: ByteArray) : Closeable {
    private var bytes: ByteArray? = utf8

    val isClosed: Boolean
        @Synchronized get() = bytes == null

    @Synchronized
    fun <R> useBytes(block: (ByteArray) -> R): R = block(checkNotNull(bytes) { "SecretValue is closed" })

    @Synchronized
    fun <R> useChars(block: (CharArray) -> R): R {
        val decoded = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(checkNotNull(bytes) { "SecretValue is closed" }))
        val chars = CharArray(decoded.remaining())
        decoded.get(chars)
        if (decoded.hasArray()) decoded.array().fill('\u0000')
        try {
            return block(chars)
        } finally {
            chars.fill('\u0000')
        }
    }

    @Synchronized
    override fun close() {
        bytes?.fill(0)
        bytes = null
    }

    override fun toString() = "SecretValue(<redacted>)"
}
