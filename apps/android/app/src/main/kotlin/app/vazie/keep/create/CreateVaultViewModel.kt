package app.vazie.keep.create

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vazie.keep.biometric.BiometricUnlock
import app.vazie.keep.biometric.EnableResult
import app.vazie.keep.biometric.PromptText
import app.vazie.keep.core.KdfSettings
import app.vazie.keep.core.KeepCore
import app.vazie.keep.core.PasswordOptions
import app.vazie.keep.data.CreateError
import app.vazie.keep.data.CreateRequest
import app.vazie.keep.data.CreateResult
import app.vazie.keep.data.UnlockResult
import app.vazie.keep.data.VaultManager
import app.vazie.keep.data.VaultRepository
import app.vazie.keep.data.VaultState
import app.vazie.keep.data.suggestedFileName
import app.vazie.keep.unlock.encodeUtf8
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SecurityPreset { Balanced, Stronger }

enum class CreateProblem { NameMissing, PasswordMissing, PasswordMismatch, KeyFileUnreadable, KeyFileNotSaved, Write, Other }

sealed interface CreatePhase {
    data object Editing : CreatePhase

    data object Creating : CreatePhase

    // The vault exists and is open. The person may turn on biometrics before going in.
    data class Ready(val canOfferBiometrics: Boolean, val biometricsFailed: Boolean = false) : CreatePhase
}

data class CreateUiState(
    val phase: CreatePhase = CreatePhase.Editing,
    val preset: SecurityPreset = SecurityPreset.Balanced,
    val benchmarkMs: Map<SecurityPreset, Int> = emptyMap(),
    val keyFileName: String? = null,
    val keyFileGenerated: Boolean = false,
    val passwordVisible: Boolean = false,
    val passwordGenerated: Boolean = false,
    val problem: CreateProblem? = null,
    val unlockFailed: Boolean = false,
)

sealed interface CreateEvent {
    data class PickDestination(val fileName: String) : CreateEvent

    data class SaveKeyFile(val fileName: String) : CreateEvent
}

