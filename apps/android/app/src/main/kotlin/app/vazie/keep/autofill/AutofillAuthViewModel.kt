package app.vazie.keep.autofill

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vazie.keep.biometric.BiometricMaterialSource
import app.vazie.keep.biometric.BiometricRecord
import app.vazie.keep.biometric.BiometricStatus
import app.vazie.keep.biometric.BiometricUnlockResult
import app.vazie.keep.biometric.MaterialResult
import app.vazie.keep.biometric.PromptText
import app.vazie.keep.core.EntryId
import app.vazie.keep.core.EntryText
import app.vazie.keep.core.SecretField
import app.vazie.keep.core.SecretValue
import app.vazie.keep.core.VaultSessionException
import app.vazie.keep.data.OpenOutcome
import app.vazie.keep.data.OpenedVault
import app.vazie.keep.data.UnlockError
import app.vazie.keep.data.VaultOpener
import app.vazie.keep.data.VaultReferenceStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.CharBuffer

// The one secret this flow ever holds: the chosen entry's password (and its username when that is
// protected). The activity builds the Dataset from it and closes it at once.
class FillPayload(val username: String?, val password: SecretValue?) {
    fun close() {
        password?.close()
    }

    override fun toString() = "FillPayload(<redacted>)"
}

enum class AuthProblem {
    // The index and the vault on disk are different databases.
    VaultChanged,
    EntryGone,
    // The entry no longer fits the page or app that asked.
    NoLongerMatches,
    KeyFileUnavailable,
    // A save was cut off: only the app may restore it.
    RestoreNeeded,
    FileUnavailable,
    CouldNotOpen,
    NoVault,
    // Android didn't confirm which app or browser started this screen, so nothing is revealed.
    CallerNotVerified,

    // Save only.
    VaultChangedElsewhere,
    NotWritable,
    CouldNotSave,
    // The submitted password is no longer held (the app was restarted).
    Expired,
}

enum class UnlockMessage { WrongPassword, BiometricFailed, BiometricLockedOut, BiometricNeedsSetup }

sealed interface AuthUiState {
    data object Loading : AuthUiState

    data class Choosing(val entries: List<IndexedEntry>, val indexAvailable: Boolean) : AuthUiState

    // A manual pick that doesn't match the page: ask before going on.
    data class Confirm(val entry: IndexedEntry) : AuthUiState

    data class Unlock(
        val entry: IndexedEntry,
        val biometric: Boolean,
        val busy: Boolean = false,
        val message: UnlockMessage? = null,
        val keyFileName: String? = null,
    ) : AuthUiState

    data class Done(val payload: FillPayload) : AuthUiState

    data class Failed(val problem: AuthProblem) : AuthUiState

    data object Cancelled : AuthUiState
}

