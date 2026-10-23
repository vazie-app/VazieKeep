package app.vazie.keep.data

import java.io.Closeable
import java.io.IOException

// What a DocumentsProvider says about one document (COLUMN_FLAGS). null flags means the URI isn't
// a document (file:// or a plain content URI): nothing is known, so the write itself decides.
data class ProviderCapabilities(
    val authority: String?,
    val known: Boolean,
    val canWrite: Boolean,
    val canDelete: Boolean,
    val canRename: Boolean,
    val canMove: Boolean,
    val isVirtual: Boolean,
) {
    companion object {
        val Unknown = ProviderCapabilities(null, false, true, false, false, false, false)
    }
}

sealed interface SaveStrategy {
    // Complete encrypted copies are kept in the app, then the document is overwritten in place
    // without truncating first, and read back. See ADR 0015.
    data object JournaledOverwrite : SaveStrategy

    data object Unsupported : SaveStrategy
}

fun chooseStrategy(capabilities: ProviderCapabilities): SaveStrategy =
    if (!capabilities.canWrite || capabilities.isVirtual) SaveStrategy.Unsupported else SaveStrategy.JournaledOverwrite

class DocumentIoException(val kind: Kind, cause: Throwable? = null) : IOException(kind.name, cause) {
    enum class Kind { Unavailable, NoPermission, NoSpace, Unsupported, Failed }
}

interface OverwriteHandle : Closeable {
    // Writes at offset 0 and never truncates first.
    fun write(bytes: ByteArray)

    fun truncate(size: Long)

    fun sync()
}

// The only place that touches ContentResolver. Everything above it is plain Kotlin.
interface DocumentPort {
    fun capabilities(uri: String): ProviderCapabilities

    fun hasWriteAccess(uri: String): Boolean

    fun read(uri: String, maxBytes: Int): ByteArray

    // Throws Unsupported when the provider can't hand out a seekable descriptor.
    fun openOverwrite(uri: String): OverwriteHandle

    // For a document the user just created ("Save a copy"); truncating a new file is harmless.
    fun writeNew(uri: String, bytes: ByteArray)
}
