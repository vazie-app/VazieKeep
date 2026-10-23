package app.vazie.keep.biometric

import app.vazie.keep.data.UnlockError
import app.vazie.keep.data.UnlockResult
import app.vazie.keep.data.VaultRepository
import app.vazie.keep.data.VaultState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.UUID
import javax.crypto.AEADBadTagException

sealed interface BiometricStatus {
    data class Unavailable(val reason: BiometricAvailability) : BiometricStatus

    data object Off : BiometricStatus

    data class On(val keyFileRequired: Boolean) : BiometricStatus

    data object NeedsSetup : BiometricStatus
}

enum class EnableError { NotUnlocked, NoPassword, NotAvailable, Cancelled, LockedOut, Failed }

sealed interface EnableResult {
    data object Enabled : EnableResult

    data class Failure(val error: EnableError) : EnableResult
}

sealed interface BiometricUnlockResult {
    data object Success : BiometricUnlockResult

    data object Cancelled : BiometricUnlockResult

    data object LockedOut : BiometricUnlockResult

    data object Failed : BiometricUnlockResult

    data object NeedsSetup : BiometricUnlockResult

    data class VaultError(val error: UnlockError) : BiometricUnlockResult
}

sealed interface MaterialResult {
    class Obtained(val bytes: ByteArray, val record: BiometricRecord.Active) : MaterialResult

    data object Cancelled : MaterialResult

    data object LockedOut : MaterialResult

    data object Failed : MaterialResult

    data object NeedsSetup : MaterialResult

    data class VaultError(val error: UnlockError) : MaterialResult
}

// What Autofill needs from biometric unlock: the stored password component after a fingerprint,
// without opening anything. Implemented by BiometricUnlock, faked in tests.
interface BiometricMaterialSource {
    suspend fun refresh(): BiometricStatus

    suspend fun obtainMaterial(text: PromptText): MaterialResult

    suspend fun materialRejected(record: BiometricRecord.Active): BiometricUnlockResult
}

