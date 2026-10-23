package app.vazie.keep

import android.content.Context
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import app.vazie.keep.biometric.AuthResult
import app.vazie.keep.biometric.BiometricAuthenticator
import app.vazie.keep.biometric.BiometricAvailability
import app.vazie.keep.biometric.BiometricRecord
import app.vazie.keep.biometric.BiometricStatus
import app.vazie.keep.biometric.BiometricUnlock
import app.vazie.keep.biometric.BiometricUnlockResult
import app.vazie.keep.biometric.FileBiometricSetupStore
import app.vazie.keep.biometric.KeystoreCredentialProtector
import app.vazie.keep.biometric.PromptText
import app.vazie.keep.biometric.ProtectorException
import app.vazie.keep.core.RustKeepCore
import app.vazie.keep.data.ContentResolverDocumentPort
import app.vazie.keep.data.ContentResolverVaultFileAccess
import app.vazie.keep.data.FileVaultCopyStore
import app.vazie.keep.data.JournaledOverwriteWriter
import app.vazie.keep.data.VaultReference
import app.vazie.keep.data.VaultReferenceStore
import app.vazie.keep.data.VaultRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Runs against the real Android Keystore of the device. No biometric is presented, so these tests
// check the key's policy and that nothing works without authentication.
@RunWith(AndroidJUnit4::class)
class KeystoreTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val protector = KeystoreCredentialProtector()
    private val alias = "vazie_keep_instrumentation_test"
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @After
    fun cleanUp() = protector.delete(alias)

    @Test
    @SdkSuppress(minSdkVersion = 31)
    fun keyIsHardwareBackedAuthBoundAndNotExportable() {
        protector.encryptCipher(alias)
        val key = keyStore.getKey(alias, null) as SecretKey
        assertNull(key.encoded)
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        assertTrue(info.isUserAuthenticationRequired)
        assertTrue(info.isInvalidatedByBiometricEnrollment)
        assertEquals(0, info.userAuthenticationValidityDurationSeconds)
        assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, info.userAuthenticationType)
        assertEquals(256, info.keySize)
        assertTrue(info.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT || info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX)
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, info.purposes)
    }

    @Test
    fun nothingIsEncryptedWithoutBiometricAuthentication() {
        val cipher = protector.encryptCipher(alias)
        assertFailsWith<GeneralSecurityException> { cipher.doFinal(ByteArray(33)) }
    }

    @Test
    fun aMissingKeyIsReportedAsInvalidated() {
        assertFailsWith<ProtectorException.Invalidated> { protector.decryptCipher(alias, ByteArray(12)) }
        protector.encryptCipher(alias)
        assertTrue(keyStore.containsAlias(alias))
        protector.delete(alias)
        assertFalse(keyStore.containsAlias(alias))
        assertFailsWith<ProtectorException.Invalidated> { protector.decryptCipher(alias, ByteArray(12)) }
    }

    // What a reinstall or a restored backup looks like: the blob is on disk, the Keystore key is not.
    @Test
    fun aBlobWithoutItsKeyNeedsSetupAndNeverPrompts() = runBlocking {
        val file = context.noBackupFilesDir.resolve("instrumentation_biometric.bin")
        val store = FileBiometricSetupStore(file)
        val reference = VaultReference("file:///nowhere.kdbx", "nowhere.kdbx", 0)
        val references = object : VaultReferenceStore {
            override fun load() = reference
            override fun save(reference: VaultReference) = Unit
            override fun clear() = Unit
        }
        val repository = VaultRepository(RustKeepCore, ContentResolverVaultFileAccess(context.contentResolver), references, JournaledOverwriteWriter(ContentResolverDocumentPort(context.contentResolver), FileVaultCopyStore(java.io.File(context.cacheDir, "copies"))), FileVaultCopyStore(java.io.File(context.cacheDir, "copies")))
        val prompts = mutableListOf<PromptText>()
        val authenticator = object : BiometricAuthenticator {
            override fun availability() = BiometricAvailability.Available
            override suspend fun authenticate(text: PromptText, cipher: Cipher): AuthResult {
                prompts += text
                return AuthResult.Cancelled
            }
        }
        store.save(BiometricRecord.Active(reference.uri, "fp", alias, false, ByteArray(12), ByteArray(49)))
        val biometrics = BiometricUnlock(repository, store, protector, authenticator)
        try {
            assertEquals(BiometricStatus.NeedsSetup, biometrics.refresh())
            assertIs<BiometricRecord.Invalidated>(store.load())
            assertEquals(BiometricUnlockResult.NeedsSetup, biometrics.unlock(PromptText("t", null, "n")))
            assertTrue(prompts.isEmpty())
        } finally {
            store.clear()
        }
    }
}
