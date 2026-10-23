package app.vazie.keep.biometric

import app.vazie.keep.testing.testRepository
import app.vazie.keep.data.UnlockError
import app.vazie.keep.data.UnlockResult
import app.vazie.keep.data.VaultState
import app.vazie.keep.testing.FINGERPRINT
import app.vazie.keep.testing.FakeAuthenticator
import app.vazie.keep.testing.FakeFileAccess
import app.vazie.keep.testing.FakeKeepCore
import app.vazie.keep.testing.InMemoryReferenceStore
import app.vazie.keep.testing.InMemorySetupStore
import app.vazie.keep.testing.KDBX_HEADER
import app.vazie.keep.testing.KEY_FILE_URI
import app.vazie.keep.testing.PROMPT
import app.vazie.keep.testing.SoftwareProtector
import app.vazie.keep.testing.VAULT_URI
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import javax.crypto.Cipher
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BiometricUnlockTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = FakeKeepCore().apply { accepts = { password, _ -> password == PASSWORD } }
    private val files = FakeFileAccess()
    private val references = InMemoryReferenceStore()
    private val repository = testRepository(core, files, references, retainUnlockMaterial = { true })
    private val store = InMemorySetupStore()
    private val protector = SoftwareProtector()
    private val authenticator = FakeAuthenticator()
    private var aliases = 0
    private val biometrics = BiometricUnlock(repository, store, protector, authenticator, io = dispatcher, newAlias = { "alias-${++aliases}" })

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private suspend fun enabled(): BiometricRecord.Active {
        assertEquals(UnlockResult.Success, repository.unlock(PASSWORD.toByteArray()))
        assertEquals(EnableResult.Enabled, biometrics.enable(PROMPT))
        repository.lock()
        return store.record as BiometricRecord.Active
    }

    @Test
    fun enableStoresOnlyCiphertextBoundToTheVault() = test {
        val record = enabled()
        assertEquals(VAULT_URI, record.vaultUri)
        assertEquals(FINGERPRINT, record.fingerprint)
        assertEquals("alias-1", record.alias)
        assertFalse(record.keyFileRequired)
        assertEquals(12, record.iv.size)
        assertFalse(record.ciphertext.decodeToString().contains(PASSWORD))
        assertFalse(record.toString().contains(PASSWORD))
        assertEquals(BiometricStatus.On(keyFileRequired = false), biometrics.status.value)
        assertEquals(1, authenticator.prompts.size)
    }

    @Test
    fun biometricUnlockReopensWithTheRememberedMaterial() = test {
        enabled()
        assertEquals(BiometricUnlockResult.Success, biometrics.unlock(PROMPT))
        val seen = core.seen.last()
        assertNull(seen.password)
        assertEquals("M:$PASSWORD", seen.material)
        assertFalse(seen.retain)
        assertIs<VaultState.Unlocked>(repository.state.value)
    }

    @Test
    fun enableCancelledOrFailedLeavesNothingBehind() = test {
        repository.unlock(PASSWORD.toByteArray())
        for ((outcome, error) in listOf(
            AuthResult.Cancelled to EnableError.Cancelled,
            AuthResult.LockedOut to EnableError.LockedOut,
            AuthResult.Failed to EnableError.Failed,
        )) {
            authenticator.outcome = outcome
            assertEquals(EnableResult.Failure(error), biometrics.enable(PROMPT))
            assertNull(store.record)
            assertTrue(protector.keys.isEmpty())
        }
        assertEquals(listOf("alias-1", "alias-2", "alias-3"), protector.deleted)
    }

    @Test
    fun enableNeedsAnOpenVaultATypedPasswordAndStrongBiometrics() = test {
        assertEquals(EnableResult.Failure(EnableError.NotUnlocked), biometrics.enable(PROMPT))
        repository.selectKeyFile(KEY_FILE_URI)
        core.accepts = { _, keyFile -> keyFile != null }
        repository.unlock(null)
        assertEquals(EnableResult.Failure(EnableError.NoPassword), biometrics.enable(PROMPT))
        repository.unlock(PASSWORD.toByteArray())
        authenticator.availability = BiometricAvailability.NoneEnrolled
        assertEquals(EnableResult.Failure(EnableError.NotAvailable), biometrics.enable(PROMPT))
        authenticator.availability = BiometricAvailability.Available
        protector.unavailable = true
        assertEquals(EnableResult.Failure(EnableError.NotAvailable), biometrics.enable(PROMPT))
        assertTrue(authenticator.prompts.isEmpty())
        assertNull(store.record)
    }

    @Test
    fun aFailedWriteRemovesTheKey() = test {
        repository.unlock(PASSWORD.toByteArray())
        store.failWrites = true
        assertEquals(EnableResult.Failure(EnableError.Failed), biometrics.enable(PROMPT))
        assertTrue(protector.keys.isEmpty())
        assertNull(store.record)
    }

    @Test
    fun cancelledOrFailedPromptFallsBackToThePassword() = test {
        enabled()
        for ((outcome, result) in listOf(
            AuthResult.Cancelled to BiometricUnlockResult.Cancelled,
            AuthResult.LockedOut to BiometricUnlockResult.LockedOut,
            AuthResult.Failed to BiometricUnlockResult.Failed,
        )) {
            authenticator.outcome = outcome
            assertEquals(result, biometrics.unlock(PROMPT))
            assertEquals(VaultState.Locked, repository.state.value)
            assertIs<BiometricRecord.Active>(store.record)
        }
        assertEquals(UnlockResult.Success, repository.unlock(PASSWORD.toByteArray()))
    }

    @Test
    fun invalidatedKeyNeedsSetupWithoutPrompting() = test {
        enabled()
        protector.invalidateAll()
        assertEquals(BiometricStatus.NeedsSetup, biometrics.refresh())
        assertEquals(BiometricRecord.Invalidated(VAULT_URI), store.record)
        assertEquals(BiometricUnlockResult.NeedsSetup, biometrics.unlock(PROMPT))
        assertTrue(authenticator.prompts.size == 1)
    }

    @Test
    fun corruptedCiphertextIsTreatedAsInvalidated() = test {
        val record = enabled()
        record.ciphertext[0] = (record.ciphertext[0].toInt() xor 1).toByte()
        assertEquals(BiometricUnlockResult.NeedsSetup, biometrics.unlock(PROMPT))
        assertEquals(BiometricRecord.Invalidated(VAULT_URI), store.record)
        assertTrue("alias-1" in protector.deleted)
        assertEquals(VaultState.Locked, repository.state.value)
    }

    @Test
    fun tamperedMetadataFailsAuthentication() = test {
        val record = enabled()
        store.record = BiometricRecord.Active(record.vaultUri, record.fingerprint, record.alias, true, record.iv, record.ciphertext)
        repository.selectKeyFile(KEY_FILE_URI)
        assertEquals(BiometricUnlockResult.NeedsSetup, biometrics.unlock(PROMPT))
        assertTrue(core.seen.size == 1)
    }

    @Test
    fun changedVaultPasswordInvalidatesTheSetup() = test {
        enabled()
        core.accepts = { password, _ -> password == "a new password" }
        assertEquals(BiometricUnlockResult.NeedsSetup, biometrics.unlock(PROMPT))
        assertEquals(BiometricStatus.NeedsSetup, biometrics.status.value)
        assertTrue(protector.keys.isEmpty())
        assertEquals(UnlockResult.Success, repository.unlock("a new password".toByteArray()))
    }

    @Test
    fun aDifferentDatabaseAtTheSameUriIsRefused() = test {
        enabled()
        core.fingerprint = "another database"
        assertEquals(BiometricUnlockResult.NeedsSetup, biometrics.unlock(PROMPT))
        assertEquals(VaultState.Locked, repository.state.value)
        assertTrue(core.sessions.last().isClosed)
        assertIs<BiometricRecord.Invalidated>(store.record)
    }

    @Test
    fun anotherVaultDropsTheOldSetup() = test {
        enabled()
        files.headers["content://test/second.kdbx"] = KDBX_HEADER
        repository.select("content://test/second.kdbx")
        assertEquals(BiometricStatus.Off, biometrics.refresh())
        assertNull(store.record)
        assertTrue(protector.keys.isEmpty())
    }

    @Test
    fun disableDeletesTheBlobAndTheKey() = test {
        enabled()
        biometrics.disable()
        assertNull(store.record)
        assertTrue(protector.keys.isEmpty())
        assertEquals(listOf("alias-1"), protector.deleted)
        assertEquals(BiometricStatus.Off, biometrics.status.value)
        assertEquals(BiometricUnlockResult.NeedsSetup, biometrics.unlock(PROMPT))
    }

    @Test
    fun reEnablingReplacesTheOldKey() = test {
        enabled()
        repository.unlock(PASSWORD.toByteArray())
        assertEquals(EnableResult.Enabled, biometrics.enable(PROMPT))
        assertEquals("alias-2", (store.record as BiometricRecord.Active).alias)
        assertEquals(setOf("alias-2"), protector.keys.keys)
    }

    @Test
    fun theKeyFileStaysASecondFactor() = test {
        core.accepts = { password, keyFile -> password == PASSWORD && keyFile == "key-file-bytes" }
        repository.selectKeyFile(KEY_FILE_URI)
        val record = enabled()
        assertTrue(record.keyFileRequired)
        assertEquals(BiometricStatus.On(keyFileRequired = true), biometrics.status.value)
        assertFalse(record.ciphertext.decodeToString().contains("key-file-bytes"))

        assertEquals(BiometricUnlockResult.Success, biometrics.unlock(PROMPT))
        assertEquals("key-file-bytes", core.seen.last().keyFile)
        repository.lock()

        repository.removeKeyFile()
        val prompts = authenticator.prompts.size
        assertEquals(BiometricUnlockResult.VaultError(UnlockError.KeyFileUnavailable), biometrics.unlock(PROMPT))
        assertEquals(prompts, authenticator.prompts.size)
        assertIs<BiometricRecord.Active>(store.record)

        repository.selectKeyFile(KEY_FILE_URI)
        files.keyFileFailure = app.vazie.keep.data.VaultFileException.Unavailable()
        assertEquals(BiometricUnlockResult.VaultError(UnlockError.KeyFileUnavailable), biometrics.unlock(PROMPT))
        assertIs<BiometricRecord.Active>(store.record)
    }

    @Test
    fun withoutAKeyFileRequirementASelectedKeyFileIsIgnored() = test {
        enabled()
        repository.selectKeyFile(KEY_FILE_URI)
        assertEquals(BiometricUnlockResult.Success, biometrics.unlock(PROMPT))
        assertNull(core.seen.last().keyFile)
    }

    @Test
    fun unavailableBiometricsHideTheButtonButKeepTheSetup() = test {
        enabled()
        authenticator.availability = BiometricAvailability.Unavailable
        assertEquals(BiometricStatus.Unavailable(BiometricAvailability.Unavailable), biometrics.refresh())
        assertIs<BiometricRecord.Active>(store.record)
        authenticator.availability = BiometricAvailability.Available
        assertIs<BiometricStatus.On>(biometrics.refresh())
    }

    @Test
    fun anOperationLostAfterAuthenticationKeepsTheSetup() = test {
        enabled()
        authenticator.outcome = AuthResult.Success(PrunedCipher())
        assertEquals(BiometricUnlockResult.Failed, biometrics.unlock(PROMPT))
        assertIs<BiometricRecord.Active>(store.record)
        assertEquals(setOf("alias-1"), protector.keys.keys)
        authenticator.outcome = null
        assertEquals(BiometricUnlockResult.Success, biometrics.unlock(PROMPT))
    }

    @Test
    fun theMaterialIsWipedAfterUse() = test {
        enabled()
        biometrics.unlock(PROMPT)
        val material = core.received.last().unlockMaterial!!
        assertContentEquals(ByteArray(material.size), material)
    }

    private companion object {
        const val PASSWORD = "correct horse"
    }
}

