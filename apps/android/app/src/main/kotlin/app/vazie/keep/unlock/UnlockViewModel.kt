package app.vazie.keep.unlock

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vazie.keep.biometric.BiometricStatus
import app.vazie.keep.biometric.BiometricUnlock
import app.vazie.keep.biometric.BiometricUnlockResult
import app.vazie.keep.biometric.PromptText
import app.vazie.keep.data.KeyFileError
import app.vazie.keep.data.KeyFileResult
import app.vazie.keep.data.RestoreOutcome
import app.vazie.keep.data.UnlockError
import app.vazie.keep.data.UnlockResult
import app.vazie.keep.data.VaultRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.CharBuffer

sealed interface UnlockUiState {
    data object Idle : UnlockUiState

    data object Unlocking : UnlockUiState

    data class Failed(val error: UnlockError) : UnlockUiState

    data class BiometricFailed(val problem: BiometricProblem) : UnlockUiState

    data object Unlocked : UnlockUiState

    data object Restored : UnlockUiState

    data object RestoreFailed : UnlockUiState
}

enum class BiometricProblem { LockedOut, Failed, NeedsSetup }

data class KeyFileUi(val name: String?, val error: KeyFileError? = null)

class UnlockViewModel(private val repository: VaultRepository, private val biometrics: BiometricUnlock) : ViewModel() {
    // Held here rather than in rememberSaveable so the password never reaches the saved-state Bundle.
    val password = TextFieldState()

    val vaultName: String? = repository.reference()?.displayName

    private val mutableState = MutableStateFlow<UnlockUiState>(UnlockUiState.Idle)
    val state: StateFlow<UnlockUiState> = mutableState.asStateFlow()

    private val mutableKeyFile = MutableStateFlow(KeyFileUi(repository.reference()?.keyFileName))
    val keyFile: StateFlow<KeyFileUi> = mutableKeyFile.asStateFlow()

    val biometricStatus: StateFlow<BiometricStatus> = biometrics.status

    private val mutableBiometricChecked = MutableStateFlow(false)
    val biometricChecked: StateFlow<Boolean> = mutableBiometricChecked.asStateFlow()

    init {
        viewModelScope.launch {
            biometrics.refresh()
            mutableBiometricChecked.value = true
        }
    }

    val canUnlock: Boolean get() = password.text.isNotEmpty() || keyFile.value.name != null

    fun unlock() {
        if (state.value == UnlockUiState.Unlocking || !canUnlock) return
        val bytes = password.text.takeIf { it.isNotEmpty() }?.encodeUtf8()
        password.clearText()
        mutableState.value = UnlockUiState.Unlocking
        viewModelScope.launch {
            mutableState.value = when (val result = repository.unlock(bytes)) {
                UnlockResult.Success -> UnlockUiState.Unlocked
                is UnlockResult.Failure -> UnlockUiState.Failed(result.error)
                UnlockResult.AlreadyRunning -> UnlockUiState.Unlocking
            }
        }
    }

    fun restoreLastSave() {
        if (state.value == UnlockUiState.Unlocking) return
        mutableState.value = UnlockUiState.Unlocking
        viewModelScope.launch {
            mutableState.value = when (repository.restoreInterruptedWrite()) {
                RestoreOutcome.Restored -> UnlockUiState.Restored
                RestoreOutcome.NothingToRestore, is RestoreOutcome.Failed -> UnlockUiState.RestoreFailed
            }
        }
    }

    fun unlockWithBiometrics(text: PromptText) {
        if (state.value == UnlockUiState.Unlocking) return
        mutableState.value = UnlockUiState.Unlocking
        viewModelScope.launch {
            mutableState.value = when (val result = biometrics.unlock(text)) {
                BiometricUnlockResult.Success -> UnlockUiState.Unlocked
                BiometricUnlockResult.Cancelled -> UnlockUiState.Idle
                BiometricUnlockResult.LockedOut -> UnlockUiState.BiometricFailed(BiometricProblem.LockedOut)
                BiometricUnlockResult.Failed -> UnlockUiState.BiometricFailed(BiometricProblem.Failed)
                BiometricUnlockResult.NeedsSetup -> UnlockUiState.BiometricFailed(BiometricProblem.NeedsSetup)
                is BiometricUnlockResult.VaultError -> UnlockUiState.Failed(result.error)
            }
        }
    }

    fun onKeyFileChosen(uri: String) {
        viewModelScope.launch {
            mutableKeyFile.value = when (val result = repository.selectKeyFile(uri)) {
                is KeyFileResult.Selected -> KeyFileUi(result.displayName)
                is KeyFileResult.Failure -> mutableKeyFile.value.copy(error = result.error)
            }
            if (keyFileProblem()) mutableState.value = UnlockUiState.Idle
        }
    }

    fun removeKeyFile() {
        repository.removeKeyFile()
        mutableKeyFile.value = KeyFileUi(null)
        if (keyFileProblem()) mutableState.value = UnlockUiState.Idle
    }

    private fun keyFileProblem(): Boolean = (state.value as? UnlockUiState.Failed)?.error in KEY_FILE_ERRORS

    fun onNavigated() {
        mutableState.value = UnlockUiState.Idle
    }

    override fun onCleared() {
        password.clearText()
    }
}

private val KEY_FILE_ERRORS = setOf(UnlockError.KeyFileUnavailable, UnlockError.KeyFileTooLarge, UnlockError.InvalidKeyFile)

internal fun CharSequence.encodeUtf8(): ByteArray {
    val buffer = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(this))
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    if (buffer.hasArray()) buffer.array().fill(0)
    return bytes
}
