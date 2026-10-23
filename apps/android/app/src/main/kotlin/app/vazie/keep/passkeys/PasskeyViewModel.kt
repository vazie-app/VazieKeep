package app.vazie.keep.passkeys

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vazie.keep.autofill.UnlockMessage
import app.vazie.keep.autofill.VaultUnlocker
import app.vazie.keep.biometric.BiometricMaterialSource
import app.vazie.keep.biometric.PromptText
import app.vazie.keep.data.OpenedVault
import app.vazie.keep.data.VaultOpener
import app.vazie.keep.data.VaultReferenceStore
import java.nio.CharBuffer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PasskeyUiState {
    data object Loading : PasskeyUiState

    data class Unlock(
        val creating: Boolean,
        val rpId: String,
        val userName: String,
        val biometric: Boolean,
        val busy: Boolean = false,
        val message: UnlockMessage? = null,
        val keyFileName: String? = null,
    ) : PasskeyUiState

    data class Done(val creating: Boolean, val json: String) : PasskeyUiState

    data class Failed(val failure: PasskeyFailure) : PasskeyUiState

    data object Cancelled : PasskeyUiState
}

// One request from Android: check who asks and for which site, unlock the vault, make or use the passkey,
// close the session. Nothing is unlocked for a caller that failed the check.
class PasskeyViewModel(
    private val request: PasskeyRequest?,
    private val references: VaultReferenceStore,
    opener: VaultOpener,
    biometrics: BiometricMaterialSource,
    private val gate: PasskeyGate,
    private val operations: PasskeyOperations,
    private val userNameOf: (entryId: String) -> String?,
    private val onSaved: () -> Unit = {},
) : ViewModel() {
    // Held here, not in saved state: the master password never reaches an instance-state bundle.
    val password = TextFieldState()

    private val mutableState = MutableStateFlow<PasskeyUiState>(PasskeyUiState.Loading)
    val state: StateFlow<PasskeyUiState> = mutableState.asStateFlow()

    private val focusChannel = Channel<Unit>(Channel.CONFLATED)
    val focusRequests: Flow<Unit> = focusChannel.receiveAsFlow()

    private val unlocker = VaultUnlocker(references, opener, biometrics)
    private var allowed: PasskeyGate.Decision.Allowed? = null
    private var prompt: PromptText? = null
    private var working = false
    private var biometricStarted = false

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val asked = request
        if (asked == null) {
            mutableState.value = failed(PasskeyProblem.BadRequest)
            return
        }
        when (val decision = gate.check(asked)) {
            is PasskeyGate.Decision.Refused -> mutableState.value = failed(decision.problem)
            is PasskeyGate.Decision.Allowed -> {
                allowed = decision
                val readiness = unlocker.readiness()
                if (readiness == null) {
                    mutableState.value = PasskeyUiState.Failed(PasskeyFailure.Unlock(app.vazie.keep.autofill.AuthProblem.NoVault))
                    return
                }
                val unlock = PasskeyUiState.Unlock(
                    creating = asked is PasskeyRequest.Create,
                    rpId = decision.rpId,
                    userName = when (asked) {
                        is PasskeyRequest.Create -> asked.options.userName
                        is PasskeyRequest.Get -> userNameOf(asked.entryId).orEmpty()
                    },
                    biometric = readiness.biometric,
                    keyFileName = readiness.keyFileName,
                )
                mutableState.value = unlock
                maybeStartBiometric(unlock)
            }
        }
    }

    fun start(promptText: PromptText) {
        prompt = promptText
        (state.value as? PasskeyUiState.Unlock)?.let(::maybeStartBiometric)
    }

    fun unlockWithPassword() {
        val current = state.value as? PasskeyUiState.Unlock ?: return
        if (working) return
        val bytes = password.text.takeIf { it.isNotEmpty() }?.let(::encodeUtf8)
        password.clearText()
        if (bytes == null && references.load()?.keyFileUri == null) return
        working = true
        mutableState.value = current.copy(busy = true, message = null)
        viewModelScope.launch { proceed(unlocker.withPassword(bytes, current.biometric), current) }
    }

    fun unlockWithBiometric() {
        val current = state.value as? PasskeyUiState.Unlock ?: return
        if (working || !current.biometric) return
        runBiometric(current)
    }

    fun cancel() {
        if (working) return
        password.clearText()
        mutableState.value = PasskeyUiState.Cancelled
    }

    private fun maybeStartBiometric(unlock: PasskeyUiState.Unlock) {
        if (!unlock.biometric || biometricStarted || prompt == null) return
        biometricStarted = true
        runBiometric(unlock)
    }

    private fun runBiometric(current: PasskeyUiState.Unlock) {
        val text = prompt ?: return
        working = true
        mutableState.value = current.copy(busy = true, message = null)
        viewModelScope.launch { proceed(unlocker.withBiometric(text), current) }
    }

    private suspend fun proceed(step: VaultUnlocker.Step, current: PasskeyUiState.Unlock) {
        when (step) {
            is VaultUnlocker.Step.Opened -> withContext(NonCancellable) { finish(step.vault, current.creating) }
            is VaultUnlocker.Step.Retry -> {
                working = false
                mutableState.value = current.copy(busy = false, message = step.message, biometric = step.biometric)
                if (step.refocus) focusChannel.trySend(Unit)
            }
            is VaultUnlocker.Step.Failed -> {
                working = false
                password.clearText()
                mutableState.value = PasskeyUiState.Failed(PasskeyFailure.Unlock(step.problem))
            }
        }
    }

    private suspend fun finish(vault: OpenedVault, creating: Boolean) {
        val asked = request
        val decision = allowed
        val reference = references.load()
        val outcome = try {
            if (asked == null || decision == null || reference == null) {
                PasskeyOutcome.Failed(PasskeyProblem.Failed)
            } else {
                when (asked) {
                    is PasskeyRequest.Create -> operations.create(vault, reference, decision.rpId, asked.options, decision.clientData)
                    is PasskeyRequest.Get -> operations.sign(vault, asked.entryId, decision.rpId, asked.options, decision.clientData)
                }
            }
        } finally {
            vault.session.close()
        }
        working = false
        password.clearText()
        mutableState.value = when (outcome) {
            is PasskeyOutcome.Done -> {
                if (creating) onSaved()
                PasskeyUiState.Done(creating, outcome.json)
            }
            is PasskeyOutcome.Failed -> failed(outcome.problem)
        }
    }

    private fun failed(problem: PasskeyProblem) = PasskeyUiState.Failed(PasskeyFailure.Passkey(problem))

    private fun encodeUtf8(text: CharSequence): ByteArray {
        val buffer = Charsets.UTF_8.encode(CharBuffer.wrap(text))
        return ByteArray(buffer.remaining()).also {
            buffer.get(it)
            if (buffer.hasArray()) buffer.array().fill(0)
        }
    }

    override fun onCleared() {
        password.clearText()
    }
}
