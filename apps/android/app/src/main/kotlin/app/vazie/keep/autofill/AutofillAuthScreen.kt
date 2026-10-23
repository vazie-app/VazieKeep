package app.vazie.keep.autofill

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vazie.keep.R
import app.vazie.keep.biometric.PromptText
import app.vazie.keep.ui.components.EmptyState
import app.vazie.keep.ui.components.ProtectedTextField
import app.vazie.keep.ui.designsystem.KeepBanner
import app.vazie.keep.ui.designsystem.KeepBannerTone
import app.vazie.keep.ui.designsystem.KeepButton
import app.vazie.keep.ui.designsystem.KeepButtonSize
import app.vazie.keep.ui.designsystem.KeepButtonStyle
import app.vazie.keep.ui.designsystem.KeepCard
import app.vazie.keep.ui.designsystem.KeepDesign
import app.vazie.keep.ui.designsystem.KeepDialog
import app.vazie.keep.ui.designsystem.KeepDialogAction
import app.vazie.keep.ui.designsystem.KeepHairline
import app.vazie.keep.ui.designsystem.KeepShapes
import app.vazie.keep.ui.designsystem.KeepSwitch
import app.vazie.keep.ui.designsystem.KeepTextField
import app.vazie.keep.ui.designsystem.Monogram
import app.vazie.keep.ui.designsystem.monogramTone
import app.vazie.keep.ui.icons.VazieIcons

@Composable
fun AutofillAuthScreen(
    viewModel: AutofillAuthViewModel,
    request: AuthRequest,
    originLabel: String,
    onOpenApp: () -> Unit,
    onCancel: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prompt = PromptText(
        title = stringResource(R.string.biometric_prompt_title),
        subtitle = stringResource(R.string.autofill_prompt_subtitle, originLabel),
        negative = stringResource(R.string.biometric_prompt_use_password),
    )
    LaunchedEffect(Unit) { viewModel.start(prompt) }
    AutofillSheet(
        title = stringResource(if (state is AuthUiState.Choosing) R.string.autofill_search_title else R.string.autofill_auth_title),
        subtitle = originLabel,
        avatar = originLabel,
        onCancel = onCancel,
    ) {
        when (val current = state) {
            AuthUiState.Loading, AuthUiState.Cancelled, is AuthUiState.Done -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = KeepDesign.colors.accent)
            }
            is AuthUiState.Choosing -> {
                val remember by viewModel.remembersChoice.collectAsStateWithLifecycle()
                Choosing(
                    state = current,
                    remember = remember,
                    offerRemember = request.origin is RequestOrigin.App,
                    onRemember = { viewModel.remembersChoice.value = it },
                    onSearch = viewModel::search,
                    onChoose = viewModel::choose,
                )
            }
            is AuthUiState.Confirm -> Confirm(current, originLabel, viewModel)
            is AuthUiState.Unlock -> UnlockPane(current, viewModel, onCancel)
            is AuthUiState.Failed -> FailedPane(current.problem, onOpenApp, onCancel)
        }
    }
}

