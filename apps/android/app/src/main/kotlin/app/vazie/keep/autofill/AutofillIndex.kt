package app.vazie.keep.autofill

import app.vazie.keep.data.VaultScopedFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

// What Autofill may know before the vault is unlocked: no secret, only what an entry's list row
// already shows. Passwords, notes and custom fields never enter it.
data class IndexedEntry(val entryId: String, val title: String, val username: String, val url: String) {
    override fun toString() = "IndexedEntry(<redacted>)"
}

// Tied to one database by its fingerprint, so an index left from another vault never resolves here.
class AutofillIndex(val fingerprint: String, val entries: List<IndexedEntry>) {
    override fun toString() = "AutofillIndex(entries=${entries.size})"
}

// A native app the user chose a credential for. The certificate is part of the identity: another
// app with the same package name but a different signer is a different app.
data class AppAssociation(val packageName: String, val certDigest: String, val fingerprint: String, val entryId: String) {
    override fun toString() = "AppAssociation(<redacted>)"
}

interface AutofillIndexStore {
    fun load(): AutofillIndex?

    fun save(index: AutofillIndex)

    fun clear()
}

interface AppAssociationStore {
    fun all(): List<AppAssociation>

    fun add(association: AppAssociation)

    // Keeps only associations for this vault whose entry still exists.
    fun retain(fingerprint: String, existingEntryIds: Set<String>)

    fun clear()
}

// App-private files in noBackupFilesDir, written to a temporary file and renamed into place.
class FileAutofillIndexStore(private val scoped: VaultScopedFile) : AutofillIndexStore {
    constructor(file: File) : this(VaultScopedFile.fixed(file))

    // One index per vault; the Autofill service answers from the active vault's.
    fun clearFor(vaultId: String) {
        scoped.forId(vaultId).delete()
    }

    override fun load(): AutofillIndex? = readFile(scoped.current()) { input ->
        val fingerprint = input.readUTF()
        val entries = List(input.readInt().also { require(it in 0..MAX_ENTRIES) }) {
            IndexedEntry(input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF())
        }
        AutofillIndex(fingerprint, entries)
    }

    override fun save(index: AutofillIndex) = writeFile(scoped.current()) { output ->
        val entries = index.entries.filter { it.fieldsFit() }
        output.writeUTF(index.fingerprint)
        output.writeInt(entries.size)
        entries.forEach {
            output.writeUTF(it.entryId)
            output.writeUTF(it.title)
            output.writeUTF(it.username)
            output.writeUTF(it.url)
        }
    }

    override fun clear() {
        scoped.current().delete()
    }

    private fun IndexedEntry.fieldsFit() = listOf(entryId, title, username, url).all { it.length <= MAX_FIELD }

    private companion object {
        const val MAX_ENTRIES = 200_000
        const val MAX_FIELD = 16_000
    }
}

class FileAppAssociationStore(private val file: File) : AppAssociationStore {
    @Synchronized
    override fun all(): List<AppAssociation> = readFile(file) { input ->
        List(input.readInt().also { require(it in 0..MAX_ASSOCIATIONS) }) {
            AppAssociation(input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF())
        }
    }.orEmpty()

    @Synchronized
    override fun add(association: AppAssociation) {
        val current = all()
        if (association in current) return
        // One approval per app and entry: a new certificate replaces the old one.
        val others = current.filterNot {
            it.packageName == association.packageName && it.fingerprint == association.fingerprint && it.entryId == association.entryId
        }
        write((others + association).takeLast(MAX_ASSOCIATIONS))
    }

    @Synchronized
    override fun retain(fingerprint: String, existingEntryIds: Set<String>) {
        val current = all()
        val kept = current.filter { it.fingerprint == fingerprint && it.entryId in existingEntryIds }
        if (kept.size != current.size) write(kept)
    }

    @Synchronized
    override fun clear() {
        file.delete()
    }

    private fun write(list: List<AppAssociation>) = writeFile(file) { output ->
        output.writeInt(list.size)
        list.forEach {
            output.writeUTF(it.packageName)
            output.writeUTF(it.certDigest)
            output.writeUTF(it.fingerprint)
            output.writeUTF(it.entryId)
        }
    }

    private companion object {
        const val MAX_ASSOCIATIONS = 5_000
    }
}

private const val FORMAT = 1

private fun <T> readFile(file: File, block: (DataInputStream) -> T): T? = try {
    if (!file.isFile) {
        null
    } else {
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != FORMAT) null else block(input)
        }
    }
} catch (_: IOException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private fun writeFile(file: File, block: (DataOutputStream) -> Unit) {
    file.parentFile?.mkdirs()
    val staged = File(file.parentFile, file.name + ".tmp")
    try {
        FileOutputStream(staged).use { stream ->
            DataOutputStream(stream.buffered()).use { output ->
                output.writeInt(FORMAT)
                block(output)
                output.flush()
            }
        }
        if (!staged.renameTo(file)) staged.delete()
    } catch (_: IOException) {
        staged.delete()
    }
}
