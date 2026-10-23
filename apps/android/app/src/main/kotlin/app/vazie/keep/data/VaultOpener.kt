package app.vazie.keep.data

import app.vazie.keep.core.KeepCore
import app.vazie.keep.core.KeepVaultSession
import app.vazie.keep.core.VaultCredentials
import app.vazie.keep.core.VaultInfo
import app.vazie.keep.core.VaultOpenException
import app.vazie.keep.core.VaultSessionException

class OpenedVault(
    val session: KeepVaultSession,
    val info: VaultInfo,
    val version: FileVersion,
    // A write was cut off: the file held neither the old nor the new version at the start.
    val interruptedWrite: Boolean,
    val usedPassword: Boolean,
    val usedKeyFile: Boolean,
    val retainedMaterial: Boolean,
)

sealed interface OpenOutcome {
    class Opened(val vault: OpenedVault) : OpenOutcome

    data class Failed(val error: UnlockError) : OpenOutcome
}

// Reads the file and the key file afresh and opens one session. Knows nothing about screens or
// the application's session: the unlock screen and Autofill both use it.
class VaultOpener(
    private val core: KeepCore,
    private val files: VaultFileAccess,
    private val copies: VaultCopyStore,
    private val timing: (label: String, millis: Long) -> Unit = { _, _ -> },
) {
    suspend fun open(
        reference: VaultReference,
        password: ByteArray?,
        material: ByteArray?,
        useKeyFile: Boolean,
        retainUnlockMaterial: Boolean,
        expectedFingerprint: String?,
    ): OpenOutcome {
        val keyFileUri = reference.keyFileUri?.takeIf { useKeyFile }
        if (password == null && material == null && keyFileUri == null) return OpenOutcome.Failed(UnlockError.MissingCredentials)
        val retain = password != null && retainUnlockMaterial
        val data = try {
            measured("file read") { files.read(reference.uri) }
        } catch (_: VaultFileException.TooLarge) {
            return OpenOutcome.Failed(UnlockError.TooLarge)
        } catch (_: VaultFileException) {
            return OpenOutcome.Failed(UnlockError.FileUnavailable)
        }
        val version = FileVersion.of(data)
        val interrupted = when (copies.inspectWrite(reference.uri, version)) {
            JournalState.Interrupted -> true
            JournalState.Completed, JournalState.NeverWritten -> {
                copies.finishWrite(reference.uri)
                false
            }
            JournalState.None -> false
        }
        suspend fun attempt(typed: ByteArray?): Result<KeepVaultSession> {
            val keyFile = keyFileUri?.let { uri ->
                try {
                    files.readKeyFile(uri)
                } catch (_: VaultFileException.TooLarge) {
                    return Result.failure(UnlockFailure(UnlockError.KeyFileTooLarge))
                } catch (_: VaultFileException) {
                    return Result.failure(UnlockFailure(UnlockError.KeyFileUnavailable))
                }
            }
            val credentials = VaultCredentials(
                password = typed?.copyOf(),
                unlockMaterial = material?.copyOf(),
                keyFile = keyFile,
                retainUnlockMaterial = retain && typed === password,
            )
            return try {
                Result.success(measured("open (KDF + decrypt + parse)") { core.openVault(data, credentials) })
            } catch (error: VaultOpenException) {
                Result.failure(UnlockFailure(error.reason.toUnlockError(keyFile = keyFileUri != null)))
            } finally {
                credentials.close()
            }
        }
        var result = attempt(password)
        val retryWithEmptyPassword = password == null && material == null &&
            (result.exceptionOrNull() as? UnlockFailure)?.error == UnlockError.WrongPasswordOrKeyFile
        if (retryWithEmptyPassword) result = attempt(ByteArray(0))
        val opened = result.getOrElse {
            val error = (it as UnlockFailure).error
            return OpenOutcome.Failed(if (error == UnlockError.Damaged && interrupted) UnlockError.DamagedRestorable else error)
        }
        val info = try {
            opened.info()
        } catch (_: VaultSessionException) {
            opened.close()
            return OpenOutcome.Failed(UnlockError.Unknown)
        }
        if (expectedFingerprint != null && info.fingerprint != expectedFingerprint) {
            opened.close()
            return OpenOutcome.Failed(UnlockError.VaultMismatch)
        }
        return OpenOutcome.Opened(
            OpenedVault(
                session = opened,
                info = info,
                version = version,
                interruptedWrite = interrupted,
                usedPassword = password != null || material != null || retryWithEmptyPassword,
                usedKeyFile = keyFileUri != null,
                retainedMaterial = retain,
            ),
        )
    }

    private suspend inline fun <T> measured(label: String, block: () -> T): T {
        val started = System.nanoTime()
        try {
            return block()
        } finally {
            timing(label, (System.nanoTime() - started) / 1_000_000)
        }
    }
}

internal class UnlockFailure(val error: UnlockError) : Exception(error.name)

internal fun VaultOpenException.Reason.toUnlockError(keyFile: Boolean) = when (this) {
    VaultOpenException.Reason.InvalidCredentials -> if (keyFile) UnlockError.WrongPasswordOrKeyFile else UnlockError.WrongPassword
    VaultOpenException.Reason.MissingCredentials -> UnlockError.MissingCredentials
    VaultOpenException.Reason.InvalidKeyFile -> UnlockError.InvalidKeyFile
    VaultOpenException.Reason.Damaged -> UnlockError.Damaged
    VaultOpenException.Reason.Unsupported -> UnlockError.Unsupported
    VaultOpenException.Reason.NotAVault -> UnlockError.NotAVault
    VaultOpenException.Reason.TooLarge -> UnlockError.TooLarge
    VaultOpenException.Reason.TooManySessions, VaultOpenException.Reason.Unavailable -> UnlockError.Unknown
}
