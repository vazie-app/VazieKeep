package app.vazie.keep.testing

import app.vazie.keep.biometric.AuthResult
import app.vazie.keep.biometric.BiometricAuthenticator
import app.vazie.keep.biometric.BiometricAvailability
import app.vazie.keep.biometric.BiometricRecord
import app.vazie.keep.biometric.BiometricSetupStore
import app.vazie.keep.biometric.CredentialProtector
import app.vazie.keep.biometric.PromptText
import app.vazie.keep.biometric.ProtectorException
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

val PROMPT = PromptText("Unlock vault", null, "Use master password")

class FakeAuthenticator(var availability: BiometricAvailability = BiometricAvailability.Available) : BiometricAuthenticator {
    // null = approve; otherwise the outcome to report instead.
    var outcome: AuthResult? = null
    val prompts = mutableListOf<PromptText>()

    override fun availability() = availability

    override suspend fun authenticate(text: PromptText, cipher: Cipher): AuthResult {
        prompts += text
        return outcome ?: AuthResult.Success(cipher)
    }
}

// Real AES-GCM through JCA with software keys, so encryption, IVs and associated data are exercised
// the same way as with Android Keystore; only hardware backing and user authentication are missing.
class SoftwareProtector : CredentialProtector {
    val keys = mutableMapOf<String, SecretKey>()
    val deleted = mutableListOf<String>()
    var unavailable = false

    override fun encryptCipher(alias: String): Cipher {
        if (unavailable) throw ProtectorException.Unavailable()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        keys[alias] = key
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    override fun decryptCipher(alias: String, iv: ByteArray): Cipher {
        val key = keys[alias] ?: throw ProtectorException.Invalidated()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
    }

    override fun delete(alias: String) {
        deleted += alias
        keys.remove(alias)
    }

    // What enrollment changes, a removed screen lock or a reinstall do to a Keystore key.
    fun invalidateAll() = keys.clear()
}

class InMemorySetupStore : BiometricSetupStore {
    var record: BiometricRecord? = null
    var failWrites = false

    override fun load() = record

    override fun save(record: BiometricRecord) {
        if (failWrites) throw IOException("disk full")
        this.record = record
    }

    override fun clear() {
        record = null
    }
}
