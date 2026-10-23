package app.vazie.keep.autofill

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vazie.keep.biometric.BiometricMaterialSource
import app.vazie.keep.biometric.PromptText
import app.vazie.keep.data.OpenedVault
import app.vazie.keep.data.VaultOpener
import app.vazie.keep.data.VaultReferenceStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.CharBuffer
import kotlin.time.Duration.Companion.milliseconds

sealed interface SaveUiState {
    data object Loading : SaveUiState

    // The preview. `shown` is the password in clear, only after an explicit tap.
    data class Confirm(
        val subject: String,
        val username: String,
        val plan: SavePlan,
        val selected: SaveChoice?,
        val shown: String? = null,
    ) : SaveUiState

    data class Unlock(
        val subject: String,
        val username: String,
        val biometric: Boolean,
        val busy: Boolean = false,
        val message: UnlockMessage? = null,
        val keyFileName: String? = null,
    ) : SaveUiState

    // `slow` turns true after half a second: only then is a progress bar shown.
    data class Saving(val slow: Boolean = false) : SaveUiState

    data class Done(val result: SaveResult) : SaveUiState

    data class Failed(val problem: AuthProblem) : SaveUiState

    data object Cancelled : SaveUiState
}

class AutofillSaveViewModel(
    token: String,
    private val handoff: SaveHandoff,
    private val references: VaultReferenceStore,
    private val index: AutofillIndexStore,
    private val associations: AppAssociationStore,
    opener: VaultOpener,
    biometrics: BiometricMaterialSource,
    private val matcher: CredentialMatcher,
    private val executor: SaveExecutor,
    private val appLabel: (String) -> String?,
    private val onSaved: () -> Unit = {},
    private val timing: (label: String, millis: Long) -> Unit = { _, _ -> },
    private val background: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    // Held here, not in saved state: neither the master password nor the submitted one reaches a bundle.
    val password = TextFieldState()

    private val mutableState = MutableStateFlow<SaveUiState>(SaveUiState.Loading)
    val state: StateFlow<SaveUiState> = mutableState.asStateFlow()

    private val focusChannel = Channel<Unit>(Channel.CONFLATED)
    val focusRequests: Flow<Unit> = focusChannel.receiveAsFlow()

    private val unlocker = VaultUnlocker(references, opener, biometrics, timing)
    private var credential: SubmittedCredential? = handoff.take(token)
    private var chosen: SaveChoice = SaveChoice.Create
    private var subject = ""
    private var prompt: PromptText? = null
    private var working = false
    private var biometricStarted = false

    init {
        load()
    }

    fun start(promptText: PromptText) {
        prompt = promptText
        (state.value as? SaveUiState.Unlock)?.let(::maybeStartBiometric)
    }

    fun select(choice: SaveChoice) {
        (state.value as? SaveUiState.Confirm)?.let { mutableState.value = it.copy(selected = choice) }
    }

    // An explicit action: the password is shown, and hidden again by `hide`.
    fun toggleShown() {
        val current = state.value as? SaveUiState.Confirm ?: return
        val shown = if (current.shown != null) null else credential?.password?.useChars { String(it) }
        mutableState.value = current.copy(shown = shown)
    }

    fun hide() {
        (state.value as? SaveUiState.Confirm)?.takeIf { it.shown != null }?.let { mutableState.value = it.copy(shown = null) }
    }

    // The fingerprint is requested only after this: saving was confirmed on screen.
    fun confirm() {
        val current = state.value as? SaveUiState.Confirm ?: return
        chosen = current.selected ?: return
        viewModelScope.launch {
            val readiness = unlocker.readiness()
            if (readiness == null) {
                mutableState.value = SaveUiState.Failed(AuthProblem.NoVault)
                return@launch
            }
            val unlock = SaveUiState.Unlock(subject, current.username, readiness.biometric, keyFileName = readiness.keyFileName)
            mutableState.value = unlock
            maybeStartBiometric(unlock)
        }
    }

    fun unlockWithPassword() {
        val current = state.value as? SaveUiState.Unlock ?: return
        if (working) return
        val bytes = password.text.takeIf { it.isNotEmpty() }?.let(::encodeUtf8)
        password.clearText()
        if (bytes == null && references.load()?.keyFileUri == null) return
        working = true
        mutableState.value = current.copy(busy = true, message = null)
        viewModelScope.launch { proceed(unlocker.withPassword(bytes, current.biometric), current) }
    }

    fun useBiometric() {
        val current = state.value as? SaveUiState.Unlock ?: return
        if (working || !current.biometric) return
        runBiometric(current)
    }

    fun cancel() {
        password.clearText()
        wipe()
        mutableState.value = SaveUiState.Cancelled
    }

    override fun onCleared() {
        password.clearText()
        wipe()
    }

    private fun wipe() {
        credential?.wipe()
        credential = null
    }

    private fun load() {
        val submitted = credential
        if (submitted == null) {
            mutableState.value = SaveUiState.Failed(AuthProblem.Expired)
            return
        }
        viewModelScope.launch {
            // The origin was decided by the service from the system's own report (a pinned browser's page, or
            // the requesting package); the system starts this screen without a calling package, so there is
            // nothing more to check here. The token only comes from the service.
            val snapshot = withContext(background) { index.load() }
            val origin = submitted.origin
            subject = when (origin) {
                is RequestOrigin.Web -> SavePlanner.titleFor(origin, null)
                is RequestOrigin.App -> SavePlanner.titleFor(origin, appLabel(origin.packageName))
            }
            val plan = SavePlanner.plan(snapshot, origin, submitted.username, matcher, associations.all())
            val default = when (plan) {
                is SavePlan.Create -> SaveChoice.Create
                is SavePlan.Update -> SaveChoice.Update(plan.entry)
                is SavePlan.Choose -> null
            }
            mutableState.value = SaveUiState.Confirm(subject, submitted.username, plan, default)
        }
    }

    private fun maybeStartBiometric(unlock: SaveUiState.Unlock) {
        if (!unlock.biometric || biometricStarted || prompt == null) return
        biometricStarted = true
        runBiometric(unlock)
    }

    private fun runBiometric(current: SaveUiState.Unlock) {
        val text = prompt ?: return
        working = true
        mutableState.value = current.copy(busy = true, message = null)
        viewModelScope.launch { proceed(unlocker.withBiometric(text), current) }
    }

    private suspend fun proceed(step: VaultUnlocker.Step, current: SaveUiState.Unlock) {
        when (step) {
            is VaultUnlocker.Step.Opened -> withContext(NonCancellable) { save(step.vault) }
            is VaultUnlocker.Step.Retry -> {
                working = false
                mutableState.value = current.copy(busy = false, message = step.message, biometric = step.biometric)
                if (step.refocus) focusChannel.trySend(Unit)
            }
            is VaultUnlocker.Step.Failed -> {
                working = false
                wipe()
                mutableState.value = SaveUiState.Failed(step.problem)
            }
        }
    }

    // One confirmed change, written by the app's own safe save, then the session closes.
    private suspend fun save(vault: OpenedVault) {
        val submitted = credential
        val reference = references.load()
        if (submitted == null || reference == null) {
            vault.session.close()
            working = false
            mutableState.value = SaveUiState.Failed(AuthProblem.Expired)
            return
        }
        val started = System.nanoTime()
        mutableState.value = SaveUiState.Saving()
        val slow = viewModelScope.launch {
            delay(500.milliseconds)
            mutableState.value = SaveUiState.Saving(slow = true)
        }
        val known = withContext(background) { index.load() }
        val result = try {
            if (known != null && known.fingerprint != vault.info.fingerprint) {
                SaveResult.Failed(AuthProblem.VaultChanged)
            } else {
                val bytes = submitted.password.useBytes { it.copyOf() }
                executor.execute(
                    vault = vault,
                    reference = reference,
                    choice = chosen,
                    username = submitted.username.trim(),
                    password = bytes,
                    title = subject,
                    url = SavePlanner.urlFor(submitted.origin),
                )
            }
        } finally {
            vault.session.close()
            slow.cancel()
        }
        timing("autofill save total", (System.nanoTime() - started) / 1_000_000)
        working = false
        val savedId = when (result) {
            is SaveResult.Created -> result.entryId
            is SaveResult.Updated -> result.entryId
            else -> null
        }
        if (savedId != null) {
            rememberApp(submitted.origin, vault.info.fingerprint, savedId)
            onSaved()
        }
        wipe()
        mutableState.value = when (result) {
            is SaveResult.Failed -> SaveUiState.Failed(result.problem)
            else -> SaveUiState.Done(result)
        }
    }

    // A credential saved from a native app belongs to that app. Android doesn't let the service read another
    // app's certificate, so the approval is recorded for the package alone ("pending", empty certificate) and
    // bound to the certificate at the first fill, where the calling package is visible (ADR 0017). A browser's
    // package is never recorded: the web origin is the identity.
    private fun rememberApp(origin: RequestOrigin, fingerprint: String, entryId: String) {
        val app = origin as? RequestOrigin.App ?: return
        if (associations.all().any { it.packageName == app.packageName && it.entryId == entryId && it.fingerprint == fingerprint }) return
        associations.add(AppAssociation(app.packageName, "", fingerprint, entryId))
    }

    private fun encodeUtf8(text: CharSequence): ByteArray {
        val buffer = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text))
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        if (buffer.hasArray()) buffer.array().fill(0)
        return bytes
    }
}
