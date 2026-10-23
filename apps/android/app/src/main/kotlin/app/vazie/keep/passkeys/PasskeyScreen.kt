package app.vazie.keep.passkeys

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vazie.keep.R
import app.vazie.keep.autofill.AutofillSheet
import app.vazie.keep.autofill.FailedPane
import app.vazie.keep.autofill.UnlockForm
import app.vazie.keep.ui.designsystem.KeepBanner
import app.vazie.keep.ui.designsystem.KeepBannerTone
import app.vazie.keep.ui.designsystem.KeepButton
import app.vazie.keep.ui.designsystem.KeepButtonSize
import app.vazie.keep.ui.designsystem.KeepButtonStyle
import app.vazie.keep.ui.designsystem.KeepDesign
import app.vazie.keep.ui.designsystem.KeepShapes

@Composable
fun PasskeyScreen(viewModel: PasskeyViewModel, creating: Boolean, onOpenApp: () -> Unit, onClose: () -> Unit, onCancel: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val title = stringResource(if (creating) R.string.passkey_create_title else R.string.passkey_use_title)
    val unlock = state as? PasskeyUiState.Unlock
    val subtitle = unlock?.let { if (it.userName.isEmpty()) it.rpId else stringResource(R.string.passkey_subject, it.rpId, it.userName) }
    AutofillSheet(title = title, subtitle = subtitle, avatar = unlock?.rpId ?: title, onCancel = onCancel) {
        when (val current = state) {
            PasskeyUiState.Loading, is PasskeyUiState.Done, PasskeyUiState.Cancelled ->
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(KeepShapes.pill), color = KeepDesign.colors.accent, trackColor = KeepDesign.colors.surfaceRaised)
            is PasskeyUiState.Unlock -> UnlockForm(
                summaryTitle = current.rpId,
                summarySubtitle = current.userName,
                biometric = current.biometric,
                busy = current.busy,
                message = current.message,
                keyFileName = current.keyFileName,
                password = viewModel.password,
                focusRequests = viewModel.focusRequests,
                confirmLabel = if (creating) R.string.passkey_create_action else R.string.passkey_use_action,
                onUnlock = viewModel::unlockWithPassword,
                onBiometric = viewModel::unlockWithBiometric,
                onCancel = onCancel,
            )
            is PasskeyUiState.Failed -> when (val failure = current.failure) {
                is PasskeyFailure.Unlock -> FailedPane(failure.problem, onOpenApp, onClose)
                is PasskeyFailure.Passkey -> {
                    KeepBanner(stringResource(failure.problem.message()), tone = KeepBannerTone.Error)
                    KeepButton(stringResource(R.string.action_ok), onClose, style = KeepButtonStyle.Tonal, size = KeepButtonSize.Large)
                }
            }
        }
    }
}

private fun PasskeyProblem.message() = when (this) {
    PasskeyProblem.BadRequest -> R.string.passkey_problem_request
    PasskeyProblem.CallerNotVerified -> R.string.passkey_problem_caller
    PasskeyProblem.SiteMismatch -> R.string.passkey_problem_site
    PasskeyProblem.CouldNotCheckSite -> R.string.passkey_problem_check
    PasskeyProblem.AlreadyExists -> R.string.passkey_problem_exists
    PasskeyProblem.UnsupportedAlgorithm -> R.string.passkey_problem_algorithm
    PasskeyProblem.NoPasskey -> R.string.passkey_problem_gone
    PasskeyProblem.NotWritable -> R.string.autofill_problem_not_writable
    PasskeyProblem.VaultChangedElsewhere -> R.string.autofill_problem_changed_elsewhere
    PasskeyProblem.CouldNotSave -> R.string.autofill_problem_could_not_save
    PasskeyProblem.Failed -> R.string.error_unknown
}
