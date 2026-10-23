package app.vazie.keep.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vazie.keep.core.EntryDetail
import app.vazie.keep.core.EntryId
import app.vazie.keep.core.HistoryVersion
import app.vazie.keep.core.KeepAccess
import app.vazie.keep.core.SecretField
import app.vazie.keep.core.VaultEditException
import app.vazie.keep.core.VaultHistoryException
import app.vazie.keep.core.VaultSessionException
import app.vazie.keep.data.EditOutcome
import app.vazie.keep.data.SecretClipboard
import app.vazie.keep.data.VaultLockedException
import app.vazie.keep.data.VaultRepository
import app.vazie.keep.data.VaultState
import app.vazie.keep.entry.ENTRY_ID_ARG
import app.vazie.keep.entry.REVEAL_DURATION
import app.vazie.keep.entry.RevealedSecret
import app.vazie.keep.entry.SecretKey
import app.vazie.keep.save.SaveFlow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration

class OpenedVersion(val version: HistoryVersion, val detail: EntryDetail, val revealed: RevealedSecret? = null) {
    fun with(revealed: RevealedSecret?) = OpenedVersion(version, detail, revealed)

    override fun toString() = "OpenedVersion(index=${version.index})"
}

sealed interface HistoryUiState {
    data object Loading : HistoryUiState

    data object Locked : HistoryUiState

    data object NotFound : HistoryUiState

    data object NeedsPlus : HistoryUiState

    data class Loaded(
        val versions: List<HistoryVersion>,
        val canRestore: Boolean,
        val opened: OpenedVersion? = null,
    ) : HistoryUiState
}

sealed interface HistoryEvent {
    data class Copied(val clearsAfterSeconds: Int?) : HistoryEvent

    data object Restored : HistoryEvent
}

class HistoryViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: VaultRepository,
    private val clipboard: SecretClipboard,
    private val background: CoroutineDispatcher = Dispatchers.Default,
    private val revealDuration: Duration = REVEAL_DURATION,
    private val access: () -> KeepAccess = { KeepAccess.Free },
) : ViewModel() {
    private val entryId = EntryId(checkNotNull(savedStateHandle.get<String>(ENTRY_ID_ARG)))
    private val mutableState = MutableStateFlow<HistoryUiState>(HistoryUiState.Loading)
    val state: StateFlow<HistoryUiState> = mutableState.asStateFlow()
    private val eventChannel = Channel<HistoryEvent>(Channel.BUFFERED)
    val events: Flow<HistoryEvent> = eventChannel.receiveAsFlow()
    private var autoHide: Job? = null

    val saveFlow = SaveFlow(repository, viewModelScope)

    init {
        viewModelScope.launch {
            repository.state.collect { vault -> if (vault is VaultState.Locked) mutableState.value = HistoryUiState.Locked }
        }
        viewModelScope.launch { repository.hideSecretsRequests.collect { hide() } }
        viewModelScope.launch { load() }
    }

    fun open(index: Int) {
        viewModelScope.launch {
            val loaded = state.value as? HistoryUiState.Loaded ?: return@launch
            val version = loaded.versions.firstOrNull { it.index == index } ?: return@launch
            val detail = read { repository.historyEntry(entryId, index, access()) } ?: return@launch
            hide()
            mutableState.update { current -> if (current is HistoryUiState.Loaded) current.copy(opened = OpenedVersion(version, detail)) else current }
        }
    }

    // False when there was no open version to close, so the screen itself should go back.
    fun closeVersion(): Boolean {
        val loaded = state.value as? HistoryUiState.Loaded ?: return false
        if (loaded.opened == null) return false
        autoHide?.cancel()
        mutableState.value = loaded.copy(opened = null)
        return true
    }

    fun reveal(key: SecretKey) {
        viewModelScope.launch {
            val text = readSecret(key) { secret -> secret.useChars { String(it) } } ?: return@launch
            setRevealed(RevealedSecret(key, text))
            autoHide?.cancel()
            autoHide = viewModelScope.launch {
                delay(revealDuration)
                hide()
            }
        }
    }

    fun hide() {
        autoHide?.cancel()
        autoHide = null
        setRevealed(null)
    }

    fun copy(key: SecretKey) {
        viewModelScope.launch {
            val result = readSecret(key) { clipboard.copy(it) } ?: return@launch
            val seconds = result.clearsAfter?.inWholeSeconds?.toInt()
            if (!result.systemConfirms || seconds == null) eventChannel.trySend(HistoryEvent.Copied(seconds))
        }
    }

    fun restore() {
        val index = (state.value as? HistoryUiState.Loaded)?.opened?.version?.index ?: return
        viewModelScope.launch {
            val outcome = try {
                repository.edit { it.restoreHistory(entryId, index, access()) }
            } catch (_: VaultLockedException) {
                mutableState.value = HistoryUiState.Locked
                return@launch
            }
            when (outcome) {
                is EditOutcome.Saved -> eventChannel.send(HistoryEvent.Restored)
                is EditOutcome.NotSaved -> {
                    saveFlow.report(outcome.failure)
                    load()
                }
                is EditOutcome.Invalid ->
                    if (outcome.reason == VaultEditException.Reason.NotEntitled) mutableState.value = HistoryUiState.NeedsPlus else load()
            }
        }
    }

    override fun onCleared() = hide()

    private fun setRevealed(revealed: RevealedSecret?) {
        mutableState.update { current ->
            val opened = (current as? HistoryUiState.Loaded)?.opened
            if (opened != null && opened.revealed !== revealed) current.copy(opened = opened.with(revealed)) else current
        }
    }

    private suspend fun load() {
        mutableState.value = read {
            HistoryUiState.Loaded(
                versions = repository.history(entryId, access()),
                canRestore = (repository.state.value as? VaultState.Unlocked)?.canEdit == true,
            )
        } ?: return
    }

    // Null means the state already says why: locked, no Keep+, or the entry or version is gone.
    private suspend fun <T> read(block: () -> T): T? = try {
        withContext(background) { block() }
    } catch (_: VaultLockedException) {
        mutableState.value = HistoryUiState.Locked
        null
    } catch (error: VaultHistoryException) {
        mutableState.value = when (error.reason) {
            VaultHistoryException.Reason.NotEntitled -> HistoryUiState.NeedsPlus
            else -> HistoryUiState.NotFound
        }
        null
    } catch (_: VaultSessionException) {
        mutableState.value = HistoryUiState.NotFound
        null
    }

    private suspend fun <T> readSecret(key: SecretKey, use: (app.vazie.keep.core.SecretValue) -> T): T? {
        val index = (state.value as? HistoryUiState.Loaded)?.opened?.version?.index ?: return null
        return read { repository.revealHistory(entryId, index, key.field(), access()).use(use) }
    }
}

private fun SecretKey.field(): SecretField = when (this) {
    SecretKey.Password -> SecretField.Password
    SecretKey.Notes -> SecretField.Notes
    is SecretKey.Custom -> SecretField.Custom(name)
}
