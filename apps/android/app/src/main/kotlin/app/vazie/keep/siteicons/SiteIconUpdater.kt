package app.vazie.keep.siteicons

import app.vazie.keep.core.EntryId
import app.vazie.keep.core.SiteIcon
import app.vazie.keep.data.VaultLockedException
import app.vazie.keep.data.VaultRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Fetches the icon of one entry's site after the entry is saved, when the person turned that on in Settings.
// Off by default: without it nothing in a vault leads to a network request by itself.
class SiteIconUpdater(
    private val repository: VaultRepository,
    private val icons: () -> SiteIcons,
    private val enabled: () -> Boolean,
    private val scope: CoroutineScope,
    private val background: CoroutineDispatcher = Dispatchers.Default,
) {
    fun entrySaved(id: EntryId) {
        if (!enabled()) return
        scope.launch {
            try {
                val host = withContext(background) { repository.siteIconHost(id) } ?: return@launch
                val png = icons().fetch(host) ?: return@launch
                repository.edit { it.setSiteIcons(listOf(SiteIcon(host, png))) }
            } catch (_: VaultLockedException) {
            }
        }
    }
}
