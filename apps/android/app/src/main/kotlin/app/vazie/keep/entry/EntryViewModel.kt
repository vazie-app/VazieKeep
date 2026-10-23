package app.vazie.keep.entry

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vazie.keep.core.EntryDetail
import app.vazie.keep.core.EntryId
import app.vazie.keep.core.GroupId
import app.vazie.keep.core.KeepAccess
import app.vazie.keep.core.SecretField
import app.vazie.keep.core.TotpException
import app.vazie.keep.core.VaultSessionException
import app.vazie.keep.data.EditOutcome
import app.vazie.keep.data.SecretClipboard
import app.vazie.keep.data.VaultLockedException
import app.vazie.keep.data.VaultRepository
import app.vazie.keep.data.VaultState
import app.vazie.keep.save.SaveFlow
import app.vazie.keep.vault.GroupChoice
import app.vazie.keep.vault.groupChoices
import app.vazie.keep.vault.isInRecycleBin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

val REVEAL_DURATION: Duration = 15.seconds
private const val TOTP_TICK = 1000L

const val ENTRY_ID_ARG = "entryId"

sealed interface SecretKey {
    data object Password : SecretKey

    data object Notes : SecretKey

    data class Custom(val name: String) : SecretKey {
        override fun toString() = "Custom(<name>)"
    }
}

class RevealedSecret(val key: SecretKey, val text: String, val announce: Boolean = false) {
    override fun toString() = "RevealedSecret($key, <redacted>)"
}

sealed interface TotpState {
    data object None : TotpState

    data object Locked : TotpState

    data object Waiting : TotpState

    class Active(val code: String, val secondsRemaining: Int, val period: Int) : TotpState {
        override fun toString() = "Active(period=$period, secondsRemaining=$secondsRemaining)"
    }
}

sealed interface EntryUiState {
    data object Loading : EntryUiState

    data object Locked : EntryUiState

    data object NotFound : EntryUiState

    data class Loaded(
        val detail: EntryDetail,
        val expired: Boolean = false,
        val revealed: RevealedSecret? = null,
        val confirmReadAloud: SecretKey? = null,
        val deletesToBin: Boolean = false,
        val moveTargets: List<GroupChoice> = emptyList(),
        val totp: TotpState = TotpState.None,
        val historyUnlocked: Boolean = false,
    ) : EntryUiState
}

sealed interface EntryEvent {
    data class Copied(val clearsAfterSeconds: Int?) : EntryEvent

    data object Deleted : EntryEvent

    data object Moved : EntryEvent

    data class AttachmentExported(val written: Boolean) : EntryEvent
}

class EntryViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: VaultRepository,
    private val clipboard: SecretClipboard,
    private val background: CoroutineDispatcher = Dispatchers.Default,
    private val revealDuration: Duration = REVEAL_DURATION,
    private val access: () -> KeepAccess = { KeepAccess.Free },
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : ViewModel() {
    private val entryId = EntryId(checkNotNull(savedStateHandle.get<String>(ENTRY_ID_ARG)))
    private val mutableState = MutableStateFlow<EntryUiState>(EntryUiState.Loading)
    val state: StateFlow<EntryUiState> = mutableState.asStateFlow()
    private val eventChannel = Channel<EntryEvent>(Channel.BUFFERED)
    val events: Flow<EntryEvent> = eventChannel.receiveAsFlow()
    private var autoHide: Job? = null
    private var totpJob: Job? = null
    private var totpVisible = true

    val saveFlow = SaveFlow(repository, viewModelScope)

    private val mutableCanEdit = MutableStateFlow(false)
    val canEdit: StateFlow<Boolean> = mutableCanEdit.asStateFlow()

    init {
        viewModelScope.launch {
            repository.state.collect { vault ->
                if (vault is VaultState.Locked) {
                    hide()
                    stopTotp()
                    mutableState.value = EntryUiState.Locked
                } else if (vault is VaultState.Unlocked) {
                    mutableCanEdit.value = vault.canEdit
                }
            }
        }
        viewModelScope.launch {
            repository.contentRevision.drop(1).collect {
                if (mutableState.value is EntryUiState.Loaded) {
                    hide()
                    load()
                }
            }
        }
        viewModelScope.launch { repository.hideSecretsRequests.collect { hide() } }
        viewModelScope.launch { load() }
    }

    private var readAloudConfirmed = false

    fun reveal(key: SecretKey) = reveal(key, announce = false)

    fun requestReadAloud(key: SecretKey) {
        if (readAloudConfirmed) {
            reveal(key, announce = true)
        } else {
            mutableState.update { current -> if (current is EntryUiState.Loaded) current.copy(confirmReadAloud = key) else current }
        }
    }

    fun confirmReadAloud(confirmed: Boolean) {
        val key = (state.value as? EntryUiState.Loaded)?.confirmReadAloud ?: return
        mutableState.update { current -> if (current is EntryUiState.Loaded) current.copy(confirmReadAloud = null) else current }
        if (confirmed) {
            readAloudConfirmed = true
            reveal(key, announce = true)
        }
    }

    private fun reveal(key: SecretKey, announce: Boolean) {
        viewModelScope.launch {
            val text = readSecret(key) { secret -> secret.useChars { String(it) } } ?: return@launch
            mutableState.update { current ->
                if (current is EntryUiState.Loaded) current.copy(revealed = RevealedSecret(key, text, announce)) else current
            }
            autoHide?.cancel()
            autoHide = viewModelScope.launch {
                delay(revealDuration)
                hide()
            }
        }
    }

    fun setTotpVisible(visible: Boolean) {
        if (totpVisible == visible) return
        totpVisible = visible
        val loaded = state.value as? EntryUiState.Loaded ?: return
        if (loaded.detail.summary.hasTotp) startTotp(loaded.detail.summary.hasTotp)
    }

    fun copyTotp() {
        viewModelScope.launch {
            val result = try {
                withContext(background) { repository.totpCode(entryId, nowSeconds(), access()).use { clipboard.copy(it.code) } }
            } catch (_: VaultLockedException) {
                mutableState.value = EntryUiState.Locked
                return@launch
            } catch (_: TotpException) {
                return@launch
            }
            val seconds = result.clearsAfter?.inWholeSeconds?.toInt()
            if (!result.systemConfirms || seconds == null) eventChannel.trySend(EntryEvent.Copied(seconds))
        }
    }

    private fun startTotp(hasTotp: Boolean) {
        totpJob?.cancel()
        totpJob = null
        if (!hasTotp) {
            setTotp(TotpState.None)
            return
        }
        if (!totpVisible) {
            setTotp(TotpState.Waiting)
            return
        }
        totpJob = viewModelScope.launch {
            while (isActive) {
                val next = readTotp() ?: break
                setTotp(next)
                delay(TOTP_TICK)
            }
        }
    }

    private fun stopTotp() {
        totpJob?.cancel()
        totpJob = null
    }

    private fun setTotp(totp: TotpState) {
        mutableState.update { current -> if (current is EntryUiState.Loaded) current.copy(totp = totp) else current }
    }

    private suspend fun readTotp(): TotpState? = try {
        withContext(background) {
            repository.totpCode(entryId, nowSeconds(), access()).use { code ->
                TotpState.Active(code.code.useChars { String(it) }, code.secondsRemaining, code.period)
            }
        }
    } catch (_: VaultLockedException) {
        mutableState.value = EntryUiState.Locked
        null
    } catch (error: TotpException) {
        setTotp(if (error.reason == TotpException.Reason.NotEntitled) TotpState.Locked else TotpState.None)
        null
    }

    fun hide() {
        autoHide?.cancel()
        autoHide = null
        mutableState.update { current ->
            if (current is EntryUiState.Loaded && (current.revealed != null || current.confirmReadAloud != null)) {
                current.copy(revealed = null, confirmReadAloud = null)
            } else {
                current
            }
        }
    }

    fun copy(key: SecretKey) {
        viewModelScope.launch {
            val result = readSecret(key) { clipboard.copy(it) } ?: return@launch
            val seconds = result.clearsAfter?.inWholeSeconds?.toInt()
            if (!result.systemConfirms || seconds == null) eventChannel.trySend(EntryEvent.Copied(seconds))
        }
    }

    fun delete(permanent: Boolean) {
        viewModelScope.launch {
            when (val outcome = runEdit { it.deleteEntry(entryId, permanent) }) {
                is EditOutcome.Saved -> eventChannel.send(EntryEvent.Deleted)
                is EditOutcome.NotSaved -> saveFlow.report(outcome.failure)
                else -> Unit
            }
        }
    }

    fun exportAttachment(name: String, uri: String?) {
        if (uri == null) return
        viewModelScope.launch {
            val outcome = repository.exportAttachment(entryId, name, uri)
            eventChannel.send(EntryEvent.AttachmentExported(outcome == app.vazie.keep.data.CopyOutcome.Written))
        }
    }

    fun setIcon(icon: Int) {
        viewModelScope.launch {
            when (val outcome = runEdit { it.setEntryIcon(entryId, icon) }) {
                is EditOutcome.NotSaved -> saveFlow.report(outcome.failure)
                else -> Unit
            }
        }
    }

    // The entry stays; the version with the passkey goes to its history.
    fun removePasskey() {
        viewModelScope.launch {
            when (val outcome = runEdit { it.removePasskey(entryId) }) {
                is EditOutcome.NotSaved -> saveFlow.report(outcome.failure)
                else -> Unit
            }
        }
    }

    fun toggleFavorite() {
        val loaded = state.value as? EntryUiState.Loaded ?: return
        val favorite = !loaded.detail.summary.favorite
        viewModelScope.launch {
            when (val outcome = runEdit { it.setFavorite(entryId, favorite) }) {
                is EditOutcome.NotSaved -> saveFlow.report(outcome.failure)
                else -> Unit
            }
        }
    }

    fun move(group: GroupId) {
        viewModelScope.launch {
            when (val outcome = runEdit { it.moveEntry(entryId, group) }) {
                is EditOutcome.Saved -> eventChannel.send(EntryEvent.Moved)
                is EditOutcome.NotSaved -> saveFlow.report(outcome.failure)
                else -> Unit
            }
        }
    }

    private suspend fun <T> runEdit(block: (app.vazie.keep.core.KeepVaultSession) -> T): EditOutcome<T>? = try {
        repository.edit(block)
    } catch (_: VaultLockedException) {
        mutableState.value = EntryUiState.Locked
        null
    }

    override fun onCleared() {
        stopTotp()
        hide()
    }

    private suspend fun load() {
        mutableState.value = try {
            withContext(background) {
                val detail = repository.entry(entryId)
                val info = (repository.state.value as? VaultState.Unlocked)?.info
                val groups = repository.groups()
                EntryUiState.Loaded(
                    expired = detail.summary.isExpired(nowSeconds()),
                    totp = if (detail.summary.hasTotp) TotpState.Waiting else TotpState.None,
                    detail = detail,
                    historyUnlocked = access().plusFeatures,
                    deletesToBin = info?.recycleBinId != null && !isInRecycleBin(groups, info.recycleBinId, detail.summary.groupId),
                    moveTargets = info?.let { groupChoices(groups, it).filter { choice -> choice.id != detail.summary.groupId } }.orEmpty(),
                )
            }
        } catch (_: VaultLockedException) {
            EntryUiState.Locked
        } catch (_: VaultSessionException) {
            EntryUiState.NotFound
        }
        (mutableState.value as? EntryUiState.Loaded)?.let { startTotp(it.detail.summary.hasTotp) } ?: stopTotp()
    }

    private suspend fun <T> readSecret(key: SecretKey, use: (app.vazie.keep.core.SecretValue) -> T): T? = try {
        withContext(background) {
            repository.reveal(entryId, key.field()).use(use)
        }
    } catch (_: VaultLockedException) {
        mutableState.value = EntryUiState.Locked
        null
    } catch (_: VaultSessionException) {
        null
    }
}

private fun SecretKey.field(): SecretField = when (this) {
    SecretKey.Password -> SecretField.Password
    SecretKey.Notes -> SecretField.Notes
    is SecretKey.Custom -> SecretField.Custom(name)
}