class BiometricUnlock(
    private val repository: VaultRepository,
    private val store: BiometricSetupStore,
    private val protector: CredentialProtector,
    private val authenticator: BiometricAuthenticator,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val newAlias: () -> String = { "vazie_keep_biometric_${UUID.randomUUID()}" },
) : BiometricMaterialSource {
    private val mutableStatus = MutableStateFlow<BiometricStatus>(BiometricStatus.Off)
    val status: StateFlow<BiometricStatus> = mutableStatus.asStateFlow()

    fun isSupported(): Boolean = authenticator.availability() == BiometricAvailability.Available

    override suspend fun refresh(): BiometricStatus = withContext(io) {
        val availability = authenticator.availability()
        val vaultUri = repository.reference()?.uri
        val status = when (val record = store.load()) {
            null -> notSetUp(availability)
            is BiometricRecord.Invalidated -> when {
                record.vaultUri != null && record.vaultUri != vaultUri -> {
                    store.clear()
                    notSetUp(availability)
                }
                else -> BiometricStatus.NeedsSetup
            }
            is BiometricRecord.Active -> when {
                record.vaultUri != vaultUri -> {
                    protector.delete(record.alias)
                    store.clear()
                    notSetUp(availability)
                }
                else -> probe(record, availability)
            }
        }
        status.also { mutableStatus.value = it }
    }

    suspend fun enable(text: PromptText): EnableResult {
        val unlocked = repository.state.value as? VaultState.Unlocked ?: return EnableResult.Failure(EnableError.NotUnlocked)
        val reference = repository.reference() ?: return EnableResult.Failure(EnableError.NotUnlocked)
        if (authenticator.availability() != BiometricAvailability.Available) return EnableResult.Failure(EnableError.NotAvailable)
        if (!unlocked.materialRetained) return EnableResult.Failure(EnableError.NoPassword)
        val alias = newAlias()
        val cipher = try {
            withContext(io) { protector.encryptCipher(alias) }
        } catch (_: ProtectorException) {
            return EnableResult.Failure(EnableError.NotAvailable)
        }
        val authorized = when (val auth = authenticator.authenticate(text, cipher)) {
            is AuthResult.Success -> auth.cipher
            else -> {
                protector.delete(alias)
                return EnableResult.Failure(auth.toEnableError())
            }
        }
        val material = repository.unlockMaterial() ?: run {
            protector.delete(alias)
            return EnableResult.Failure(EnableError.NotUnlocked)
        }
        val draft = BiometricRecord.Active(reference.uri, unlocked.info.fingerprint, alias, unlocked.usedKeyFile, ByteArray(0), ByteArray(0))
        val record = try {
            material.useBytes { plain ->
                authorized.updateAAD(draft.associatedData())
                val sealed = authorized.doFinal(plain)
                BiometricRecord.Active(draft.vaultUri, draft.fingerprint, alias, draft.keyFileRequired, authorized.iv, sealed)
            }
        } catch (_: GeneralSecurityException) {
            protector.delete(alias)
            return EnableResult.Failure(EnableError.Failed)
        } catch (_: IllegalStateException) {
            protector.delete(alias)
            return EnableResult.Failure(EnableError.Failed)
        } catch (_: ProviderException) {
            protector.delete(alias)
            return EnableResult.Failure(EnableError.Failed)
        } finally {
            material.close()
        }
        val previous = withContext(io) { store.load() }
        try {
            withContext(io) { store.save(record) }
        } catch (_: IOException) {
            protector.delete(alias)
            withContext(io) { store.clear() }
            refresh()
            return EnableResult.Failure(EnableError.Failed)
        }
        (previous as? BiometricRecord.Active)?.alias?.takeIf { it != alias }?.let(protector::delete)
        refresh()
        return EnableResult.Enabled
    }

    // Authenticates and decrypts the stored password component, without opening anything. The
    // caller opens the vault with it and reports a rejection through `materialRejected`.
    override suspend fun obtainMaterial(text: PromptText): MaterialResult {
        val reference = repository.reference() ?: return MaterialResult.VaultError(UnlockError.FileUnavailable)
        val record = withContext(io) { store.load() } as? BiometricRecord.Active
        if (record == null || record.vaultUri != reference.uri) {
            refresh()
            return MaterialResult.NeedsSetup
        }
        if (record.keyFileRequired && reference.keyFileUri == null) {
            return MaterialResult.VaultError(UnlockError.KeyFileUnavailable)
        }
        val cipher = try {
            withContext(io) { protector.decryptCipher(record.alias, record.iv) }
        } catch (_: ProtectorException.Invalidated) {
            invalidate(record)
            return MaterialResult.NeedsSetup
        } catch (_: ProtectorException.Unavailable) {
            return MaterialResult.Failed
        }
        val authorized = when (val auth = authenticator.authenticate(text, cipher)) {
            is AuthResult.Success -> auth.cipher
            AuthResult.Cancelled -> return MaterialResult.Cancelled
            AuthResult.LockedOut -> return MaterialResult.LockedOut
            AuthResult.Failed -> return MaterialResult.Failed
        }
        // Only a failed tag means the blob was damaged or tampered with. Anything else (for
        // example Keystore pruning the operation) is transient and keeps the setup.
        val material = try {
            authorized.updateAAD(record.associatedData())
            authorized.doFinal(record.ciphertext)
        } catch (_: AEADBadTagException) {
            invalidate(record)
            return MaterialResult.NeedsSetup
        } catch (_: GeneralSecurityException) {
            return MaterialResult.Failed
        } catch (_: IllegalStateException) {
            return MaterialResult.Failed
        } catch (_: ProviderException) {
            return MaterialResult.Failed
        }
        return MaterialResult.Obtained(material, record)
    }

    // The vault refused material that decrypted fine: the setup no longer fits this vault.
    override suspend fun materialRejected(record: BiometricRecord.Active): BiometricUnlockResult = invalidate(record)

    suspend fun unlock(text: PromptText): BiometricUnlockResult {
        val obtained = when (val result = obtainMaterial(text)) {
            is MaterialResult.Obtained -> result
            MaterialResult.Cancelled -> return BiometricUnlockResult.Cancelled
            MaterialResult.LockedOut -> return BiometricUnlockResult.LockedOut
            MaterialResult.Failed -> return BiometricUnlockResult.Failed
            MaterialResult.NeedsSetup -> return BiometricUnlockResult.NeedsSetup
            is MaterialResult.VaultError -> return BiometricUnlockResult.VaultError(result.error)
        }
        val record = obtained.record
        return when (val result = repository.unlockWithMaterial(obtained.bytes, record.keyFileRequired, record.fingerprint)) {
            UnlockResult.Success -> BiometricUnlockResult.Success
            UnlockResult.AlreadyRunning -> BiometricUnlockResult.Failed
            is UnlockResult.Failure -> when (result.error) {
                UnlockError.WrongPassword,
                UnlockError.WrongPasswordOrKeyFile,
                UnlockError.MissingCredentials,
                UnlockError.VaultMismatch,
                -> invalidate(record)
                else -> BiometricUnlockResult.VaultError(result.error)
            }
        }
    }

    suspend fun disable() {
        withContext(io) {
            (store.load() as? BiometricRecord.Active)?.let { protector.delete(it.alias) }
            store.clear()
        }
        refresh()
    }

    private fun probe(record: BiometricRecord.Active, availability: BiometricAvailability): BiometricStatus = try {
        protector.decryptCipher(record.alias, record.iv)
        if (availability == BiometricAvailability.Available) BiometricStatus.On(record.keyFileRequired) else BiometricStatus.Unavailable(availability)
    } catch (_: ProtectorException.Invalidated) {
        markInvalidated(record)
        BiometricStatus.NeedsSetup
    } catch (_: ProtectorException.Unavailable) {
        BiometricStatus.On(record.keyFileRequired)
    }

    private suspend fun invalidate(record: BiometricRecord.Active): BiometricUnlockResult {
        withContext(io) { markInvalidated(record) }
        mutableStatus.value = BiometricStatus.NeedsSetup
        return BiometricUnlockResult.NeedsSetup
    }

    private fun markInvalidated(record: BiometricRecord.Active) {
        protector.delete(record.alias)
        try {
            store.save(BiometricRecord.Invalidated(record.vaultUri))
        } catch (_: IOException) {
            store.clear()
        }
    }

    private fun notSetUp(availability: BiometricAvailability): BiometricStatus =
        if (availability == BiometricAvailability.Available) BiometricStatus.Off else BiometricStatus.Unavailable(availability)

    private fun AuthResult.toEnableError() = when (this) {
        AuthResult.Cancelled -> EnableError.Cancelled
        AuthResult.LockedOut -> EnableError.LockedOut
        else -> EnableError.Failed
    }
}
