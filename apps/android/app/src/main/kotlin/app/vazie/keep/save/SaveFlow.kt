package app.vazie.keep.save

import app.vazie.keep.data.CopyOutcome
import app.vazie.keep.data.MergeOutcome
import app.vazie.keep.data.MergeProblem
import app.vazie.keep.data.SaveFailure
import app.vazie.keep.data.SaveOutcome
import app.vazie.keep.data.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class CopyResult { Saved, Failed }

// What the person is asked while the file and the open vault are being combined.
enum class MergeAsk { Password, WrongPassword }

// How a merge ended, for a message. [conflictCopies] is the number of entries changed on both sides, each kept twice.
sealed interface MergeNotice {
    data class Merged(val conflictCopies: Int) : MergeNotice

    data class Failed(val problem: MergeProblem) : MergeNotice
}

// What a screen does when an edit reached the session but not the file: show the failure and let the
// user retry, save a copy, reload or cancel. One per screen model.
class SaveFlow(private val repository: VaultRepository, private val scope: CoroutineScope) {
    private val mutableProblem = MutableStateFlow<SaveFailure?>(null)
    val problem: StateFlow<SaveFailure?> = mutableProblem.asStateFlow()

    private val mutableCopy = MutableStateFlow<CopyResult?>(null)
    val copyResult: StateFlow<CopyResult?> = mutableCopy.asStateFlow()

    private val mutableMergeAsk = MutableStateFlow<MergeAsk?>(null)
    val mergeAsk: StateFlow<MergeAsk?> = mutableMergeAsk.asStateFlow()

    private val mutableMergeNotice = MutableStateFlow<MergeNotice?>(null)
    val mergeNotice: StateFlow<MergeNotice?> = mutableMergeNotice.asStateFlow()

    fun report(failure: SaveFailure) {
        mutableProblem.value = failure
    }

    fun dismiss() {
        mutableProblem.value = null
    }

    fun copyShown() {
        mutableCopy.value = null
    }

    fun retry(onSaved: () -> Unit = {}) {
        mutableProblem.value = null
        scope.launch {
            when (val outcome = repository.save()) {
                SaveOutcome.Saved -> onSaved()
                is SaveOutcome.Failed -> mutableProblem.value = outcome.failure
            }
        }
    }

    // Discards what isn't in the file and locks; the next unlock reads the file as it is now.
    fun reload() {
        mutableProblem.value = null
        repository.lock(discardChanges = true)
    }

    // Combines the file as it is now with the open vault (ADR 0023) and saves the result. [password] is wiped by the
    // repository; `null` first tries what the session already knows and asks for the password only if that is not enough.
    fun merge(password: ByteArray?, conflictSuffix: String) {
        mutableProblem.value = null
        mutableMergeAsk.value = null
        scope.launch {
            when (val outcome = repository.mergeChangedFile(password, conflictSuffix)) {
                is MergeOutcome.Merged -> when (val save = outcome.save) {
                    SaveOutcome.Saved -> mutableMergeNotice.value = MergeNotice.Merged(outcome.report.conflicts.size)
                    // The file changed once more while merging: the same choice again.
                    is SaveOutcome.Failed -> mutableProblem.value = save.failure
                }
                is MergeOutcome.Problem -> when (outcome.problem) {
                    MergeProblem.NeedsPassword -> mutableMergeAsk.value = MergeAsk.Password
                    MergeProblem.WrongPassword -> mutableMergeAsk.value = MergeAsk.WrongPassword
                    else -> {
                        mutableMergeNotice.value = MergeNotice.Failed(outcome.problem)
                        mutableProblem.value = SaveFailure.ChangedExternally
                    }
                }
            }
        }
    }

    fun cancelMerge() {
        mutableMergeAsk.value = null
        mutableProblem.value = SaveFailure.ChangedExternally
    }

    fun mergeNoticeShown() {
        mutableMergeNotice.value = null
    }

    fun saveCopy(uri: String?) {
        if (uri == null) return
        mutableProblem.value = null
        scope.launch {
            mutableCopy.value = when (repository.saveCopyTo(uri)) {
                CopyOutcome.Written -> CopyResult.Saved
                CopyOutcome.Failed -> CopyResult.Failed
            }
        }
    }
}
