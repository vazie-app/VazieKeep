package app.vazie.keep.core

import java.io.Closeable

// KeePass composite key input. The password is either typed text or unlock material remembered
// from an earlier session (never both). Closing wipes every array.
class VaultCredentials(
    val password: ByteArray? = null,
    val unlockMaterial: ByteArray? = null,
    val keyFile: ByteArray? = null,
    val retainUnlockMaterial: Boolean = false,
) : Closeable {
    init {
        require(password == null || unlockMaterial == null) { "password and unlock material are exclusive" }
    }

    override fun close() {
        password?.fill(0)
        unlockMaterial?.fill(0)
        keyFile?.fill(0)
    }

    override fun toString() =
        "VaultCredentials(password=${password != null}, unlockMaterial=${unlockMaterial != null}, keyFile=${keyFile != null})"
}

class UnlockMaterial(bytes: ByteArray) : Closeable {
    private var bytes: ByteArray? = bytes

    val isClosed: Boolean
        @Synchronized get() = bytes == null

    @Synchronized
    fun <R> useBytes(block: (ByteArray) -> R): R = block(checkNotNull(bytes) { "UnlockMaterial is closed" })

    @Synchronized
    override fun close() {
        bytes?.fill(0)
        bytes = null
    }

    override fun toString() = "UnlockMaterial(<redacted>)"
}
