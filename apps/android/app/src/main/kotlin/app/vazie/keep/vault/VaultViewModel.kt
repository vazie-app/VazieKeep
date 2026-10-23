package app.vazie.keep.vault

import app.vazie.keep.core.SiteIcon
import app.vazie.keep.siteicons.SiteIcons
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vazie.keep.core.EntryId
import app.vazie.keep.core.EntrySummary
import app.vazie.keep.core.SecretField
import app.vazie.keep.core.VaultSessionException
import app.vazie.keep.core.GroupId
import app.vazie.keep.core.KeepVaultSession
import app.vazie.keep.core.VaultEditException
import app.vazie.keep.core.VaultGroup
import app.vazie.keep.data.CopyOutcome
import app.vazie.keep.data.EditOutcome
import app.vazie.keep.data.SaveOutcome
import app.vazie.keep.data.SecretClipboard
import app.vazie.keep.data.VaultLockedException
import app.vazie.keep.data.VaultRepository
import app.vazie.keep.data.VaultState
import app.vazie.keep.save.SaveFlow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

val SEARCH_DEBOUNCE = 200.milliseconds

private const val ICON_REQUESTS = 6

sealed interface VaultUiState {
    data object Loading : VaultUiState

    data object Locked : VaultUiState

    data class Browsing(
        val vaultName: String,
        val path: List<VaultGroup>,
        val currentGroup: GroupId,
        val groups: List<VaultGroup>,
        val entries: List<EntrySummary>,
        val recycleBin: GroupId?,
        val filter: VaultFilter = VaultFilter.All,
        val filters: List<FilterChoice> = emptyList(),
    ) : VaultUiState

    data class Searching(val vaultName: String, val results: List<EntrySummary>?) : VaultUiState
}

sealed interface VaultFilter {
    data object All : VaultFilter

    data object Favorites : VaultFilter

    data object Expired : VaultFilter

    data class Tag(val name: String) : VaultFilter
}

data class FilterChoice(val filter: VaultFilter, val count: Int)

sealed interface VaultDialog {
    data object NewGroup : VaultDialog

    data class RenameGroup(val group: VaultGroup) : VaultDialog

    data class GroupIcon(val group: VaultGroup) : VaultDialog

    data class DeleteGroup(val group: VaultGroup, val groups: Int, val entries: Int, val toBin: Boolean) : VaultDialog

    data object ConfirmLock : VaultDialog

    // Asked before anything is sent: fetching the icons tells each site that someone opened it.
    data object SiteIcons : VaultDialog
}

data class IconProgress(val done: Int, val total: Int)

enum class VaultMessage { InvalidName, CouldNotEdit, CopySaved, CopyFailed, AccessNotGranted, PasswordCopied, IconsAssigned, IconsNothing, IconsUnavailable }

