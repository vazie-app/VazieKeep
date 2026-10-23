package app.vazie.keep.testing

import app.vazie.keep.data.DocumentIoException
import app.vazie.keep.data.DocumentPort
import app.vazie.keep.data.OverwriteHandle
import app.vazie.keep.data.ProviderCapabilities

// An in-memory document with the semantics that matter: writes land at offset 0 without
// truncating, a failed write leaves a prefix of the new bytes over the old ones, and every step is
// recorded.
class FakeDocumentPort(var content: ByteArray = "original-vault".toByteArray()) : DocumentPort {
    var capabilities = ProviderCapabilities.Unknown.copy(known = true)
    var writeAccess = true
    var readFailure: DocumentIoException.Kind? = null
    var openFailure: DocumentIoException.Kind? = null
    var writeFailure: DocumentIoException.Kind? = null
    var writeCutAt = 0
    var closeFailure: DocumentIoException.Kind? = null
    var corruptWrites = false
    // 1-based: which openOverwrite call is the first to fail (to break the restore, not the write).
    var failOpenFromCall: Int? = null
    var copyFailure: DocumentIoException.Kind? = null
    val operations = mutableListOf<String>()
    private var opens = 0
    var created: ByteArray? = null

    override fun capabilities(uri: String) = capabilities

    override fun hasWriteAccess(uri: String) = writeAccess

    override fun read(uri: String, maxBytes: Int): ByteArray {
        operations += "read"
        readFailure?.let { throw DocumentIoException(it) }
        return content.copyOf()
    }

    override fun openOverwrite(uri: String): OverwriteHandle {
        opens++
        operations += "open"
        openFailure?.let { throw DocumentIoException(it) }
        if (failOpenFromCall != null && opens >= failOpenFromCall!!) throw DocumentIoException(DocumentIoException.Kind.Failed)
        return Handle()
    }

    override fun writeNew(uri: String, bytes: ByteArray) {
        copyFailure?.let { throw DocumentIoException(it) }
        created = bytes
        content = bytes
    }

    private inner class Handle : OverwriteHandle {
        private val first = opens == 1

        override fun write(bytes: ByteArray) {
            operations += "write"
            val failure = writeFailure
            if (failure != null && first) {
                content = bytes.copyOf(writeCutAt) + content.drop(writeCutAt).toByteArray()
                throw DocumentIoException(failure)
            }
            val data = if (corruptWrites && first) bytes.copyOf().also { it[0] = (it[0] + 1).toByte() } else bytes
            content = data + content.drop(data.size).toByteArray()
        }

        override fun truncate(size: Long) {
            operations += "truncate:$size"
            if (content.size > size) content = content.copyOf(size.toInt())
        }

        override fun sync() {
            operations += "sync"
        }

        override fun close() {
            operations += "close"
            if (first) closeFailure?.let { throw DocumentIoException(it) }
        }
    }
}
