package app.vazie.keep.biometric

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

sealed class ProtectorException(message: String) : Exception(message) {
    class Invalidated : ProtectorException("key invalidated or missing")

    class Unavailable : ProtectorException("secure key storage unavailable")
}

// Ciphers returned here are not usable until BiometricPrompt authorizes them.
interface CredentialProtector {
    fun encryptCipher(alias: String): Cipher

    fun decryptCipher(alias: String, iv: ByteArray): Cipher

    fun delete(alias: String)
}

class KeystoreCredentialProtector : CredentialProtector {
    override fun encryptCipher(alias: String): Cipher = try {
        val key = generate(alias)
        if (!isHardwareBacked(key)) throw ProtectorException.Unavailable()
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    } catch (error: ProtectorException) {
        delete(alias)
        throw error
    } catch (_: GeneralSecurityException) {
        delete(alias)
        throw ProtectorException.Unavailable()
    } catch (_: ProviderException) {
        delete(alias)
        throw ProtectorException.Unavailable()
    }

    override fun decryptCipher(alias: String, iv: ByteArray): Cipher {
        val key = try {
            keyStore().getKey(alias, null) as? SecretKey
        } catch (_: GeneralSecurityException) {
            null
        } ?: throw ProtectorException.Invalidated()
        return try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv)) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            throw ProtectorException.Invalidated()
        } catch (_: GeneralSecurityException) {
            throw ProtectorException.Unavailable()
        } catch (_: ProviderException) {
            throw ProtectorException.Unavailable()
        }
    }

    override fun delete(alias: String) {
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun generate(alias: String): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private fun isHardwareBacked(key: SecretKey): Boolean {
        val info = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.securityLevel != KeyProperties.SECURITY_LEVEL_SOFTWARE && info.securityLevel != KeyProperties.SECURITY_LEVEL_UNKNOWN
        } else {
            @Suppress("DEPRECATION")
            info.isInsideSecureHardware
        }
    }

    private fun keyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