private data class Snapshot(
    val name: String,
    val root: GroupId,
    val recycleBin: GroupId?,
    val groups: List<VaultGroup>,
    val entries: List<EntrySummary>,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class VaultViewModel(
    private val repository: VaultRepository,
    private val background: CoroutineDispatcher = Dispatchers.Default,
    private val clipboard: SecretClipboard? = null,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val siteIcons: SiteIcons? = null,
) : ViewModel() {
    private val snapshot = MutableStateFlow<Snapshot?>(null)
    private val path = MutableStateFlow<List<GroupId>>(emptyList())
    private val filter = MutableStateFlow<VaultFilter>(VaultFilter.All)
    private val locked = MutableStateFlow(false)
    private val mutableQuery = MutableStateFlow("")
    val query: StateFlow<String> = mutableQuery.asStateFlow()

    private val results = combine(
        mutableQuery.debounce { text -> if (text.isBlank()) Duration.ZERO else SEARCH_DEBOUNCE },
        repository.contentRevision,
    ) { text, _ -> text }
        .mapLatest { text -> if (text.isBlank()) null else read { repository.search(text.trim()) } }

    private val place = combine(path, filter) { stack, selected -> stack to selected }

    val state: StateFlow<VaultUiState> = combine(snapshot, place, locked, mutableQuery, results) { data, (stack, selected), isLocked, text, found ->
        when {
            isLocked -> VaultUiState.Locked
            data == null -> VaultUiState.Loading
            text.isNotBlank() -> VaultUiState.Searching(data.name, found.takeIf { text.isNotBlank() })
            else -> browsing(data, stack, selected)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, VaultUiState.Loading)

    val saveFlow = SaveFlow(repository, viewModelScope)

    val canEdit: StateFlow<Boolean> = repository.state.map { (it as? VaultState.Unlocked)?.canEdit == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val readOnlyByPlan: StateFlow<Boolean> = repository.state.map { (it as? VaultState.Unlocked)?.readOnlyByPlan == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val needsWriteGrant: StateFlow<Boolean> = repository.state.map { (it as? VaultState.Unlocked)?.writeGranted == false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val hasUnsavedChanges: StateFlow<Boolean> = repository.hasUnsavedChanges
    val hasRecoveredCopy: StateFlow<Boolean> = repository.hasRecoveredCopy
    val saveInProgress: StateFlow<Boolean> = repository.saveInProgress

    private val mutableDialog = MutableStateFlow<VaultDialog?>(null)
    val dialog: StateFlow<VaultDialog?> = mutableDialog.asStateFlow()

    private val messageChannel = Channel<VaultMessage>(Channel.BUFFERED)
    val messages: Flow<VaultMessage> = messageChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            repository.state.collect { vault ->
                if (vault is VaultState.Locked) {
                    clear()
                } else if (snapshot.value == null) {
                    load((vault as VaultState.Unlocked).info.name)
                }
            }
        }
        viewModelScope.launch {
            repository.contentRevision.drop(1).collect {
                val name = (repository.state.value as? VaultState.Unlocked)?.info?.name ?: return@collect
                load(name)
            }
        }
    }

    var lastCopySeconds: Int? = null
        private set

    fun copyPassword(id: EntryId) {
        val target = clipboard ?: return
        viewModelScope.launch {
            val result = try {
                withContext(background) { repository.reveal(id, SecretField.Password).use { target.copy(it) } }
            } catch (_: VaultLockedException) {
                return@launch
            } catch (_: VaultSessionException) {
                return@launch
            }
            lastCopySeconds = result.clearsAfter?.inWholeSeconds?.toInt()
            if (!result.systemConfirms || lastCopySeconds == null) messageChannel.trySend(VaultMessage.PasswordCopied)
        }
    }

    fun onQueryChange(text: String) {
        mutableQuery.value = text
    }

    fun openGroup(id: GroupId) {
        path.value = path.value + id
    }

    fun selectFilter(selected: VaultFilter) {
        path.value = emptyList()
        filter.value = selected
    }

    fun up(): Boolean {
        if (filter.value != VaultFilter.All) {
            filter.value = VaultFilter.All
            return true
        }
        if (path.value.isEmpty()) return false
        path.value = path.value.dropLast(1)
        return true
    }

    fun requestLock() {
        if (repository.hasUnsavedChanges.value) mutableDialog.value = VaultDialog.ConfirmLock else repository.lock()
    }

    fun lockSavingFirst() {
        mutableDialog.value = null
        viewModelScope.launch {
            when (val outcome = repository.save()) {
                SaveOutcome.Saved -> repository.lock()
                is SaveOutcome.Failed -> saveFlow.report(outcome.failure)
            }
        }
    }

    fun lockDiscarding() {
        mutableDialog.value = null
        repository.lock(discardChanges = true)
    }

    fun requestNewGroup() {
        mutableDialog.value = VaultDialog.NewGroup
    }

    fun requestRename(group: VaultGroup) {
        mutableDialog.value = VaultDialog.RenameGroup(group)
    }

    fun requestDelete(group: VaultGroup) {
        val data = snapshot.value ?: return
        val inside = descendants(data.groups, group.id)
        val toBin = data.recycleBin != null && !isInRecycleBin(data.groups, data.recycleBin, group.id)
        mutableDialog.value = VaultDialog.DeleteGroup(
            group = group,
            groups = inside.size,
            entries = data.entries.count { it.groupId == group.id || it.groupId in inside },
            toBin = toBin,
        )
    }

    fun dismissDialog() {
        mutableDialog.value = null
    }

    fun createGroup(name: String) {
        val parent = (state.value as? VaultUiState.Browsing)?.currentGroup ?: return
        mutableDialog.value = null
        edit { it.createGroup(parent, name.trim()) }
    }

    var lastIconCount: Int = 0
        private set

    private val mutableIconProgress = MutableStateFlow<IconProgress?>(null)
    val iconProgress: StateFlow<IconProgress?> = mutableIconProgress.asStateFlow()
    private var iconJob: Job? = null

    fun requestSiteIcons() {
        mutableDialog.value = VaultDialog.SiteIcons
    }

    fun cancelSiteIcons() {
        iconJob?.cancel()
        mutableIconProgress.value = null
    }

    // Fetches the icon of every site the entries name, straight from the site, and keeps the images in
    // the vault file. An entry whose site gave nothing gets one of the built-in icons by its address.
    fun assignIcons() {
        mutableDialog.value = null
        if (iconJob?.isActive == true) return
        iconJob = viewModelScope.launch {
            val hosts = if (siteIcons == null) emptyList() else read { repository.siteIconHosts() } ?: return@launch
            val fetched = try {
                fetchIcons(hosts)
            } finally {
                mutableIconProgress.value = null
            }
            val outcome = try {
                repository.edit { session ->
                    val images = if (fetched.isEmpty()) 0 else session.setSiteIcons(fetched)
                    images to session.assignIconsByUrl()
                }
            } catch (_: VaultLockedException) {
                clear()
                return@launch
            }
            val (images, plain) = when (outcome) {
                is EditOutcome.Saved -> outcome.value
                is EditOutcome.NotSaved -> {
                    saveFlow.report(outcome.failure)
                    outcome.value
                }
                is EditOutcome.Invalid -> return@launch messageChannel.send(VaultMessage.CouldNotEdit)
            }
            lastIconCount = if (images > 0) images else plain
            messageChannel.send(
                when {
                    lastIconCount > 0 -> VaultMessage.IconsAssigned
                    hosts.isNotEmpty() -> VaultMessage.IconsUnavailable
                    else -> VaultMessage.IconsNothing
                },
            )
        }
    }

    private suspend fun fetchIcons(hosts: List<String>): List<SiteIcon> {
        val source = siteIcons ?: return emptyList()
        if (hosts.isEmpty()) return emptyList()
        mutableIconProgress.value = IconProgress(0, hosts.size)
        val gate = Semaphore(ICON_REQUESTS)
        return coroutineScope {
            hosts.map { host ->
                async {
                    val png = gate.withPermit { source.fetch(host) }
                    mutableIconProgress.update { it?.copy(done = it.done + 1) }
                    png?.let { SiteIcon(host, it) }
                }
            }.awaitAll().filterNotNull()
        }
    }

    fun requestIcon(group: VaultGroup) {
        mutableDialog.value = VaultDialog.GroupIcon(group)
    }

    fun setGroupIcon(group: VaultGroup, icon: Int) {
        mutableDialog.value = null
        edit { it.setGroupIcon(group.id, icon) }
    }

    fun renameGroup(group: VaultGroup, name: String) {
        mutableDialog.value = null
        edit { it.renameGroup(group.id, name.trim()) }
    }

    fun deleteGroup(group: VaultGroup, permanent: Boolean) {
        mutableDialog.value = null
        edit { it.deleteGroup(group.id, recursive = true, permanent = permanent) }
    }

    fun saveRecoveredCopy(uri: String?) {
        if (uri == null) return
        viewModelScope.launch {
            messageChannel.send(
                when (repository.saveRecoveredCopyTo(uri)) {
                    CopyOutcome.Written -> VaultMessage.CopySaved
                    CopyOutcome.Failed -> VaultMessage.CopyFailed
                },
            )
        }
    }

    fun discardRecoveredCopy() {
        repository.discardRecoveredCopy()
    }

    fun regrantAccess(uri: String?) {
        if (uri == null) return
        if (!repository.regrantWriteAccess(uri)) messageChannel.trySend(VaultMessage.AccessNotGranted)
    }

    private fun <T> edit(block: (KeepVaultSession) -> T) {
        viewModelScope.launch {
            val outcome = try {
                repository.edit(block)
            } catch (_: VaultLockedException) {
                clear()
                return@launch
            }
            when (outcome) {
                is EditOutcome.Saved -> Unit
                is EditOutcome.NotSaved -> saveFlow.report(outcome.failure)
                is EditOutcome.Invalid -> messageChannel.send(
                    if (outcome.reason == VaultEditException.Reason.InvalidText) VaultMessage.InvalidName else VaultMessage.CouldNotEdit,
                )
            }
        }
    }

    private suspend fun load(name: String) {
        val loaded = read {
            val groups = repository.groups()
            val bin = (repository.state.value as? VaultState.Unlocked)?.info?.recycleBinId
            Snapshot(name, groups.first().id, bin, groups, repository.entries())
        } ?: return
        snapshot.value = loaded
    }

    private suspend fun <T> read(block: () -> T): T? = try {
        withContext(background) { block() }
    } catch (_: VaultLockedException) {
        clear()
        null
    }

    private fun clear() {
        locked.value = true
        snapshot.value = null
        path.value = emptyList()
        filter.value = VaultFilter.All
        mutableQuery.value = ""
        mutableDialog.update { null }
    }

    private fun browsing(data: Snapshot, stack: List<GroupId>, selected: VaultFilter): VaultUiState.Browsing {
        val live = data.entries.filter { data.recycleBin == null || !isInRecycleBin(data.groups, data.recycleBin, it.groupId) }
        val now = nowSeconds()
        val filters = filterChoices(live, now)
        val active = selected.takeIf { choice -> filters.any { it.filter == choice } } ?: VaultFilter.All
        if (active != VaultFilter.All) {
            return VaultUiState.Browsing(
                vaultName = data.name,
                path = emptyList(),
                currentGroup = data.root,
                groups = emptyList(),
                entries = live.filter { it.matches(active, now) },
                recycleBin = data.recycleBin,
                filter = active,
                filters = filters,
            )
        }
        val byId = data.groups.associateBy { it.id }
        val trail = stack.mapNotNull { byId[it] }
        val current = trail.lastOrNull()?.id ?: data.root
        return VaultUiState.Browsing(
            vaultName = data.name,
            path = trail,
            currentGroup = current,
            groups = data.groups.filter { it.parentId == current },
            entries = data.entries.filter { it.groupId == current },
            recycleBin = data.recycleBin,
            filters = filters,
        )
    }
}

private fun filterChoices(entries: List<EntrySummary>, now: Long): List<FilterChoice> {
    val favorites = entries.count { it.favorite }
    val expired = entries.count { it.isExpired(now) }
    val tags = entries.flatMap { it.tags.distinct() }
        .groupingBy { it }
        .eachCount()
        .toList()
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })
    val extra = buildList {
        if (favorites > 0) add(FilterChoice(VaultFilter.Favorites, favorites))
        if (expired > 0) add(FilterChoice(VaultFilter.Expired, expired))
        tags.forEach { (name, count) -> add(FilterChoice(VaultFilter.Tag(name), count)) }
    }
    return if (extra.isEmpty()) emptyList() else listOf(FilterChoice(VaultFilter.All, entries.size)) + extra
}

private fun EntrySummary.matches(filter: VaultFilter, now: Long): Boolean = when (filter) {
    VaultFilter.All -> true
    VaultFilter.Favorites -> favorite
    VaultFilter.Expired -> isExpired(now)
    is VaultFilter.Tag -> filter.name in tags
}