class CreateVaultViewModel(
    private val core: KeepCore,
    private val manager: VaultManager,
    private val repository: VaultRepository,
    private val biometrics: BiometricUnlock,
) : ViewModel() {
    // Held here rather than in rememberSaveable so secrets never reach the saved-state Bundle.
    val name = TextFieldState()
    val password = TextFieldState()
    val confirmation = TextFieldState()

    private val mutableState = MutableStateFlow(CreateUiState())
    val state: StateFlow<CreateUiState> = mutableState.asStateFlow()

    private val events = Channel<CreateEvent>(Channel.BUFFERED)
    val eventFlow: Flow<CreateEvent> = events.receiveAsFlow()

    private var keyFile: ByteArray? = null
    private var keyFileUri: String? = null
    private var keyFileDisplayName: String? = null
    private var generatedKeyFile: ByteArray? = null
    private var passwordForUnlock: ByteArray? = null

    init {
        viewModelScope.launch {
            for (preset in SecurityPreset.entries) {
                val ms = runCatching { core.benchmarkKdf(preset.settings()) }.getOrNull() ?: continue
                mutableState.update { it.copy(benchmarkMs = it.benchmarkMs + (preset to ms)) }
            }
        }
    }

    private fun SecurityPreset.settings(): KdfSettings = if (this == SecurityPreset.Balanced) core.balancedPreset else core.strongerPreset

    fun selectPreset(preset: SecurityPreset) = mutableState.update { it.copy(preset = preset) }

    fun toggleVisible() = mutableState.update { it.copy(passwordVisible = !it.passwordVisible) }

    fun generatePassword() {
        val secret = core.generatePassword(PasswordOptions(length = 24, excludeAmbiguous = true))
        secret.useChars { chars ->
            password.setTextAndPlaceCursorAtEnd(String(chars))
            confirmation.setTextAndPlaceCursorAtEnd(String(chars))
        }
        secret.close()
        mutableState.update { it.copy(passwordGenerated = true, passwordVisible = true, problem = null) }
    }

    fun onPasswordEdited() {
        mutableState.update { if (it.passwordGenerated || it.problem != null) it.copy(passwordGenerated = false, problem = null) else it }
    }

    fun onKeyFilePicked(uri: String?, displayName: String?) {
        if (uri == null) return
        viewModelScope.launch {
            val bytes = manager.readKeyFile(uri)
            if (bytes == null) {
                mutableState.update { it.copy(problem = CreateProblem.KeyFileUnreadable) }
                return@launch
            }
            wipeKeyFile()
            keyFile = bytes
            keyFileUri = uri
            keyFileDisplayName = displayName
            mutableState.update { it.copy(keyFileName = displayName ?: uri.substringAfterLast('/'), keyFileGenerated = false, problem = null) }
        }
    }

    fun generateKeyFile() {
        val secret = core.generateKeyFile()
        generatedKeyFile?.fill(0)
        generatedKeyFile = secret.useBytes { it.copyOf() }
        secret.close()
        val base = name.text.toString().trim().ifEmpty { "Vault" }
        events.trySend(CreateEvent.SaveKeyFile(suggestedFileName(base).removeSuffix(".kdbx") + ".keyx"))
    }

    fun onKeyFileSaved(uri: String?, displayName: String?) {
        val bytes = generatedKeyFile
        generatedKeyFile = null
        if (uri == null || bytes == null) {
            bytes?.fill(0)
            return
        }
        viewModelScope.launch {
            val saved = manager.saveKeyFile(uri, bytes)
            if (!saved) {
                bytes.fill(0)
                mutableState.update { it.copy(problem = CreateProblem.KeyFileNotSaved) }
                return@launch
            }
            wipeKeyFile()
            keyFile = bytes
            keyFileUri = uri
            keyFileDisplayName = displayName
            mutableState.update { it.copy(keyFileName = displayName ?: uri.substringAfterLast('/'), keyFileGenerated = true, problem = null) }
        }
    }

    fun removeKeyFile() {
        wipeKeyFile()
        mutableState.update { it.copy(keyFileName = null, keyFileGenerated = false) }
    }

    private fun wipeKeyFile() {
        keyFile?.fill(0)
        keyFile = null
        keyFileUri = null
        keyFileDisplayName = null
    }

    fun requestCreate() {
        if (state.value.phase != CreatePhase.Editing) return
        val problem = validate()
        if (problem != null) {
            mutableState.update { it.copy(problem = problem) }
            return
        }
        mutableState.update { it.copy(problem = null, unlockFailed = false) }
        events.trySend(CreateEvent.PickDestination(suggestedFileName(name.text.toString())))
    }

    private fun validate(): CreateProblem? = when {
        name.text.isBlank() -> CreateProblem.NameMissing
        password.text.isEmpty() && keyFile == null -> CreateProblem.PasswordMissing
        password.text.isEmpty() && confirmation.text.isNotEmpty() -> CreateProblem.PasswordMismatch
        password.text.toString() != confirmation.text.toString() -> CreateProblem.PasswordMismatch
        else -> null
    }

    fun onDestinationPicked(uri: String?) {
        if (uri == null || state.value.phase != CreatePhase.Editing) return
        val typed = password.text.takeIf { it.isNotEmpty() }
        val request = CreateRequest(
            name = name.text.toString().trim(),
            kdf = state.value.preset.settings(),
            password = typed?.encodeUtf8(),
            keyFile = keyFile?.copyOf(),
            keyFileUri = keyFileUri,
            keyFileName = keyFileDisplayName,
        )
        passwordForUnlock = typed?.encodeUtf8()
        mutableState.update { it.copy(phase = CreatePhase.Creating, problem = null) }
        viewModelScope.launch {
            when (val result = manager.create(request, uri)) {
                is CreateResult.Failure -> {
                    passwordForUnlock?.fill(0)
                    passwordForUnlock = null
                    mutableState.update { it.copy(phase = CreatePhase.Editing, problem = result.error.toProblem()) }
                }
                is CreateResult.Created -> openCreated()
            }
        }
    }

    private suspend fun openCreated() {
        biometrics.refresh()
        val bytes = passwordForUnlock
        passwordForUnlock = null
        val result = repository.unlock(bytes)
        clearSecrets()
        if (result != UnlockResult.Success) {
            mutableState.update { it.copy(phase = CreatePhase.Editing, unlockFailed = true) }
            return
        }
        val unlocked = repository.state.value as? VaultState.Unlocked
        val offer = unlocked?.materialRetained == true
        mutableState.update { it.copy(phase = CreatePhase.Ready(offer)) }
    }

    fun enableBiometrics(prompt: PromptText) {
        viewModelScope.launch {
            val result = biometrics.enable(prompt)
            mutableState.update {
                it.copy(phase = CreatePhase.Ready(canOfferBiometrics = result !is EnableResult.Enabled, biometricsFailed = result is EnableResult.Failure))
            }
            if (result is EnableResult.Enabled) mutableState.update { it.copy(phase = CreatePhase.Ready(canOfferBiometrics = false)) }
        }
    }

    private fun clearSecrets() {
        password.clearText()
        confirmation.clearText()
        wipeKeyFile()
    }

    private fun CreateError.toProblem() = when (this) {
        CreateError.InvalidName -> CreateProblem.NameMissing
        CreateError.NoCredentials -> CreateProblem.PasswordMissing
        CreateError.InvalidKeyFile -> CreateProblem.KeyFileUnreadable
        CreateError.CouldNotWrite, CreateError.VerificationFailed -> CreateProblem.Write
        CreateError.WeakSettings, CreateError.Failed, CreateError.NeedsPlus -> CreateProblem.Other
    }

    override fun onCleared() {
        clearSecrets()
        generatedKeyFile?.fill(0)
        passwordForUnlock?.fill(0)
    }
}
