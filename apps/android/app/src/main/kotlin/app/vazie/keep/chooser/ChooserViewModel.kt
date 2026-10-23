package app.vazie.keep.chooser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vazie.keep.data.SelectError
import app.vazie.keep.data.SelectResult
import app.vazie.keep.data.VaultDescriptor
import app.vazie.keep.data.VaultManager
import app.vazie.keep.data.VaultRegistry
import app.vazie.keep.data.VaultRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HubVault(val descriptor: VaultDescriptor, val active: Boolean, val available: Boolean?)

data class ChooserUiState(
    val vaults: List<HubVault> = emptyList(),
    val checking: Boolean = false,
    val error: SelectError? = null,
    val relocateFailed: Boolean = false,
    val selected: Boolean = false,
)

class ChooserViewModel(
    private val repository: VaultRepository,
    private val registry: VaultRegistry,
    private val manager: VaultManager,
) : ViewModel() {
    private val availability = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val mutableState = MutableStateFlow(ChooserUiState())
    val state: StateFlow<ChooserUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            registry.snapshot.collect { snapshot ->
                mutableState.update { it.copy(vaults = snapshot.vaults.toHub(snapshot.activeId)) }
            }
        }
        viewModelScope.launch {
            availability.collect {
                mutableState.update { state -> state.copy(vaults = state.vaults.map { it.copy(available = availability.value[it.descriptor.id]) }) }
            }
        }
        refreshAvailability()
    }

    private fun List<VaultDescriptor>.toHub(activeId: String?) = sortedByDescending { it.lastUsedAt }
        .map { HubVault(it, it.id == activeId, availability.value[it.id]) }

    fun refreshAvailability() {
        viewModelScope.launch {
            val result = registry.snapshot.value.vaults.associate { it.id to manager.isAvailable(it) }
            availability.value = result
        }
    }

    fun onFilePicked(uri: String?) {
        if (uri == null || state.value.checking) return
        mutableState.update { it.copy(checking = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.select(uri)) {
                is SelectResult.Selected -> mutableState.update { it.copy(checking = false, selected = true) }
                is SelectResult.Failure -> mutableState.update { it.copy(checking = false, error = result.error) }
            }
        }
    }

    fun open(id: String) {
        repository.activate(id)
        mutableState.update { it.copy(selected = true, error = null) }
    }

    fun forget(id: String) {
        viewModelScope.launch { manager.forget(id) }
    }

    fun rename(id: String, name: String) = manager.rename(id, name)

    fun locate(id: String, uri: String?) {
        if (uri == null) return
        viewModelScope.launch {
            val problem = manager.relocate(id, uri)
            mutableState.update { it.copy(relocateFailed = problem != null) }
            refreshAvailability()
        }
    }

    fun onNavigated() {
        mutableState.update { it.copy(selected = false) }
    }
}
