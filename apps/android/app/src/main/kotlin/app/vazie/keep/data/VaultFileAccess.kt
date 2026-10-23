package app.vazie.keep.data

import app.vazie.keep.core.MAX_ATTACHMENT_BYTES
import android.content.ContentResolver
import android.content.Intent
import android.provider.OpenableColumns
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

const val MAX_VAULT_BYTES = 256 * 1024 * 1024
const val MAX_KEY_FILE_BYTES = 64 * 1024 * 1024

private const val HEADER_BYTES = 12

sealed class VaultFileException(message: String) : IOException(message) {
    class Unavailable : VaultFileException("vault file unavailable")

    class TooLarge : VaultFileException("vault file too large")
}

interface VaultFileAccess {
    suspend fun read(uri: String): ByteArray

    suspend fun readKeyFile(uri: String): ByteArray

    suspend fun readAttachment(uri: String): ByteArray

    suspend fun readHeader(uri: String): ByteArray

    suspend fun displayName(uri: String): String?

    // Keeps the grant across restarts. The vault needs write access to be saved; a key file doesn't.
    fun persistPermission(uri: String, write: Boolean = false)

    fun releasePermission(uri: String)

    fun hasWriteAccess(uri: String): Boolean

    // Writes the complete content of a document that was just created and is still empty, and waits
    // until it is on disk. Not for existing vaults: those go through the journaled writer.
    suspend fun writeNew(uri: String, bytes: ByteArray)

    // Best effort: removes a document this app just created and could not fill. Never throws.
    suspend fun deleteQuietly(uri: String)
}

class ContentResolverVaultFileAccess(
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val port: DocumentPort = ContentResolverDocumentPort(resolver),
) : VaultFileAccess {
    override suspend fun read(uri: String): ByteArray = open(uri) { readBounded(it, MAX_VAULT_BYTES) }

    override suspend fun readKeyFile(uri: String): ByteArray = open(uri) { readBounded(it, MAX_KEY_FILE_BYTES) }

    override suspend fun readAttachment(uri: String): ByteArray = open(uri) { readBounded(it, MAX_ATTACHMENT_BYTES.toInt()) }

    override suspend fun readHeader(uri: String): ByteArray = open(uri) { stream ->
        val header = ByteArray(HEADER_BYTES)
        var read = 0
        while (read < HEADER_BYTES) {
            val count = stream.read(header, read, HEADER_BYTES - read)
            if (count < 0) break
            read += count
        }
        header.copyOf(read)
    }

    override suspend fun displayName(uri: String): String? = withContext(io) {
        runCatching {
            resolver.query(uri.toUri(), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    override fun persistPermission(uri: String, write: Boolean) {
        val target = uri.toUri()
        if (write) {
            val both = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            if (runCatching { resolver.takePersistableUriPermission(target, both) }.isSuccess) return
        }
        runCatching { resolver.takePersistableUriPermission(target, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    override fun releasePermission(uri: String) {
        val both = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.releasePersistableUriPermission(uri.toUri(), both) }
    }

    override fun hasWriteAccess(uri: String): Boolean = port.hasWriteAccess(uri)

    override suspend fun writeNew(uri: String, bytes: ByteArray) {
        withContext(io) {
            try {
                val descriptor = resolver.openFileDescriptor(uri.toUri(), "wt") ?: throw VaultFileException.Unavailable()
                descriptor.use { pfd ->
                    java.io.FileOutputStream(pfd.fileDescriptor).use { out ->
                        out.write(bytes)
                        out.flush()
                        out.fd.sync()
                    }
                }
            } catch (_: SecurityException) {
                throw VaultFileException.Unavailable()
            } catch (_: IOException) {
                throw VaultFileException.Unavailable()
            }
        }
    }

    override suspend fun deleteQuietly(uri: String) {
        withContext(io) {
            runCatching { android.provider.DocumentsContract.deleteDocument(resolver, uri.toUri()) }
        }
    }

    private suspend fun <T> open(uri: String, block: (InputStream) -> T): T = withContext(io) {
        val stream = try {
            resolver.openInputStream(uri.toUri())
        } catch (_: SecurityException) {
            null
        } catch (_: IOException) {
            null
        } ?: throw VaultFileException.Unavailable()
        try {
            stream.use(block)
        } catch (error: VaultFileException) {
            throw error
        } catch (_: IOException) {
            throw VaultFileException.Unavailable()
        }
    }
}

// Key files are secret, so the intermediate buffers are zero-filled. The vault file is ciphertext.
internal fun readBounded(stream: InputStream, maxBytes: Int): ByteArray {
    val out = WipingByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0
    try {
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw VaultFileException.TooLarge()
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    } finally {
        buffer.fill(0)
        out.wipe()
    }
}

private class WipingByteArrayOutputStream : ByteArrayOutputStream() {
    fun wipe() {
        buf.fill(0)
        count = 0
    }
}
