package app.vazie.keep.autofill

import app.vazie.keep.biometric.BiometricMaterialSource
import app.vazie.keep.biometric.BiometricRecord
import app.vazie.keep.biometric.BiometricStatus
import app.vazie.keep.biometric.MaterialResult
import app.vazie.keep.biometric.PromptText
import app.vazie.keep.data.OpenOutcome
import app.vazie.keep.data.OpenedVault
import app.vazie.keep.data.UnlockError
import app.vazie.keep.data.VaultOpener
import app.vazie.keep.data.VaultReferenceStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// The unlock half that fill and save share: fingerprint (only when asked), then the master
// password, with the key file read afresh each time. It opens one session and hands it over; the
// caller closes it.
class VaultUnlocker(
    private val references: VaultReferenceStore,
    private val opener: VaultOpener,
    private val biometrics: BiometricMaterialSource,
    private val timing: (label: String, millis: Long) -> Unit = { _, _ -> },
) {
    class Readiness(val biometric: Boolean, val keyFileName: String?)

    sealed interface Step {
        class Opened(val vault: OpenedVault) : Step

        // Back to the form: show `message`, keep the fingerprint button only if `biometric`.
        data class Retry(val message: UnlockMessage?, val biometric: Boolean, val refocus: Boolean) : Step

        data class Failed(val problem: AuthProblem) : Step
    }

    suspend fun readiness(): Readiness? {
        val reference = references.load() ?: return null
        return Readiness(biometrics.refresh() is BiometricStatus.On, reference.keyFileName)
    }

    suspend fun withBiometric(prompt: PromptText): Step {
        val started = System.nanoTime()
        return when (val result = biometrics.obtainMaterial(prompt)) {
            is MaterialResult.Obtained -> withContext(NonCancellable) {
                timing("autofill fingerprint", (System.nanoTime() - started) / 1_000_000)
                val reference = references.load()
                val openStarted = System.nanoTime()
                val outcome = reference?.let { opener.open(it, null, result.bytes, result.record.keyFileRequired, false, result.record.fingerprint) }
                result.bytes.fill(0)
                timing("autofill open", (System.nanoTime() - openStarted) / 1_000_000)
                afterOpen(outcome, biometricAvailable = true, record = result.record)
            }
            MaterialResult.Cancelled -> Step.Retry(null, biometric = true, refocus = true)
            MaterialResult.LockedOut -> Step.Retry(UnlockMessage.BiometricLockedOut, biometric = true, refocus = false)
            MaterialResult.Failed -> Step.Retry(UnlockMessage.BiometricFailed, biometric = true, refocus = false)
            MaterialResult.NeedsSetup -> Step.Retry(UnlockMessage.BiometricNeedsSetup, biometric = false, refocus = true)
            is MaterialResult.VaultError -> Step.Failed(problemOf(result.error))
        }
    }

    // `bytes` is wiped here. Null means no typed password, which only a key file can stand in for.
    suspend fun withPassword(bytes: ByteArray?, biometricAvailable: Boolean): Step = withContext(NonCancellable) {
        val reference = references.load()
        if (reference == null) {
            bytes?.fill(0)
            return@withContext Step.Failed(AuthProblem.NoVault)
        }
        val started = System.nanoTime()
        val outcome = opener.open(reference, bytes, null, useKeyFile = true, retainUnlockMaterial = false, expectedFingerprint = null)
        bytes?.fill(0)
        timing("autofill open", (System.nanoTime() - started) / 1_000_000)
        afterOpen(outcome, biometricAvailable, record = null)
    }

    private suspend fun afterOpen(outcome: OpenOutcome?, biometricAvailable: Boolean, record: BiometricRecord.Active?): Step = when (outcome) {
        null -> Step.Failed(AuthProblem.NoVault)
        is OpenOutcome.Opened -> Step.Opened(outcome.vault)
        is OpenOutcome.Failed -> {
            val error = outcome.error
            when {
                record != null && error in REJECTED_BY_VAULT -> {
                    biometrics.materialRejected(record)
                    Step.Retry(UnlockMessage.BiometricNeedsSetup, biometric = false, refocus = true)
                }
                error == UnlockError.WrongPassword || error == UnlockError.WrongPasswordOrKeyFile ->
                    Step.Retry(UnlockMessage.WrongPassword, biometricAvailable, refocus = true)
                else -> Step.Failed(problemOf(error))
            }
        }
    }

    private fun problemOf(error: UnlockError) = when (error) {
        UnlockError.KeyFileUnavailable, UnlockError.KeyFileTooLarge, UnlockError.InvalidKeyFile -> AuthProblem.KeyFileUnavailable
        UnlockError.DamagedRestorable -> AuthProblem.RestoreNeeded
        UnlockError.FileUnavailable -> AuthProblem.FileUnavailable
        UnlockError.VaultMismatch -> AuthProblem.VaultChanged
        else -> AuthProblem.CouldNotOpen
    }

    private companion object {
        val REJECTED_BY_VAULT = setOf(
            UnlockError.WrongPassword,
            UnlockError.WrongPasswordOrKeyFile,
            UnlockError.MissingCredentials,
            UnlockError.VaultMismatch,
        )
    }
}
