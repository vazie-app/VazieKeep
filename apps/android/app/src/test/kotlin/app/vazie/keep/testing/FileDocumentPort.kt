package app.vazie.keep.testing

import app.vazie.keep.data.DocumentIoException
import app.vazie.keep.data.DocumentPort
import app.vazie.keep.data.OverwriteHandle
import app.vazie.keep.data.ProviderCapabilities
import app.vazie.keep.data.VaultFileAccess
import app.vazie.keep.data.readBounded
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

private fun path(uri: String) = File(uri.removePrefix("file://"))

// Real files for the JVM tests that run the real core: writes land at offset 0 without truncating,
// exactly like a seekable descriptor from a document provider.
class FileDocumentPort : DocumentPort {
    override fun capabilities(uri: String) = ProviderCapabilities.Unknown

    override fun hasWriteAccess(uri: String) = true

    override fun read(uri: String, maxBytes: Int): ByteArray = try {
        path(uri).inputStream().use { readBounded(it, maxBytes) }
    } catch (error: IOException) {
        throw DocumentIoException(DocumentIoException.Kind.Unavailable, error)
    }

    override fun openOverwrite(uri: String): OverwriteHandle {
        val file = RandomAccessFile(path(uri), "rw")
        return object : OverwriteHandle {
            override fun write(bytes: ByteArray) {
                file.seek(0)
                file.write(bytes)
            }

            override fun truncate(size: Long) = file.setLength(size)

            override fun sync() = file.fd.sync()

            override fun close() = file.close()
        }
    }

    override fun writeNew(uri: String, bytes: ByteArray) = path(uri).writeBytes(bytes)
}

class FileBackedAccess : VaultFileAccess {
    override suspend fun read(uri: String) = path(uri).readBytes()

    override suspend fun readKeyFile(uri: String) = path(uri).readBytes()

    override suspend fun readAttachment(uri: String) = path(uri).readBytes()

    override suspend fun readHeader(uri: String) = path(uri).readBytes().copyOf(12)

    override suspend fun displayName(uri: String) = path(uri).name

    override fun persistPermission(uri: String, write: Boolean) = Unit

    override fun releasePermission(uri: String) = Unit

    override fun hasWriteAccess(uri: String) = true

    override suspend fun writeNew(uri: String, bytes: ByteArray) = path(uri).writeBytes(bytes)

    override suspend fun deleteQuietly(uri: String) {
        path(uri).delete()
    }
}
