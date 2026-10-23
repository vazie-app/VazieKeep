package app.vazie.keep

import android.graphics.Color
import android.os.Bundle
import java.io.FileDescriptor
import java.io.PrintWriter
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.vazie.keep.settings.ThemeMode
import app.vazie.keep.siteicons.LocalEntryImages
import app.vazie.keep.ui.KeepNavHost
import app.vazie.keep.ui.designsystem.KeepTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    override fun dump(prefix: String, fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
        if (!BuildConfig.DEBUG) return super.dump(prefix, fd, writer, args)
        val container = (application as KeepApplication).container
        DebugState.write(container, writer)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val container = (application as KeepApplication).container
        container.biometricAuthenticator.attach(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                container.settings.map { it.screenshotsAllowed }.distinctUntilChanged().collect { allowed ->
                    if (allowed) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }
        }
        setContent {
            val settings by container.settings.collectAsStateWithLifecycle()
            val dark = when (settings.themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
                onDispose {}
            }
            KeepTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalEntryImages provides container.entryImages) {
                    KeepNavHost(container)
                }
            }
        }
    }

    override fun onDestroy() {
        (application as KeepApplication).container.biometricAuthenticator.detach(this)
        super.onDestroy()
    }
}
