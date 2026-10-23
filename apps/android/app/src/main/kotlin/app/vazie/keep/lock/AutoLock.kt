package app.vazie.keep.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.vazie.keep.data.VaultRepository
import app.vazie.keep.data.VaultState
import app.vazie.keep.settings.SecuritySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class AutoLockController(
    private val repository: VaultRepository,
    private val scope: CoroutineScope,
    private val settings: StateFlow<SecuritySettings>,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : DefaultLifecycleObserver {
    private var backgroundedAt: Long? = null
    private var pending: Job? = null

    init {
        scope.launch {
            settings.map { it.autoLockTimeout }.distinctUntilChanged().drop(1).collect {
                if (backgroundedAt != null) schedule()
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) = onBackground()

    override fun onStart(owner: LifecycleOwner) = onForeground()

    fun onBackground() {
        repository.requestHideSecrets()
        if (repository.state.value !is VaultState.Unlocked) return
        backgroundedAt = clock()
        schedule()
    }

    fun onForeground() {
        pending?.cancel()
        pending = null
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (clock() - since >= settings.value.autoLockTimeout.duration.inWholeMilliseconds) lock()
    }

    fun onScreenOff() {
        repository.requestHideSecrets()
        if (settings.value.lockOnDeviceLock && repository.state.value is VaultState.Unlocked) lock()
    }

    private fun schedule() {
        pending?.cancel()
        val since = backgroundedAt ?: return
        val remaining = settings.value.autoLockTimeout.duration - (clock() - since).milliseconds
        if (!remaining.isPositive()) {
            lock()
            return
        }
        pending = scope.launch {
            delay(remaining)
            lock()
        }
    }

    private fun lock() {
        pending?.cancel()
        pending = null
        backgroundedAt = null
        repository.lock()
    }
}

class ScreenOffReceiver(private val onScreenOff: () -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_SCREEN_OFF) onScreenOff()
    }

    fun register(context: Context) {
        ContextCompat.registerReceiver(context, this, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
