package app.vazie.keep.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import app.vazie.keep.core.SecretValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

data class CopyResult(val systemConfirms: Boolean, val clearsAfter: Duration?)

interface SecretClipboard {
    fun copy(secret: SecretValue): CopyResult

    fun clearNow()
}

class AndroidSecretClipboard(
    context: Context,
    private val scope: CoroutineScope,
    private val clearAfter: () -> Duration?,
) : SecretClipboard {
    private val manager = context.getSystemService(ClipboardManager::class.java)
    private var clearJob: Job? = null

    override fun copy(secret: SecretValue): CopyResult {
        // ClipData is parcelled as a String by the framework, so building one here adds no extra copy.
        val clip = secret.useChars { chars -> ClipData.newPlainText(LABEL, String(chars)) }
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        manager.setPrimaryClip(clip)
        clearJob?.cancel()
        val timeout = clearAfter()
        if (timeout != null) {
            clearJob = scope.launch {
                delay(timeout)
                clearNow()
            }
        }
        return CopyResult(systemConfirms = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU, clearsAfter = timeout)
    }

    override fun clearNow() {
        clearJob?.cancel()
        clearJob = null
        runCatching {
            if (manager.primaryClipDescription?.label == LABEL) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    manager.clearPrimaryClip()
                } else {
                    manager.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }
    }

    private companion object {
        const val LABEL = "Vazie Keep"
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
