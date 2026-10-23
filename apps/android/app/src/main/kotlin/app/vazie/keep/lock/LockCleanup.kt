package app.vazie.keep.lock

import app.vazie.keep.data.SecretClipboard
import app.vazie.keep.data.VaultRepository
import app.vazie.keep.data.VaultState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

class LockCleanup(private val repository: VaultRepository, private val clipboard: SecretClipboard) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            repository.state.drop(1).filterIsInstance<VaultState.Locked>().collect { clipboard.clearNow() }
        }
    }
}
