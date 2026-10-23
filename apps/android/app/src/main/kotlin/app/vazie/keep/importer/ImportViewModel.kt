package app.vazie.keep.importer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vazie.keep.core.ImportException
import app.vazie.keep.core.ImportOutcome
import app.vazie.keep.core.ImportPlan
import app.vazie.keep.core.ImportSummary
import app.vazie.keep.core.KeepCore
import app.vazie.keep.data.AttachmentFile
import app.vazie.keep.data.EditOutcome
import app.vazie.keep.data.VaultLockedException
import app.vazie.keep.data.VaultRepository
import app.vazie.keep.data.VaultState
import app.vazie.keep.save.SaveFlow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ImportProblem { Unreadable, TooLarge, Empty, Unrecognized, Encrypted, Malformed, Failed }

sealed interface ImportUiState {
    data object Idle : ImportUiState

    data object Reading : ImportUiState

    data class Preview(val summary: ImportSummary) : ImportUiState

    data class Importing(val summary: ImportSummary) : ImportUiState

    data class Done(val outcome: ImportOutcome) : ImportUiState

    data class Failed(val problem: ImportProblem) : ImportUiState
}

// Takes another password manager's export into the open vault. The file is the person's passwords in
// plain text: its bytes are wiped as soon as the core has read them, and the parsed export lives in the
// core until it is imported or dropped.
class ImportViewModel(
    private val core: KeepCore,
    private val repository: VaultRepository,
    private val background: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val mutableState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val state: StateFlow<ImportUiState> = mutableState.asStateFlow()
    private var plan: ImportPlan? = null

    val saveFlow = SaveFlow(repository, viewModelScope)

    init {
        viewModelScope.launch {
            repository.state.collect { vault -> if (vault is VaultState.Locked) dismiss() }
        }
    }

    fun onFilePicked(uri: String?) {
        if (uri == null || mutableState.value !is ImportUiState.Idle) return
        mutableState.value = ImportUiState.Reading
        viewModelScope.launch {
            val bytes = when (val file = repository.readAttachmentFile(uri)) {
                is AttachmentFile.Read -> file.bytes
                AttachmentFile.TooLarge -> return@launch fail(ImportProblem.TooLarge)
                AttachmentFile.Unavailable -> return@launch fail(ImportProblem.Unreadable)
            }
            val parsed = try {
                withContext(background) { core.parseImport(bytes) }
            } catch (error: ImportException) {
                return@launch fail(error.reason.toProblem())
            } finally {
                bytes.fill(0)
            }
            if (mutableState.value !is ImportUiState.Reading) {
                parsed.close()
                return@launch
            }
            plan = parsed
            mutableState.value = ImportUiState.Preview(parsed.summary())
        }
    }

    fun confirm() {
        val preview = mutableState.value as? ImportUiState.Preview ?: return
        val current = plan ?: return
        val root = (repository.state.value as? VaultState.Unlocked)?.info?.rootGroupId ?: return dismiss()
        mutableState.value = ImportUiState.Importing(preview.summary)
        viewModelScope.launch {
            val outcome = try {
                repository.edit { it.importPlan(root, current) }
            } catch (_: VaultLockedException) {
                return@launch dismiss()
            }
            drop()
            mutableState.value = when (outcome) {
                is EditOutcome.Saved -> ImportUiState.Done(outcome.value)
                is EditOutcome.NotSaved -> {
                    saveFlow.report(outcome.failure)
                    ImportUiState.Done(outcome.value)
                }
                is EditOutcome.Invalid -> ImportUiState.Failed(ImportProblem.Failed)
            }
        }
    }

    fun dismiss() {
        drop()
        mutableState.value = ImportUiState.Idle
    }

    override fun onCleared() = drop()

    private fun fail(problem: ImportProblem) {
        mutableState.value = ImportUiState.Failed(problem)
    }

    private fun drop() {
        plan?.close()
        plan = null
    }
}

private fun ImportException.Reason.toProblem() = when (this) {
    ImportException.Reason.Empty -> ImportProblem.Empty
    ImportException.Reason.TooLarge -> ImportProblem.TooLarge
    ImportException.Reason.Unrecognized -> ImportProblem.Unrecognized
    ImportException.Reason.Encrypted -> ImportProblem.Encrypted
    ImportException.Reason.Malformed -> ImportProblem.Malformed
}
