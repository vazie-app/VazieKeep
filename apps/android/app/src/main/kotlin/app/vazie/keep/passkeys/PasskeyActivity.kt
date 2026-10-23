package app.vazie.keep.passkeys

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.CallingAppInfo
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.vazie.keep.KeepApplication
import app.vazie.keep.R
import app.vazie.keep.biometric.PromptText
import app.vazie.keep.settings.ThemeMode
import app.vazie.keep.ui.designsystem.KeepTheme
import java.security.MessageDigest

// Opened by Android when the person picked Vazie Keep for a passkey. Secure window, no history, never in
// recents. It checks who is asking, unlocks the vault, makes or uses the passkey and hands the answer back.
class PasskeyActivity : FragmentActivity() {
    private val container get() = (application as KeepApplication).container
    private var finished = false
    private var creating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        creating = intent.action == ACTION_CREATE
        val request = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) read(intent) else null
        container.biometricAuthenticator.attach(this)
        val viewModel = ViewModelProvider(
            this,
            viewModelFactory { initializer { container.newPasskeyViewModel(request) } },
        )[PasskeyViewModel::class.java]
        viewModel.start(
            PromptText(
                getString(R.string.biometric_prompt_title),
                getString(if (creating) R.string.passkey_create_title else R.string.passkey_use_title),
                getString(R.string.biometric_prompt_use_password),
            ),
        )
        setContent {
            val settings by container.settings.collectAsStateWithLifecycle()
            val dark = when (settings.themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            val state by viewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(state) {
                when (val current = state) {
                    is PasskeyUiState.Done -> deliver(current)
                    PasskeyUiState.Cancelled -> cancelAndFinish()
                    else -> Unit
                }
            }
            KeepTheme(darkTheme = dark) {
                PasskeyScreen(viewModel = viewModel, creating = creating, onOpenApp = { openApp() }, onClose = { failAndFinish() }, onCancel = viewModel::cancel)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun read(intent: Intent): PasskeyRequest? {
        if (intent.action == ACTION_CREATE) {
            val provided = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent) ?: return null
            val asked = provided.callingRequest as? CreatePublicKeyCredentialRequest ?: return null
            val options = WebAuthnJson.creation(asked.requestJson) ?: return null
            val caller = caller(provided.callingAppInfo) ?: return null
            return PasskeyRequest.Create(options, caller, asked.clientDataHash)
        }
        val provided = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent) ?: return null
        val asked = provided.credentialOptions.filterIsInstance<GetPublicKeyCredentialOption>().firstOrNull() ?: return null
        val options = WebAuthnJson.assertion(asked.requestJson) ?: return null
        val entryId = intent.getStringExtra(EXTRA_ENTRY) ?: return null
        val caller = caller(provided.callingAppInfo) ?: return null
        return PasskeyRequest.Get(options, entryId, caller, asked.clientDataHash)
    }

    // A web origin is believed only from a browser on the bundled list of privileged apps, matched by its
    // signing certificate. Anything else is an app speaking for itself.
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun caller(info: CallingAppInfo): PasskeyCaller? {
        if (info.isOriginPopulated()) {
            val origin = try {
                info.getOrigin(container.privilegedBrowsers)
            } catch (_: IllegalStateException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            return origin?.let { PasskeyCaller.Browser(info.packageName, it.trimEnd('/')) }
        }
        val signer = info.signingInfo.apkContentsSigners.firstOrNull() ?: return null
        return PasskeyCaller.App(info.packageName, MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()))
    }

    private fun deliver(done: PasskeyUiState.Done) {
        if (finished) return
        finished = true
        val data = Intent()
        if (done.creating) {
            PendingIntentHandler.setCreateCredentialResponse(data, CreatePublicKeyCredentialResponse(done.json))
            Toast.makeText(this, R.string.passkey_created, Toast.LENGTH_SHORT).show()
        } else {
            PendingIntentHandler.setGetCredentialResponse(data, GetCredentialResponse(PublicKeyCredential(done.json)))
        }
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    private fun cancelAndFinish() = finishWith(cancelled = true)

    private fun failAndFinish() = finishWith(cancelled = false)

    private fun finishWith(cancelled: Boolean) {
        if (finished) return
        finished = true
        val data = Intent()
        if (creating) {
            PendingIntentHandler.setCreateCredentialException(data, if (cancelled) CreateCredentialCancellationException() else CreateCredentialUnknownException())
        } else {
            PendingIntentHandler.setGetCredentialException(data, if (cancelled) GetCredentialCancellationException() else GetCredentialUnknownException())
        }
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    private fun openApp() {
        startActivity(Intent(this, app.vazie.keep.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        failAndFinish()
    }

    override fun onDestroy() {
        container.biometricAuthenticator.detach(this)
        super.onDestroy()
    }

    companion object {
        const val ACTION_CREATE = "app.vazie.keep.passkeys.CREATE"
        const val ACTION_GET = "app.vazie.keep.passkeys.GET"
        const val EXTRA_ENTRY = "entry"
    }
}
