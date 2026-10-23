package app.vazie.keep.data

import android.content.ContentResolver
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.core.net.toUri
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class ContentResolverDocumentPort(private val resolver: ContentResolver) : DocumentPort {
    override fun capabilities(uri: String): ProviderCapabilities {
        val target = uri.toUri()
        val flags = try {
            resolver.query(target, arrayOf(Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else null
            }
        } catch (_: RuntimeException) {
            null
        } ?: return ProviderCapabilities.Unknown.copy(authority = target.authority)
        return ProviderCapabilities(
            authority = target.authority,
            known = true,
            canWrite = flags and Document.FLAG_SUPPORTS_WRITE != 0,
            canDelete = flags and Document.FLAG_SUPPORTS_DELETE != 0,
            canRename = flags and Document.FLAG_SUPPORTS_RENAME != 0,
            canMove = flags and Document.FLAG_SUPPORTS_MOVE != 0,
            isVirtual = flags and Document.FLAG_VIRTUAL_DOCUMENT != 0,
        )
    }

    override fun hasWriteAccess(uri: String): Boolean {
        val target = uri.toUri()
        if (target.scheme != ContentResolver.SCHEME_CONTENT) return true
        return resolver.persistedUriPermissions.any { it.uri == target && it.isWritePermission }
    }

    override fun read(uri: String, maxBytes: Int): ByteArray {
        val stream = try {
            resolver.openInputStream(uri.toUri())
        } catch (error: SecurityException) {
            throw DocumentIoException(DocumentIoException.Kind.NoPermission, error)
        } catch (error: IOException) {
            throw DocumentIoException(DocumentIoException.Kind.Unavailable, error)
        } ?: throw DocumentIoException(DocumentIoException.Kind.Unavailable)
        try {
            return stream.use { readBounded(it, maxBytes) }
        } catch (error: VaultFileException) {
            throw DocumentIoException(DocumentIoException.Kind.Failed, error)
        } catch (error: IOException) {
            throw DocumentIoException(DocumentIoException.Kind.Unavailable, error)
        }
    }

    override fun openOverwrite(uri: String): OverwriteHandle {
        val descriptor = try {
            resolver.openFileDescriptor(uri.toUri(), "rw")
        } catch (error: SecurityException) {
            throw DocumentIoException(DocumentIoException.Kind.NoPermission, error)
        } catch (error: FileNotFoundException) {
            // The document was just read, so this is a provider that has no read-write mode.
            throw DocumentIoException(DocumentIoException.Kind.Unsupported, error)
        } catch (error: RuntimeException) {
            throw DocumentIoException(DocumentIoException.Kind.Unsupported, error)
        } ?: throw DocumentIoException(DocumentIoException.Kind.Unsupported)
        try {
            Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_CUR)
        } catch (error: ErrnoException) {
            descriptor.close()
            throw DocumentIoException(DocumentIoException.Kind.Unsupported, error)
        }
        return DescriptorOverwriteHandle(descriptor)
    }

    override fun writeNew(uri: String, bytes: ByteArray) {
        try {
            val stream = resolver.openOutputStream(uri.toUri(), "w") ?: throw DocumentIoException(DocumentIoException.Kind.Unavailable)
            stream.use { it.write(bytes) }
        } catch (error: SecurityException) {
            throw DocumentIoException(DocumentIoException.Kind.NoPermission, error)
        } catch (error: DocumentIoException) {
            throw error
        } catch (error: IOException) {
            throw DocumentIoException(error.kindFromMessage(), error)
        }
    }
}

private class DescriptorOverwriteHandle(descriptor: ParcelFileDescriptor) : OverwriteHandle {
    private val stream = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
    private val channel: FileChannel = stream.channel

    override fun write(bytes: ByteArray) = guard {
        channel.position(0)
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    override fun truncate(size: Long) = guard {
        channel.truncate(size)
    }

    override fun sync() = guard {
        try {
            channel.force(true)
        } catch (error: IOException) {
            // EINVAL: the descriptor can't be synced (a pipe-backed provider). The read-back decides.
            if (error.message?.contains("EINVAL") != true) throw error
        }
    }

    override fun close() = guard { stream.close() }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (error: DocumentIoException) {
            throw error
        } catch (error: IOException) {
            throw DocumentIoException(error.kindFromMessage(), error)
        }
    }
}

private fun IOException.kindFromMessage(): DocumentIoException.Kind {
    val text = generateSequence<Throwable>(this) { it.cause }.mapNotNull { it.message }.joinToString(" ")
    return when {
        "ENOSPC" in text || "No space left" in text || "EDQUOT" in text -> DocumentIoException.Kind.NoSpace
        "EACCES" in text || "EPERM" in text || "EROFS" in text -> DocumentIoException.Kind.NoPermission
        "ESPIPE" in text -> DocumentIoException.Kind.Unsupported
        else -> DocumentIoException.Kind.Failed
    }
}
