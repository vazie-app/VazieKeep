package app.vazie.keep.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface VaultDocumentWriter {
    // Replaces the document with already encrypted bytes, only if it still holds `expected`.
    // `backup` receives the bytes being replaced before anything is written.
    // Throws WriteException; on every failure except RestoreNeeded the document is unchanged.
    suspend fun replace(uri: String, expected: FileVersion, bytes: ByteArray, backup: (ByteArray) -> Boolean): FileVersion

    // Puts back the complete bytes of an interrupted write over a document that no longer reads
    // as a vault. `current` is the damaged content that is being replaced.
    suspend fun restore(uri: String, damaged: FileVersion, bytes: ByteArray): FileVersion

    suspend fun capabilities(uri: String): ProviderCapabilities

    suspend fun writeCopy(uri: String, bytes: ByteArray)
}

class JournaledOverwriteWriter(
    private val port: DocumentPort,
    private val copies: VaultCopyStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxBytes: Int = MAX_VAULT_BYTES,
) : VaultDocumentWriter {
    override suspend fun capabilities(uri: String) = withContext(io) { port.capabilities(uri) }

    override suspend fun writeCopy(uri: String, bytes: ByteArray) = withContext(io) {
        try {
            port.writeNew(uri, bytes)
            if (!port.read(uri, maxBytes).contentEquals(bytes)) throw WriteException(SaveFailure.VerificationFailed)
        } catch (error: DocumentIoException) {
            throw WriteException(error.kind.toFailure())
        }
    }

    override suspend fun replace(uri: String, expected: FileVersion, bytes: ByteArray, backup: (ByteArray) -> Boolean): FileVersion =
        withContext(io) {
            if (chooseStrategy(port.capabilities(uri)) == SaveStrategy.Unsupported) throw WriteException(SaveFailure.ProviderUnsupported)
            val current = io { port.read(uri, maxBytes) }
            if (FileVersion.of(current) != expected) throw WriteException(SaveFailure.ChangedExternally)
            overwrite(uri, current, bytes, backup)
        }

    override suspend fun restore(uri: String, damaged: FileVersion, bytes: ByteArray): FileVersion = withContext(io) {
        if (chooseStrategy(port.capabilities(uri)) == SaveStrategy.Unsupported) throw WriteException(SaveFailure.ProviderUnsupported)
        val current = io { port.read(uri, maxBytes) }
        if (FileVersion.of(current) != damaged) throw WriteException(SaveFailure.ChangedExternally)
        overwrite(uri, current, bytes) { copies.keepDamaged(uri, it) }
    }

    private fun overwrite(uri: String, current: ByteArray, bytes: ByteArray, backup: (ByteArray) -> Boolean): FileVersion {
        // Opening writes nothing, so a refusal here has no side effects.
        val handle = io { port.openOverwrite(uri) }
        try {
            if (!backup(current)) throw WriteException(SaveFailure.BackupFailed)
            if (!copies.beginWrite(uri, FileVersion.of(current), bytes)) throw WriteException(SaveFailure.BackupFailed)
            try {
                handle.write(bytes)
                handle.truncate(bytes.size.toLong())
                handle.sync()
                handle.close()
            } catch (error: java.io.IOException) {
                handle.closeQuietly()
                throw putBack(uri, current, error.toFailure())
            }
        } catch (error: WriteException) {
            handle.closeQuietly()
            if (error.failure == SaveFailure.BackupFailed) copies.finishWrite(uri)
            throw error
        }
        val written = try {
            port.read(uri, maxBytes)
        } catch (error: DocumentIoException) {
            throw putBack(uri, current, SaveFailure.VerificationFailed)
        }
        if (!written.contentEquals(bytes)) throw putBack(uri, current, SaveFailure.VerificationFailed)
        copies.finishWrite(uri)
        return FileVersion.of(bytes)
    }

    // The journal stays in place unless the old bytes are verifiably back.
    private fun putBack(uri: String, previous: ByteArray, failure: SaveFailure): WriteException {
        val restored = try {
            port.openOverwrite(uri).use { handle ->
                handle.write(previous)
                handle.truncate(previous.size.toLong())
                handle.sync()
            }
            port.read(uri, maxBytes).contentEquals(previous)
        } catch (_: java.io.IOException) {
            false
        }
        if (!restored) return WriteException(SaveFailure.RestoreNeeded)
        copies.finishWrite(uri)
        return WriteException(failure)
    }

    private fun OverwriteHandle.closeQuietly() {
        try {
            close()
        } catch (_: java.io.IOException) {
        }
    }

    private inline fun <T> io(block: () -> T): T = try {
        block()
    } catch (error: DocumentIoException) {
        throw WriteException(error.kind.toFailure())
    }
}

private fun java.io.IOException.toFailure() = ((this as? DocumentIoException)?.kind ?: DocumentIoException.Kind.Failed).toFailure()

private fun DocumentIoException.Kind.toFailure() = when (this) {
    DocumentIoException.Kind.Unavailable, DocumentIoException.Kind.Failed -> SaveFailure.Unavailable
    DocumentIoException.Kind.NoPermission -> SaveFailure.NotWritable
    DocumentIoException.Kind.NoSpace -> SaveFailure.InsufficientSpace
    DocumentIoException.Kind.Unsupported -> SaveFailure.ProviderUnsupported
}
