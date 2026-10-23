package app.vazie.keep.passkeys

import app.vazie.keep.core.EntrySummary
import app.vazie.keep.core.EntryText
import app.vazie.keep.data.VaultScopedFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

// What Android may be told before the vault is unlocked: which sites have a passkey here and for which
// account. No key, nothing that signs.
data class IndexedPasskey(val entryId: String, val rpId: String, val userName: String, val credentialId: String, val title: String) {
    override fun toString() = "IndexedPasskey(<redacted>)"
}

class PasskeyIndex(val fingerprint: String, val passkeys: List<IndexedPasskey>) {
    companion object {
        fun of(fingerprint: String, entries: List<EntrySummary>) = PasskeyIndex(
            fingerprint,
            entries.mapNotNull { entry ->
                val passkey = entry.passkey ?: return@mapNotNull null
                IndexedPasskey(entry.id.value, passkey.rpId, passkey.userName, passkey.credentialId, (entry.title as? EntryText.Plain)?.value.orEmpty())
            },
        )
    }
}

interface PasskeyIndexStore {
    fun load(): PasskeyIndex?

    fun save(index: PasskeyIndex)

    fun clear()
}

// One file per vault in noBackupFilesDir, written to a temporary file and renamed into place.
class FilePasskeyIndexStore(private val scoped: VaultScopedFile) : PasskeyIndexStore {
    fun clearFor(vaultId: String) {
        scoped.forId(vaultId).delete()
    }

    override fun load(): PasskeyIndex? {
        val file = scoped.current()
        if (!file.isFile) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != FORMAT) return null
                val fingerprint = input.readUTF()
                val passkeys = List(input.readInt().also { require(it in 0..MAX) }) {
                    IndexedPasskey(input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF())
                }
                PasskeyIndex(fingerprint, passkeys)
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun save(index: PasskeyIndex) {
        val file = scoped.current()
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            DataOutputStream(temporary.outputStream().buffered()).use { out ->
                val fitting = index.passkeys.filter { p -> listOf(p.entryId, p.rpId, p.userName, p.credentialId, p.title).all { it.length <= MAX_FIELD } }
                out.writeInt(FORMAT)
                out.writeUTF(index.fingerprint)
                out.writeInt(fitting.size)
                fitting.forEach {
                    out.writeUTF(it.entryId)
                    out.writeUTF(it.rpId)
                    out.writeUTF(it.userName)
                    out.writeUTF(it.credentialId)
                    out.writeUTF(it.title)
                }
            }
            if (!temporary.renameTo(file)) temporary.delete()
        } catch (_: IOException) {
            temporary.delete()
        }
    }

    override fun clear() {
        scoped.current().delete()
    }

    private companion object {
        const val FORMAT = 1
        const val MAX = 20_000
        const val MAX_FIELD = 4_000
    }
}
