package app.vazie.keep.autofill

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.WindowManager
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.activity.compose.setContent
import androidx.annotation.VisibleForTesting
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider
import app.vazie.keep.KeepApplication
import app.vazie.keep.R
import app.vazie.keep.settings.ThemeMode
import app.vazie.keep.ui.designsystem.KeepTheme

// Opened by the framework when a suggestion is tapped. Secure window, no history, never in recents.
// It unlocks (fingerprint, then password), reveals the one chosen secret, hands it back as a
// Dataset and finishes.
class AutofillAuthActivity : FragmentActivity() {
    private val container get() = (application as KeepApplication).container
    private var delivered = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        val request = AuthRequest.fromIntent(intent)
        if (request == null) {
            cancelAndFinish()
            return
        }
        container.biometricAuthenticator.attach(this)
        val viewModel = ViewModelProvider(
            this,
            viewModelFactory { initializer { container.newAutofillAuthViewModel(request, callingPackage) } },
        )[AutofillAuthViewModel::class.java]
        val originLabel = when (val origin = request.origin) {
            is RequestOrigin.Web -> origin.origin.host
            is RequestOrigin.App -> container.packageIdentity.label(origin.packageName) ?: origin.packageName
        }
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
                    is AuthUiState.Done -> deliver(request, current.payload)
                    AuthUiState.Cancelled -> cancelAndFinish()
                    else -> Unit
                }
            }
            KeepTheme(darkTheme = dark) {
                AutofillAuthScreen(
                    viewModel = viewModel,
                    request = request,
                    originLabel = originLabel,
                    onOpenApp = { openApp() },
                    onCancel = viewModel::cancel,
                )
            }
        }
    }

    private fun deliver(request: AuthRequest, payload: FillPayload) {
        if (delivered) return
        delivered = true
        val dataset = buildDataset(request, payload)
        payload.close()
        val data = Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset)
        lastReturned = RESULT_OK to data
        setResult(RESULT_OK, data)
        finish()
    }

    @Suppress("DEPRECATION")
    private fun buildDataset(request: AuthRequest, payload: FillPayload): Dataset {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Dataset.Builder()
        } else {
            Dataset.Builder(RemoteViews(packageName, R.layout.autofill_dataset))
        }
        val username = payload.username
        if (username != null && request.usernameId != null) {
            builder.setValue(request.usernameId, AutofillValue.forText(username))
        }
        val password = payload.password
        if (password != null && request.passwordId != null) {
            password.useChars { builder.setValue(request.passwordId, AutofillValue.forText(String(it))) }
        }
        return builder.build()
    }

    private fun cancelAndFinish() {
        lastReturned = RESULT_CANCELED to null
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun openApp() {
        startActivity(Intent(this, app.vazie.keep.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        cancelAndFinish()
    }

    override fun onDestroy() {
        container.biometricAuthenticator.detach(this)
        super.onDestroy()
    }

    companion object {
        // What was last handed back to the system, kept so a test can read it (the activity runs in
        // its own task and is gone by the time a test looks).
        @VisibleForTesting
        @Volatile
        var lastReturned: Pair<Int, Intent?>? = null
    }
}
