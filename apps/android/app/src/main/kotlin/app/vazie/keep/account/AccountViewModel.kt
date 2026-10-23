package app.vazie.keep.account

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AccountMessage { InvalidCode, InvalidEmail, RateLimited, Unavailable }

data class AccountUiState(
    val codeSentTo: String? = null,
    val busy: Boolean = false,
    /** A new code is being asked for while one is already out: only that button spins, not "Sign in". */
    val resending: Boolean = false,
    /** Seconds until another code may be asked for; 0 when it may be now. */
    val resendIn: Int = 0,
    val message: AccountMessage? = null,
    val confirmingSignOut: Boolean = false,
    val deleting: Boolean = false,
    val deleteFailed: Boolean = false,
)

class AccountViewModel(private val service: KeepEntitlementService) : ViewModel() {
    // Held here rather than in saved state so a code never reaches a Bundle.
    val email = TextFieldState()
    val code = TextFieldState()
    val deleteEmail = TextFieldState()

    val entitlement: StateFlow<KeepEntitlementState> = service.state

    private val mutable = MutableStateFlow(AccountUiState())
    val state: StateFlow<AccountUiState> = mutable.asStateFlow()

    private var countdown: Job? = null

    fun sendCode() {
        val current = mutable.value
        if (current.busy || current.resendIn > 0) return
        val address = current.codeSentTo ?: email.text.toString().trim()
        val resending = current.codeSentTo != null
        mutable.update { it.copy(busy = true, resending = resending, message = null) }
        viewModelScope.launch {
            when (service.requestCode(address)) {
                CodeRequestResult.Sent -> {
                    if (resending) code.clearText()
                    mutable.update { it.copy(busy = false, resending = false, codeSentTo = address) }
                    startCountdown()
                }
                CodeRequestResult.InvalidEmail -> mutable.update { it.copy(busy = false, resending = false, message = AccountMessage.InvalidEmail) }
                CodeRequestResult.RateLimited -> mutable.update { it.copy(busy = false, resending = false, message = AccountMessage.RateLimited) }
                CodeRequestResult.Unavailable -> mutable.update { it.copy(busy = false, resending = false, message = AccountMessage.Unavailable) }
            }
        }
    }

    // The backend refuses another code for the same address within its cooldown, so the button waits it out
    // instead of offering a tap that can only come back as "too many attempts".
    private fun startCountdown() {
        countdown?.cancel()
        countdown = viewModelScope.launch {
            for (left in RESEND_COOLDOWN_SECONDS downTo 1) {
                mutable.update { it.copy(resendIn = left) }
                delay(1_000)
            }
            mutable.update { it.copy(resendIn = 0) }
        }
    }

    fun changeEmail() {
        code.clearText()
        countdown?.cancel()
        mutable.update { it.copy(codeSentTo = null, resendIn = 0, message = null) }
    }

    fun signIn() = run { service.signIn(mutable.value.codeSentTo ?: email.text.toString(), code.text.toString()) }

    fun devSignIn() = run { service.devSignIn() }

    private fun run(call: suspend () -> SignInResult) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            when (call()) {
                SignInResult.Success -> {
                    code.clearText()
                    email.clearText()
                    countdown?.cancel()
                    mutable.value = AccountUiState()
                }
                SignInResult.InvalidCode -> mutable.update { it.copy(busy = false, message = AccountMessage.InvalidCode) }
                SignInResult.RateLimited -> mutable.update { it.copy(busy = false, message = AccountMessage.RateLimited) }
                SignInResult.Unavailable -> mutable.update { it.copy(busy = false, message = AccountMessage.Unavailable) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { service.refresh(force = true) }
    }

    fun askSignOut(asking: Boolean) = mutable.update { it.copy(confirmingSignOut = asking) }

    fun signOut() {
        mutable.update { it.copy(confirmingSignOut = false) }
        viewModelScope.launch { service.signOut() }
    }

    // The delete button works only once the account's own address is typed, as in Vazie VPN: it cannot be pressed
    // and confirmed by accident.
    fun confirmsDeletion(): Boolean {
        val own = entitlement.value.account?.email?.trim()?.lowercase().orEmpty()
        return own.isNotEmpty() && deleteEmail.text.toString().trim().lowercase() == own
    }

    fun deleteAccount(onDeleted: () -> Unit) {
        if (mutable.value.deleting || !confirmsDeletion()) return
        mutable.update { it.copy(deleting = true, deleteFailed = false) }
        viewModelScope.launch {
            when (service.deleteAccount()) {
                DeleteResult.Deleted -> {
                    deleteEmail.clearText()
                    mutable.update { it.copy(deleting = false) }
                    onDeleted()
                }
                DeleteResult.Failed -> mutable.update { it.copy(deleting = false, deleteFailed = true) }
            }
        }
    }

    override fun onCleared() {
        code.clearText()
        deleteEmail.clearText()
    }
}

private const val RESEND_COOLDOWN_SECONDS = 60
