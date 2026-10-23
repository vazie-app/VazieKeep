package app.vazie.keep.autofill

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.vazie.keep.KeepApplication
import app.vazie.keep.R
import app.vazie.keep.settings.ThemeMode
import app.vazie.keep.ui.designsystem.KeepTheme

// Opened by the system after the person tapped "Save" in Android's own prompt. It shows what would be
// saved, asks once more (create or update), unlocks, writes through the app's safe save and finishes.
class AutofillSaveActivity : FragmentActivity() {
    private val container get() = (application as KeepApplication).container
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        val token = intent.getStringExtra(TOKEN)
        if (token == null) {
            finish(Activity.RESULT_CANCELED)
            return
        }
        container.biometricAuthenticator.attach(this)
        val viewModel = ViewModelProvider(
            this,
            viewModelFactory { initializer { container.newAutofillSaveViewModel(token) } },
        )[AutofillSaveViewModel::class.java]
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
                    is SaveUiState.Done -> {
                        Toast.makeText(
                            this@AutofillSaveActivity,
                            when (current.result) {
                                is SaveResult.Created -> R.string.autofill_saved_created
                                is SaveResult.Updated -> R.string.autofill_saved_updated
                                else -> R.string.autofill_save_unchanged
                            },
                            Toast.LENGTH_SHORT,
                        ).show()
                        finish(Activity.RESULT_OK)
                    }
                    SaveUiState.Cancelled -> finish(Activity.RESULT_CANCELED)
                    else -> Unit
                }
            }
            KeepTheme(darkTheme = dark) {
                AutofillSaveScreen(viewModel = viewModel, onOpenApp = { openApp() }, onCancel = viewModel::cancel)
            }
        }
    }

    private fun finish(code: Int) {
        if (finished) return
        finished = true
        lastReturned = code
        setResult(code)
        finish()
    }

    private fun openApp() {
        startActivity(Intent(this, app.vazie.keep.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish(Activity.RESULT_CANCELED)
    }

    override fun onDestroy() {
        container.biometricAuthenticator.detach(this)
        super.onDestroy()
    }

    companion object {
        const val TOKEN = "token"

        @VisibleForTesting
        @Volatile
        var lastReturned: Int? = null
    }
}