class AutofillAuthViewModel(
    private val request: AuthRequest,
    private val references: VaultReferenceStore,
    private val index: AutofillIndexStore,
    private val associations: AppAssociationStore,
    opener: VaultOpener,
    biometrics: BiometricMaterialSource,
    private val matcher: CredentialMatcher,
    private val caller: CallerVerifier,
    private val reindex: (OpenedVault) -> Unit = {},
    private val timing: (label: String, millis: Long) -> Unit = { _, _ -> },
    private val background: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    // Held here, not in saved state: the password never reaches an instance-state bundle.
    val password = TextFieldState()

    private val mutableState = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val state: StateFlow<AuthUiState> = mutableState.asStateFlow()

    private val focusChannel = Channel<Unit>(Channel.CONFLATED)
    val focusRequests: Flow<Unit> = focusChannel.receiveAsFlow()

    val remembersChoice = MutableStateFlow(true)

    private var loaded: AutofillIndex? = null
    private var chosen: IndexedEntry? = null
    private var manualPick = false
    private var biometricStarted = false
    private var prompt: PromptText? = null
    private var working = false
    private var signing: SigningIdentity? = null
    private val unlocker = VaultUnlocker(references, opener, biometrics, timing)

    init {
        load()
    }

    fun start(promptText: PromptText) {
        prompt = promptText
        (state.value as? AuthUiState.Unlock)?.let(::maybeStartBiometric)
    }

    fun search(query: String) {
        val all = loaded?.entries.orEmpty()
        val needle = query.trim().lowercase()
        val shown = if (needle.isEmpty()) all else all.filter { needle in it.title.lowercase() || needle in it.username.lowercase() || needle in it.url.lowercase() }
        mutableState.value = AuthUiState.Choosing(shown.sortedBy { it.title.lowercase() }, indexAvailable = loaded != null)
    }

    fun choose(entry: IndexedEntry) {
        manualPick = true
        chosen = entry
        if (needsConfirmation(entry)) mutableState.value = AuthUiState.Confirm(entry) else toUnlock(entry)
    }

    fun confirmChoice() {
        (state.value as? AuthUiState.Confirm)?.let { toUnlock(it.entry) }
    }

    fun declineChoice() {
        search("")
    }

    fun unlockWithPassword() {
        val current = state.value as? AuthUiState.Unlock ?: return
        if (working) return
        val bytes = password.text.takeIf { it.isNotEmpty() }?.let(::encodeUtf8)
        password.clearText()
        if (bytes == null && references.load()?.keyFileUri == null) return
        working = true
        mutableState.value = current.copy(busy = true, message = null)
        viewModelScope.launch { proceed(unlocker.withPassword(bytes, current.biometric), current) }
    }

    fun useBiometric() {
        val current = state.value as? AuthUiState.Unlock ?: return
        if (working || !current.biometric) return
        runBiometric(current)
    }

    fun cancel() {
        password.clearText()
        mutableState.value = AuthUiState.Cancelled
    }

    override fun onCleared() {
        password.clearText()
        (mutableState.value as? AuthUiState.Done)?.payload?.close()
    }

    private fun load() {
        viewModelScope.launch {
            val snapshot = withContext(background) { index.load() }
            loaded = snapshot
            when (request.mode) {
                AuthRequest.Mode.Search -> mutableState.value = AuthUiState.Choosing(
                    snapshot?.entries.orEmpty().sortedBy { it.title.lowercase() },
                    indexAvailable = snapshot != null,
                )
                AuthRequest.Mode.Select -> {
                    val entry = snapshot?.entries?.firstOrNull { it.entryId == request.entryId }
                    when {
                        snapshot == null || snapshot.fingerprint != request.fingerprint -> mutableState.value = AuthUiState.Failed(AuthProblem.VaultChanged)
                        entry == null -> mutableState.value = AuthUiState.Failed(AuthProblem.EntryGone)
                        else -> {
                            chosen = entry
                            toUnlock(entry)
                        }
                    }
                }
            }
        }
    }

    private fun toUnlock(entry: IndexedEntry) {
        viewModelScope.launch {
            val readiness = unlocker.readiness()
            if (readiness == null) {
                mutableState.value = AuthUiState.Failed(AuthProblem.NoVault)
                return@launch
            }
            when (val check = caller.verify(request.origin)) {
                is CallerCheck.Verified -> signing = check.signing
                CallerCheck.Unverified -> {
                    mutableState.value = AuthUiState.Failed(AuthProblem.CallerNotVerified)
                    return@launch
                }
            }
            val unlock = AuthUiState.Unlock(entry, biometric = readiness.biometric, keyFileName = readiness.keyFileName)
            mutableState.value = unlock
            maybeStartBiometric(unlock)
        }
    }

    // The tap that chose the suggestion is the explicit action, so the fingerprint prompt may open now.
    private fun maybeStartBiometric(unlock: AuthUiState.Unlock) {
        if (!unlock.biometric || biometricStarted || prompt == null) return
        biometricStarted = true
        runBiometric(unlock)
    }

    private fun runBiometric(current: AuthUiState.Unlock) {
        val text = prompt ?: return
        working = true
        mutableState.value = current.copy(busy = true, message = null)
        viewModelScope.launch { proceed(unlocker.withBiometric(text), current) }
    }

    private suspend fun proceed(step: VaultUnlocker.Step, current: AuthUiState.Unlock) {
        when (step) {
            is VaultUnlocker.Step.Opened -> withContext(NonCancellable) { fill(step.vault, current) }
            is VaultUnlocker.Step.Retry -> {
                working = false
                mutableState.value = current.copy(busy = false, message = step.message, biometric = step.biometric)
                if (step.refocus) focusChannel.trySend(Unit)
            }
            is VaultUnlocker.Step.Failed -> {
                working = false
                mutableState.value = AuthUiState.Failed(step.problem)
            }
        }
    }

    // One secret leaves the vault, and only for the entry that was chosen. The session closes before
    // anything else can use it.
    private suspend fun fill(vault: OpenedVault, current: AuthUiState.Unlock) = withContext(background) {
        val started = System.nanoTime()
        val session = vault.session
        var payload: FillPayload? = null
        var problem: AuthProblem? = null
        try {
            if (request.fingerprint.isNotEmpty() && vault.info.fingerprint != request.fingerprint) {
                problem = AuthProblem.VaultChanged
            } else {
                runCatching { reindex(vault) }
                val id = EntryId(current.entry.entryId)
                val detail = try {
                    session.entry(id)
                } catch (_: VaultSessionException) {
                    null
                }
                if (detail == null) {
                    problem = AuthProblem.EntryGone
                } else if (!stillMatches(detail.summary.url, current.entry.entryId, vault.info.fingerprint)) {
                    problem = AuthProblem.NoLongerMatches
                } else {
                    val username = when (val text = detail.summary.username) {
                        is EntryText.Plain -> text.value
                        EntryText.Hidden -> session.reveal(id, SecretField.UserName).use { it.useChars { chars -> String(chars) } }
                    }
                    val secret = if (detail.summary.hasPassword) session.reveal(id, SecretField.Password) else null
                    payload = FillPayload(username.takeIf { it.isNotEmpty() }, secret)
                }
            }
        } catch (_: VaultSessionException) {
            problem = AuthProblem.CouldNotOpen
        } finally {
            session.close()
        }
        working = false
        timing("autofill reveal", (System.nanoTime() - started) / 1_000_000)
        if (payload == null) {
            mutableState.value = AuthUiState.Failed(problem ?: AuthProblem.CouldNotOpen)
            return@withContext
        }
        rememberAssociation(current.entry, vault.info.fingerprint)
        mutableState.value = AuthUiState.Done(payload)
    }

    private fun stillMatches(url: EntryText, entryId: String, fingerprint: String): Boolean {
        if (manualPick) return true
        return when (val origin = request.origin) {
            is RequestOrigin.Web -> {
                val plain = (url as? EntryText.Plain)?.value ?: return false
                matcher.matchesWeb(IndexedEntry(entryId, "", "", plain), origin.origin)
            }
            is RequestOrigin.App -> matcher.matchesApp(entryId, fingerprint, origin, signing, associations.all())
        }
    }

    private fun needsConfirmation(entry: IndexedEntry): Boolean = when (val origin = request.origin) {
        is RequestOrigin.Web -> !matcher.matchesWeb(entry, origin.origin)
        is RequestOrigin.App -> false
    }

    private fun rememberAssociation(entry: IndexedEntry, fingerprint: String) {
        val origin = request.origin as? RequestOrigin.App ?: return
        val cert = signing?.current ?: return
        // A manual pick, or an approved app whose key rotated: record the certificate Android vouches for now.
        val rotated = !manualPick && associations.all().any { it.packageName == origin.packageName && it.entryId == entry.entryId && it.certDigest != cert }
        if (manualPick && !remembersChoice.value) return
        if (manualPick || rotated) associations.add(AppAssociation(origin.packageName, cert, fingerprint, entry.entryId))
    }

    private fun encodeUtf8(text: CharSequence): ByteArray {
        val buffer = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text))
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        if (buffer.hasArray()) buffer.array().fill(0)
        return bytes
    }
}
