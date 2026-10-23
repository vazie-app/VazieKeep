package app.vazie.keep.autofill

import app.vazie.keep.core.EntrySummary
import app.vazie.keep.core.EntryText
import app.vazie.keep.core.VaultGroup
import app.vazie.keep.core.VaultInfo
import app.vazie.keep.data.OpenedVault
import app.vazie.keep.data.VaultLockedException
import app.vazie.keep.data.VaultRepository
import app.vazie.keep.data.VaultState
import app.vazie.keep.vault.isInRecycleBin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

object IndexBuilder {
    // From the core's summaries: only plain title, username and URL, and nothing in the Recycle Bin.
    fun build(info: VaultInfo, groups: List<VaultGroup>, entries: List<EntrySummary>): AutofillIndex = AutofillIndex(
        info.fingerprint,
        entries
            .filter { !isInRecycleBin(groups, info.recycleBinId, it.groupId) }
            .map {
                IndexedEntry(
                    entryId = it.id.value,
                    title = (it.title as? EntryText.Plain)?.value.orEmpty(),
                    username = (it.username as? EntryText.Plain)?.value.orEmpty(),
                    url = (it.url as? EntryText.Plain)?.value.orEmpty(),
                )
            },
    )

    fun persist(index: AutofillIndex, store: AutofillIndexStore, associations: AppAssociationStore) {
        store.save(index)
        associations.retain(index.fingerprint, index.entries.mapTo(HashSet()) { it.entryId })
    }

    fun persist(vault: OpenedVault, store: AutofillIndexStore, associations: AppAssociationStore) {
        persist(build(vault.info, vault.session.groups(), vault.session.entries()), store, associations)
    }
}

// Keeps the non-secret index current: after every unlock and every edit while the vault is open.
class AutofillIndexer(
    private val repository: VaultRepository,
    private val store: AutofillIndexStore,
    private val associations: AppAssociationStore,
    private val scope: CoroutineScope,
    private val background: CoroutineDispatcher = Dispatchers.Default,
    // Other indexes of the same open vault, rebuilt at the same moments.
    private val alsoIndex: (info: VaultInfo, entries: List<app.vazie.keep.core.EntrySummary>) -> Unit = { _, _ -> },
) {
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun start() {
        scope.launch {
            combine(repository.state, repository.contentRevision) { state, revision -> state to revision }
                .debounce(150.milliseconds)
                .mapLatest { (state, _) -> if (state is VaultState.Unlocked) rebuild(state.info) }
                .collect {}
        }
    }

    // A different vault: the old suggestions and app choices must not outlive it.
    fun clear() {
        store.clear()
        associations.clear()
    }

    private suspend fun rebuild(info: VaultInfo) = withContext(background) {
        try {
            val entries = repository.entries()
            IndexBuilder.persist(IndexBuilder.build(info, repository.groups(), entries), store, associations)
            alsoIndex(info, entries)
        } catch (_: VaultLockedException) {
        }
    }
}