// What Android Keystore hands back when it pruned the operation behind an authorized cipher.
private class PrunedCipher : Cipher(PrunedSpi(), getInstance("AES/GCM/NoPadding").provider, "AES/GCM/NoPadding") {
    init {
        init(DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(ByteArray(32), "AES"))
    }
}

private class PrunedSpi : javax.crypto.CipherSpi() {
    override fun engineSetMode(mode: String?) = Unit
    override fun engineSetPadding(padding: String?) = Unit
    override fun engineGetBlockSize() = 16
    override fun engineGetOutputSize(inputLen: Int) = inputLen
    override fun engineGetIV(): ByteArray? = null
    override fun engineGetParameters(): java.security.AlgorithmParameters? = null
    override fun engineInit(opmode: Int, key: java.security.Key?, random: java.security.SecureRandom?) = Unit
    override fun engineInit(opmode: Int, key: java.security.Key?, params: java.security.spec.AlgorithmParameterSpec?, random: java.security.SecureRandom?) = Unit
    override fun engineInit(opmode: Int, key: java.security.Key?, params: java.security.AlgorithmParameters?, random: java.security.SecureRandom?) = Unit
    override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray? = null
    override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int) = 0
    override fun engineUpdateAAD(src: ByteArray?, offset: Int, len: Int) = Unit
    override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray =
        throw javax.crypto.IllegalBlockSizeException("operation pruned")
    override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int): Int =
        throw javax.crypto.IllegalBlockSizeException("operation pruned")
}
