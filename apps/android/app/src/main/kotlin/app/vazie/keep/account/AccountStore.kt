package app.vazie.keep.account

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Everything the app keeps about a Vazie Account on this device. The session token is a credential,
// so the whole record is stored encrypted under a Keystore key; the lease is not secret but is kept
// with it so that signing out removes both at once.
class StoredAccount(
    val accountId: String,
    val email: String?,
    val sessionToken: String,
    val leaseToken: String?,
    // The latest device time seen, so setting the clock back cannot extend a lease.
    val lastObservedDeviceTime: Long,
    // Wall-clock seconds of the last successful refresh, for the 24-hour refresh cadence.
    val lastRefreshAt: Long,
) {
    override fun toString() = "StoredAccount(accountId=$accountId)"
}

interface AccountStore {
    fun load(): StoredAccount?

    fun save(account: StoredAccount)

    // Removes the record and, where there is one, the key that protected it.
    fun clear()
}

object AccountCodec {
    private const val MAGIC = 0x564B4143
    private const val VERSION: Byte = 1

    fun encode(account: StoredAccount): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { out ->
            out.writeInt(MAGIC)
            out.writeByte(VERSION.toInt())
            out.writeUTF(account.accountId)
            out.writeUTF(account.email.orEmpty())
            out.writeUTF(account.sessionToken)
            writeText(out, account.leaseToken.orEmpty())
            out.writeLong(account.lastObservedDeviceTime)
            out.writeLong(account.lastRefreshAt)
        }
    }.toByteArray()

    fun decode(bytes: ByteArray): StoredAccount? = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readInt() != MAGIC || input.readByte() != VERSION) return null
            StoredAccount(
                accountId = input.readUTF(),
                email = input.readUTF().ifEmpty { null },
                sessionToken = input.readUTF(),
                leaseToken = readText(input).ifEmpty { null },
                lastObservedDeviceTime = input.readLong(),
                lastRefreshAt = input.readLong(),
            )
        }
    } catch (_: IOException) {
        null
    }

    private fun writeText(out: DataOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readText(input: DataInputStream): String {
        val size = input.readInt()
        if (size !in 0..8192) throw IOException("bad length")
        return ByteArray(size).also(input::readFully).toString(Charsets.UTF_8)
    }
}

// AES-GCM under a non-exportable Android Keystore key without user authentication: the account must be
// readable in the background and after a reboot, so unlike the biometric record it is not auth-bound.
// The file lives in the no-backup directory; losing the key (reinstall, clear data) simply means signed out.
class KeystoreAccountStore(
    private val file: File,
    private val alias: String = "vazie_account_state",
) : AccountStore {

    override fun load(): StoredAccount? {
        if (!file.isFile) return null
        return try {
            val raw = file.readBytes()
            if (raw.size < IV_BYTES + 16) return null
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(create = false) ?: return null, GCMParameterSpec(128, raw, 0, IV_BYTES))
                updateAAD(AAD)
            }
            AccountCodec.decode(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES))
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    override fun save(account: StoredAccount) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, checkNotNull(key(create = true)))
            updateAAD(AAD)
        }
        val plain = AccountCodec.encode(account)
        val sealed = cipher.iv + cipher.doFinal(plain)
        plain.fill(0)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.outputStream().use {
            it.write(sealed)
            it.fd.sync()
        }
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IOException("could not store the account")
        }
    }

    override fun clear() {
        file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
        try {
            KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(alias)
        } catch (_: GeneralSecurityException) {
        }
    }

    private fun key(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        val AAD = "vazie-keep/account/v1".toByteArray(Charsets.UTF_8)
    }
}