@Composable
internal fun Choosing(
    state: AuthUiState.Choosing,
    remember: Boolean,
    offerRemember: Boolean,
    onRemember: (Boolean) -> Unit,
    onSearch: (String) -> Unit,
    onChoose: (IndexedEntry) -> Unit,
) {
    val search = rememberTextFieldState()
    val colors = KeepDesign.colors
    LaunchedEffect(search) {
        snapshotFlow { search.text.toString() }.collect { onSearch(it) }
    }
    KeepTextField(
        state = search,
        label = null,
        placeholder = stringResource(R.string.vault_search_hint),
        leadingIcon = VazieIcons.Search,
        height = 52,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
    )
    if (offerRemember) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(KeepShapes.chip)
                .clickable(role = Role.Switch) { onRemember(!remember) }
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.autofill_remember), style = KeepDesign.type.rowSubtitle, color = colors.textSecondary, modifier = Modifier.weight(1f).padding(end = 12.dp))
            KeepSwitch(remember)
        }
    }
    if (state.entries.isEmpty()) {
        EmptyState(
            VazieIcons.Search,
            stringResource(if (state.indexAvailable) R.string.vault_search_empty_title else R.string.autofill_no_index_title),
            stringResource(if (state.indexAvailable) R.string.vault_search_empty_message else R.string.autofill_no_index_message),
            modifier = Modifier.heightIn(min = 220.dp),
        )
    } else {
        KeepCard {
            state.entries.forEachIndexed { index, entry ->
                if (index > 0) KeepHairline(Modifier.padding(start = 70.dp))
                val title = entry.title.ifBlank { stringResource(R.string.entry_untitled) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clickable(role = Role.Button) { onChoose(entry) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Monogram(title, size = 42.dp, radius = 13.dp, tone = monogramTone(colors, title))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(title, style = KeepDesign.type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (entry.username.isNotEmpty()) {
                            Text(entry.username, style = KeepDesign.type.rowSubtitle, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Confirm(state: AuthUiState.Confirm, originLabel: String, viewModel: AutofillAuthViewModel) {
    KeepDialog(
        onDismissRequest = viewModel::declineChoice,
        title = stringResource(R.string.autofill_mismatch_title),
        message = stringResource(R.string.autofill_mismatch_message, state.entry.title.ifBlank { stringResource(R.string.entry_untitled) }, originLabel),
    ) {
        KeepDialogAction(stringResource(R.string.autofill_mismatch_fill), viewModel::confirmChoice, KeepButtonStyle.Action)
        KeepDialogAction(stringResource(R.string.action_cancel), viewModel::declineChoice)
    }
}

@Composable
private fun UnlockPane(state: AuthUiState.Unlock, viewModel: AutofillAuthViewModel, onCancel: () -> Unit) {
    UnlockForm(
        summaryTitle = state.entry.title.ifBlank { stringResource(R.string.entry_untitled) },
        summarySubtitle = state.entry.username,
        biometric = state.biometric,
        busy = state.busy,
        message = state.message,
        keyFileName = state.keyFileName,
        password = viewModel.password,
        focusRequests = viewModel.focusRequests,
        confirmLabel = R.string.autofill_unlock_fill,
        onUnlock = viewModel::unlockWithPassword,
        onBiometric = viewModel::useBiometric,
        onCancel = onCancel,
    )
}

@Composable
internal fun UnlockForm(
    summaryTitle: String,
    summarySubtitle: String,
    biometric: Boolean,
    busy: Boolean,
    message: UnlockMessage?,
    keyFileName: String?,
    password: TextFieldState,
    focusRequests: kotlinx.coroutines.flow.Flow<Unit>,
    confirmLabel: Int,
    onUnlock: () -> Unit,
    onBiometric: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = KeepDesign.colors
    val focus = remember { FocusRequester() }
    var visible by remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(focusRequests) { focusRequests.collect { focus.requestFocus() } }
    LaunchedEffect(biometric) { if (!biometric) focus.requestFocus() }
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Monogram(summaryTitle, size = 42.dp, radius = 13.dp, tone = monogramTone(colors, summaryTitle))
        Column(Modifier.weight(1f)) {
            Text(summaryTitle, style = KeepDesign.type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (summarySubtitle.isNotEmpty()) {
                Text(summarySubtitle, style = KeepDesign.type.rowSubtitle, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    message?.let {
        if (it != UnlockMessage.WrongPassword) {
            KeepBanner(
                stringResource(
                    when (it) {
                        UnlockMessage.WrongPassword -> R.string.error_wrong_password
                        UnlockMessage.BiometricFailed -> R.string.biometric_failed
                        UnlockMessage.BiometricLockedOut -> R.string.biometric_locked_out
                        UnlockMessage.BiometricNeedsSetup -> R.string.biometric_needs_setup
                    },
                ),
                tone = KeepBannerTone.Error,
            )
        }
    }
    ProtectedTextField(
        state = password,
        label = stringResource(R.string.unlock_password_label),
        visible = visible,
        onToggleVisible = { visible = !visible },
        enabled = !busy,
        error = if (message == UnlockMessage.WrongPassword) stringResource(R.string.error_wrong_password) else null,
        focus = focus,
        onDone = onUnlock,
    )
    keyFileName?.let { name ->
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Icon(VazieIcons.Key, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.key_file_label), style = KeepDesign.type.caption, color = colors.textSecondary)
            Text(name, style = KeepDesign.type.mono.copy(fontSize = 13.sp), color = colors.text, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
    }
    KeepButton(stringResource(confirmLabel), onUnlock, enabled = !busy, loading = busy, size = KeepButtonSize.Large)
    if (busy) {
        Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth().clip(KeepShapes.pill), color = colors.accent, trackColor = colors.surfaceRaised)
            Text(stringResource(R.string.unlock_progress), style = KeepDesign.type.caption, color = colors.textSecondary)
        }
    }
    if (biometric) {
        KeepButton(stringResource(R.string.unlock_biometric), onBiometric, style = KeepButtonStyle.Tonal, enabled = !busy, icon = VazieIcons.Biometric)
    }
    KeepButton(stringResource(R.string.action_cancel), onCancel, style = KeepButtonStyle.Text, size = KeepButtonSize.Medium, fullWidth = true)
}

@Composable
internal fun FailedPane(problem: AuthProblem, onOpenApp: () -> Unit, onCancel: () -> Unit) {
    val message = when (problem) {
        AuthProblem.VaultChanged -> R.string.autofill_problem_vault_changed
        AuthProblem.EntryGone -> R.string.autofill_problem_entry_gone
        AuthProblem.NoLongerMatches -> R.string.autofill_problem_no_match
        AuthProblem.KeyFileUnavailable -> R.string.autofill_problem_key_file
        AuthProblem.RestoreNeeded -> R.string.autofill_problem_restore
        AuthProblem.FileUnavailable -> R.string.error_file_unavailable
        AuthProblem.CouldNotOpen -> R.string.error_unknown
        AuthProblem.NoVault -> R.string.autofill_problem_no_vault
        AuthProblem.CallerNotVerified -> R.string.autofill_problem_caller
        AuthProblem.VaultChangedElsewhere -> R.string.autofill_problem_changed_elsewhere
        AuthProblem.NotWritable -> R.string.autofill_problem_not_writable
        AuthProblem.CouldNotSave -> R.string.autofill_problem_could_not_save
        AuthProblem.Expired -> R.string.autofill_problem_expired
    }
    KeepBanner(stringResource(message), tone = KeepBannerTone.Error)
    KeepButton(stringResource(R.string.autofill_open_app), onOpenApp)
    KeepButton(stringResource(R.string.action_cancel), onCancel, style = KeepButtonStyle.Text, size = KeepButtonSize.Medium, fullWidth = true)
}
