package app.vazie.keep.biometric

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import app.vazie.keep.data.VaultScopedFile
import java.io.File
import java.io.IOException

sealed interface BiometricRecord {
    // vaultUri and fingerprint are public identifiers; iv and ciphertext are useless without the
    // Keystore key, which never leaves secure hardware.
    class Active(
        val vaultUri: String,
        val fingerprint: String,
        val alias: String,
        val keyFileRequired: Boolean,
        val iv: ByteArray,
        val ciphertext: ByteArray,
    ) : BiometricRecord {
        // Binds the ciphertext to its vault, key alias and key-file policy (AES-GCM associated data).
        fun associatedData(): ByteArray =
            listOf("vazie-keep/biometric/v1", vaultUri, fingerprint, alias, keyFileRequired.toString())
                .joinToString("\u0000")
                .toByteArray(Charsets.UTF_8)

        override fun toString() = "BiometricRecord.Active(keyFileRequired=$keyFileRequired)"
    }

    // A setup that stopped working. Kept (without any secret) so the UI can say so; null means
    // the record was unreadable.
    data class Invalidated(val vaultUri: String?) : BiometricRecord {
        override fun toString() = "BiometricRecord.Invalidated"
    }
}

interface BiometricSetupStore {
    fun load(): BiometricRecord?

    fun save(record: BiometricRecord)

    fun clear()

    // The record of another vault, so forgetting one can remove its key without activating it.
    fun loadFor(vaultId: String): BiometricRecord? = null

    fun clearFor(vaultId: String) {}
}

// One record per vault; load, save and clear act on the active vault.
class FileBiometricSetupStore(private val scoped: VaultScopedFile) : BiometricSetupStore {
    constructor(file: File) : this(VaultScopedFile.fixed(file))

    override fun loadFor(vaultId: String): BiometricRecord? = read(scoped.forId(vaultId))

    override fun clearFor(vaultId: String) = delete(scoped.forId(vaultId))

    override fun load(): BiometricRecord? = read(scoped.current())

    private fun read(file: File): BiometricRecord? {
        if (!file.exists()) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC || input.readByte() != VERSION) return BiometricRecord.Invalidated(null)
                when (input.readByte()) {
                    STATE_ACTIVE -> BiometricRecord.Active(
                        vaultUri = input.readUTF(),
                        fingerprint = input.readUTF(),
                        alias = input.readUTF(),
                        keyFileRequired = input.readBoolean(),
                        iv = input.readBlob(),
                        ciphertext = input.readBlob(),
                    )
                    STATE_INVALIDATED -> BiometricRecord.Invalidated(input.readUTF().ifEmpty { null })
                    else -> BiometricRecord.Invalidated(null)
                }
            }
        } catch (_: IOException) {
            BiometricRecord.Invalidated(null)
        }
    }

    override fun save(record: BiometricRecord) {
        val file = scoped.current()
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(MAGIC)
                out.writeByte(VERSION.toInt())
                when (record) {
                    is BiometricRecord.Active -> {
                        out.writeByte(STATE_ACTIVE.toInt())
                        out.writeUTF(record.vaultUri)
                        out.writeUTF(record.fingerprint)
                        out.writeUTF(record.alias)
                        out.writeBoolean(record.keyFileRequired)
                        out.writeBlob(record.iv)
                        out.writeBlob(record.ciphertext)
                    }
                    is BiometricRecord.Invalidated -> {
                        out.writeByte(STATE_INVALIDATED.toInt())
                        out.writeUTF(record.vaultUri.orEmpty())
                    }
                }
            }
        }.toByteArray()
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.outputStream().use {
            it.write(bytes)
            it.fd.sync()
        }
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IOException("could not store biometric setup")
        }
    }

    override fun clear() = delete(scoped.current())

    private fun delete(file: File) {
        file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
    }

    private fun DataInputStream.readBlob(): ByteArray {
        val size = readUnsignedShort()
        return ByteArray(size).also { readFully(it) }
    }

    private fun DataOutputStream.writeBlob(bytes: ByteArray) {
        require(bytes.size <= 0xFFFF)
        writeShort(bytes.size)
        write(bytes)
    }

    private companion object {
        const val MAGIC = 0x564B4255
        const val VERSION: Byte = 1
        const val STATE_ACTIVE: Byte = 1
        const val STATE_INVALIDATED: Byte = 2
    }
}
