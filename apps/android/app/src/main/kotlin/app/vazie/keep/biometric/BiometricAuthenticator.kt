package app.vazie.keep.biometric

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import javax.crypto.Cipher

enum class BiometricAvailability { Available, NoneEnrolled, NoHardware, Unavailable }

sealed interface AuthResult {
    class Success(val cipher: Cipher) : AuthResult

    data object Cancelled : AuthResult

    data object LockedOut : AuthResult

    data object Failed : AuthResult
}

data class PromptText(val title: String, val subtitle: String?, val negative: String)

interface BiometricAuthenticator {
    fun availability(): BiometricAvailability

    suspend fun authenticate(text: PromptText, cipher: Cipher): AuthResult
}

// Class 3 (strong) biometrics only: device credential would let anyone who knows the screen PIN
// open the vault, and weak (class 2) face unlock cannot authorize a Keystore key.
class AndroidBiometricAuthenticator(context: Context) : BiometricAuthenticator {
    private val manager = BiometricManager.from(context)
    // Every Activity that can show a prompt registers; the newest one that is still alive is used.
    private val hosts = mutableListOf<Pair<WeakReference<FragmentActivity>, BiometricPrompt>>()
    private val prompt: BiometricPrompt? get() = hosts.lastOrNull { it.first.get() != null }?.second
    private var pending: CompletableDeferred<AuthResult>? = null

    private val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            val cipher = result.cryptoObject?.cipher
            finish(if (cipher != null) AuthResult.Success(cipher) else AuthResult.Failed)
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            finish(
                when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_CANCELED,
                    -> AuthResult.Cancelled
                    BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> AuthResult.LockedOut
                    else -> AuthResult.Failed
                },
            )
        }
    }

    // Called from every Activity.onCreate, so a prompt survives rotation with the same callback.
    fun attach(activity: FragmentActivity) {
        hosts.removeAll { it.first.get() == null || it.first.get() === activity }
        hosts += WeakReference(activity) to BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
    }

    fun detach(activity: FragmentActivity) {
        hosts.removeAll { it.first.get() == null || it.first.get() === activity }
    }

    override fun availability(): BiometricAvailability = when (manager.canAuthenticate(BIOMETRIC_STRONG)) {
        BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.Available
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NoneEnrolled
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricAvailability.NoHardware
        else -> BiometricAvailability.Unavailable
    }

    override suspend fun authenticate(text: PromptText, cipher: Cipher): AuthResult = withContext(Dispatchers.Main.immediate) {
        val current = prompt ?: return@withContext AuthResult.Failed
        val deferred = CompletableDeferred<AuthResult>()
        pending?.complete(AuthResult.Cancelled)
        pending = deferred
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(text.title)
            .apply { text.subtitle?.let(::setSubtitle) }
            .setNegativeButtonText(text.negative)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()
        try {
            current.authenticate(info, BiometricPrompt.CryptoObject(cipher))
            deferred.await()
        } finally {
            if (!deferred.isCompleted) prompt?.cancelAuthentication()
            if (pending === deferred) pending = null
        }
    }

    private fun finish(result: AuthResult) {
        pending?.complete(result)
        pending = null
    }
}
