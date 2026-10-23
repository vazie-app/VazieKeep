package app.vazie.keep.save

import androidx.compose.runtime.getValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vazie.keep.R
import app.vazie.keep.data.MergeProblem
import app.vazie.keep.data.SaveFailure
import app.vazie.keep.ui.components.ProtectedTextField
import app.vazie.keep.unlock.encodeUtf8

@Composable
fun SaveFlowHost(flow: SaveFlow, suggestedName: String, snackbar: SnackbarHostState) {
    val problem by flow.problem.collectAsStateWithLifecycle()
    val copy by flow.copyResult.collectAsStateWithLifecycle()
    val mergeAsk by flow.mergeAsk.collectAsStateWithLifecycle()
    val mergeNotice by flow.mergeNotice.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val conflictSuffix = stringResource(R.string.merge_conflict_suffix)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        flow.saveCopy(uri?.toString())
    }
    LaunchedEffect(copy) {
        val result = copy ?: return@LaunchedEffect
        snackbar.showSnackbar(resources.getString(if (result == CopyResult.Saved) R.string.copy_saved else R.string.copy_failed))
        flow.copyShown()
    }
    LaunchedEffect(mergeNotice) {
        val notice = mergeNotice ?: return@LaunchedEffect
        val text = when (notice) {
            is MergeNotice.Merged ->
                if (notice.conflictCopies == 0) resources.getString(R.string.merge_done)
                else resources.getString(R.string.merge_done_copies, notice.conflictCopies)
            is MergeNotice.Failed -> resources.getString(
                if (notice.problem == MergeProblem.DifferentVault) R.string.merge_different_vault else R.string.merge_failed,
            )
        }
        snackbar.showSnackbar(text)
        flow.mergeNoticeShown()
    }
    mergeAsk?.let { ask ->
        MergePasswordDialog(
            wrong = ask == MergeAsk.WrongPassword,
            onMerge = { password -> flow.merge(password, conflictSuffix) },
            onCancel = flow::cancelMerge,
        )
    }
    problem?.let { failure ->
        SaveFailureDialog(
            failure = failure,
            onRetry = { flow.retry() },
            onSaveCopy = { picker.launch(suggestedName) },
            onReload = flow::reload,
            onDismiss = flow::dismiss,
            onMerge = { flow.merge(null, conflictSuffix) },
        )
    }
}

// The master password of the vault, for opening its newest version next to the one that is open.
@Composable
private fun MergePasswordDialog(wrong: Boolean, onMerge: (ByteArray?) -> Unit, onCancel: () -> Unit) {
    val password = remember { TextFieldState() }
    var visible by remember { mutableStateOf(false) }
    val submit = {
        val bytes = password.text.takeIf { it.isNotEmpty() }?.encodeUtf8()
        password.clearText()
        onMerge(bytes)
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.merge_password_title)) },
        text = {
            Column {
                Text(stringResource(R.string.merge_password_message))
                ProtectedTextField(
                    state = password,
                    label = stringResource(R.string.unlock_password_label),
                    visible = visible,
                    onToggleVisible = { visible = !visible },
                    error = if (wrong) stringResource(R.string.error_wrong_password) else null,
                    onDone = submit,
                )
            }
        },
        confirmButton = { TextButton(onClick = submit) { Text(stringResource(R.string.save_merge)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun SaveFailureDialog(
    failure: SaveFailure,
    onRetry: () -> Unit,
    onSaveCopy: () -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
    onMerge: (() -> Unit)? = null,
) {
    val conflict = failure == SaveFailure.ChangedExternally
    val retryable = when (failure) {
        SaveFailure.Unavailable, SaveFailure.NotWritable, SaveFailure.InsufficientSpace, SaveFailure.BackupFailed,
        SaveFailure.VerificationFailed, SaveFailure.EncryptionFailed,
        -> true
        else -> false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (conflict) R.string.save_changed_title else R.string.save_failed_title)) },
        text = { Text(stringResource(failure.message())) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                when {
                    conflict -> {
                        if (onMerge != null) TextButton(onClick = onMerge) { Text(stringResource(R.string.save_merge)) }
                        TextButton(onClick = onReload) { Text(stringResource(R.string.save_reload)) }
                    }
                    retryable -> TextButton(onClick = onRetry) { Text(stringResource(R.string.save_try_again)) }
                }
                if (failure != SaveFailure.Locked) {
                    TextButton(onClick = onSaveCopy) { Text(stringResource(R.string.save_copy)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

private fun SaveFailure.message() = when (this) {
    SaveFailure.ChangedExternally -> R.string.save_changed_message
    SaveFailure.Unavailable -> R.string.save_unavailable
    SaveFailure.NotWritable -> R.string.save_not_writable
    SaveFailure.InsufficientSpace -> R.string.save_no_space
    SaveFailure.BackupFailed -> R.string.save_backup_failed
    SaveFailure.VerificationFailed -> R.string.save_verification_failed
    SaveFailure.EncryptionFailed -> R.string.save_encryption_failed
    SaveFailure.ProviderUnsupported -> R.string.save_unsupported
    SaveFailure.RestoreNeeded -> R.string.save_restore_needed
    SaveFailure.ReadOnly -> R.string.save_read_only
    SaveFailure.Locked -> R.string.save_locked
}
