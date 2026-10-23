package app.vazie.keep.data

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

enum class JournalState {
    None,
    // The file already holds the bytes that were being written.
    Completed,
    // The file still holds the version the write started from.
    NeverWritten,
    // The file holds neither: a write was cut off, or someone else changed the file since.
    Interrupted,
}

// Encrypted copies of a vault in the app's own storage, never in the user's folder: the version
// the last save replaced, changes that couldn't be saved, and the write-ahead journal of a save in
// progress. All are complete KDBX files, as encrypted as the vault itself.
interface VaultCopyStore {
    fun keepBackup(bytes: ByteArray, fingerprint: String): Boolean

    fun backup(fingerprint: String): ByteArray?

    fun keepUnsaved(bytes: ByteArray, fingerprint: String): Boolean

    fun unsaved(fingerprint: String): ByteArray?

    fun removeUnsaved(fingerprint: String)

    // Changes that were never saved before a session ended become a recovered copy at the next
    // unlock, so that this session's own unsaved copy can't overwrite them.
    fun promoteUnsaved(fingerprint: String): Boolean

    fun recovered(fingerprint: String): ByteArray?

    fun hasRecovered(fingerprint: String): Boolean

    fun removeRecovered(fingerprint: String)

    // Forgetting a vault drops every local copy of it; the document itself is never touched.
    fun removeAll(fingerprint: String) {
        removeUnsaved(fingerprint)
        removeRecovered(fingerprint)
    }

    fun beginWrite(uri: String, baseline: FileVersion, pending: ByteArray): Boolean

    fun inspectWrite(uri: String, current: FileVersion): JournalState

    fun pendingWrite(uri: String): ByteArray?

    fun finishWrite(uri: String)

    fun keepDamaged(uri: String, bytes: ByteArray): Boolean

    // Forgetting a vault removes every copy kept for it.
    fun removeAll(fingerprint: String?, uri: String) {}
}

class FileVaultCopyStore(private val directory: File) : VaultCopyStore {
    override fun keepBackup(bytes: ByteArray, fingerprint: String) = write(file(fingerprint, "previous"), bytes)

    override fun backup(fingerprint: String) = read(file(fingerprint, "previous"))

    override fun keepUnsaved(bytes: ByteArray, fingerprint: String) = write(file(fingerprint, "unsaved"), bytes)

    override fun unsaved(fingerprint: String) = read(file(fingerprint, "unsaved"))

    override fun removeUnsaved(fingerprint: String) {
        file(fingerprint, "unsaved").delete()
    }

    override fun promoteUnsaved(fingerprint: String): Boolean {
        val unsaved = file(fingerprint, "unsaved")
        return unsaved.isFile && unsaved.renameTo(file(fingerprint, "recovered"))
    }

    override fun recovered(fingerprint: String) = read(file(fingerprint, "recovered"))

    override fun hasRecovered(fingerprint: String) = file(fingerprint, "recovered").isFile

    override fun removeRecovered(fingerprint: String) {
        file(fingerprint, "recovered").delete()
    }

    override fun removeAll(fingerprint: String) {
        listOf("previous", "unsaved", "recovered").forEach { file(fingerprint, it).delete() }
    }

    override fun beginWrite(uri: String, baseline: FileVersion, pending: ByteArray): Boolean {
        val key = uriKey(uri)
        return write(File(directory, "$key-pending.kdbx"), pending) &&
            write(File(directory, "$key-pending.base"), baseline.sha256.toByteArray())
    }

    override fun inspectWrite(uri: String, current: FileVersion): JournalState {
        val key = uriKey(uri)
        val pending = read(File(directory, "$key-pending.kdbx")) ?: return JournalState.None
        val baseline = read(File(directory, "$key-pending.base"))?.decodeToString()
        return when {
            FileVersion.of(pending) == current -> JournalState.Completed
            baseline == current.sha256 -> JournalState.NeverWritten
            else -> JournalState.Interrupted
        }
    }

    override fun pendingWrite(uri: String) = read(File(directory, "${uriKey(uri)}-pending.kdbx"))

    override fun finishWrite(uri: String) {
        val key = uriKey(uri)
        File(directory, "$key-pending.kdbx").delete()
        File(directory, "$key-pending.base").delete()
    }

    override fun keepDamaged(uri: String, bytes: ByteArray) = write(File(directory, "${uriKey(uri)}-damaged.kdbx"), bytes)

    override fun removeAll(fingerprint: String?, uri: String) {
        val key = uriKey(uri)
        listOf("$key-pending.kdbx", "$key-pending.base", "$key-damaged.kdbx").forEach { File(directory, it).delete() }
        if (fingerprint != null) listOf("previous", "unsaved", "recovered").forEach { file(fingerprint, it).delete() }
    }

    private fun file(fingerprint: String, kind: String) = File(directory, "${fingerprint.take(16)}-$kind.kdbx")

    private fun uriKey(uri: String) = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    private fun read(file: File): ByteArray? = try {
        file.takeIf { it.isFile }?.readBytes()
    } catch (_: java.io.IOException) {
        null
    }

    // A sibling file renamed over the target inside the app's own directory is atomic.
    private fun write(target: File, bytes: ByteArray): Boolean = try {
        directory.mkdirs()
        val staged = File(directory, target.name + ".tmp")
        FileOutputStream(staged).use {
            it.write(bytes)
            it.fd.sync()
        }
        staged.renameTo(target).also { if (!it) staged.delete() }
    } catch (_: java.io.IOException) {
        false
    }
}
