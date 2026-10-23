package app.vazie.keep.unlock

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vazie.keep.R
import app.vazie.keep.biometric.BiometricStatus
import app.vazie.keep.biometric.PromptText
import app.vazie.keep.data.KeyFileError
import app.vazie.keep.data.UnlockError
import app.vazie.keep.ui.components.ProtectedTextField
import app.vazie.keep.ui.designsystem.KeepBanner
import app.vazie.keep.ui.designsystem.KeepBannerTone
import app.vazie.keep.ui.designsystem.KeepButton
import app.vazie.keep.ui.designsystem.KeepButtonSize
import app.vazie.keep.ui.designsystem.KeepButtonStyle
import app.vazie.keep.ui.designsystem.KeepDesign
import app.vazie.keep.ui.designsystem.KeepIconButton
import app.vazie.keep.ui.designsystem.KeepIconTone
import app.vazie.keep.ui.designsystem.KeepScreen
import app.vazie.keep.ui.designsystem.KeepShapes
import app.vazie.keep.ui.designsystem.KeepTopBar
import app.vazie.keep.ui.designsystem.Monogram
import app.vazie.keep.ui.icons.VazieIcons

@Composable
fun UnlockScreen(viewModel: UnlockViewModel, onUnlocked: () -> Unit, onChooseOther: () -> Unit, onSettings: () -> Unit, banner: @Composable () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keyFile by viewModel.keyFile.collectAsStateWithLifecycle()
    val biometric by viewModel.biometricStatus.collectAsStateWithLifecycle()
    val biometricChecked by viewModel.biometricChecked.collectAsStateWithLifecycle()
    var visible by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.onKeyFileChosen(it.toString()) }
    }
    val promptText = PromptText(
        title = stringResource(R.string.biometric_prompt_title),
        subtitle = viewModel.vaultName,
        negative = stringResource(R.string.biometric_prompt_use_password),
    )
    val focus = remember { FocusRequester() }
    val unlocking = state == UnlockUiState.Unlocking
    val colors = KeepDesign.colors
    val vaultName = viewModel.vaultName ?: stringResource(R.string.unlock_unknown_vault)

    LaunchedEffect(state) {
        if (state == UnlockUiState.Unlocked) {
            viewModel.onNavigated()
            onUnlocked()
        }
    }
    // With biometric unlock on, the keyboard would cover its button, so the field waits for a tap.
    LaunchedEffect(biometricChecked) {
        if (biometricChecked && biometric !is BiometricStatus.On) focus.requestFocus()
    }
    LaunchedEffect(state) {
        if (state is UnlockUiState.Failed) focus.requestFocus()
    }

    KeepScreen {
        Column(Modifier.fillMaxSize().imePadding()) {
            KeepTopBar(
                leading = {
                    Row(
                        modifier = Modifier
                            .height(44.dp)
                            .clip(KeepShapes.pill)
                            .background(colors.surfaceField)
                            .clickable(enabled = !unlocking, role = Role.Button, onClick = onChooseOther)
                            .padding(start = 10.dp, end = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(VazieIcons.Swap, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.unlock_databases), style = KeepDesign.type.label.copy(fontSize = 14.sp), color = colors.text)
                    }
                },
                actions = {
                    KeepIconButton(VazieIcons.Settings, stringResource(R.string.settings_title), onSettings, visualSize = 48)
                },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                banner()
                Spacer(Modifier.height(12.dp))
                Monogram(vaultName, size = 106.dp, radius = 32.dp, tone = colors.vaultTiles.first(), textSize = 45f)
                Text(
                    if (unlocking) stringResource(R.string.unlock_opening, vaultName) else vaultName,
                    style = KeepDesign.type.headline.copy(fontSize = 32.5.sp, lineHeight = 38.sp),
                    color = colors.text,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp).semantics { heading() },
                )
                Text(
                    stringResource(R.string.unlock_vault_subtitle),
                    style = KeepDesign.type.mono,
                    color = colors.textSecondary,
                )
                KeyFileChip(keyFile = keyFile, enabled = !unlocking, onChoose = { picker.launch(arrayOf("*/*")) }, onRemove = viewModel::removeKeyFile)
                keyFile.error?.let { error ->
                    KeepBanner(
                        stringResource(
                            when (error) {
                                KeyFileError.Unavailable -> R.string.error_key_file_unavailable
                                KeyFileError.IsVault -> R.string.error_key_file_is_vault
                            },
                        ),
                        tone = KeepBannerTone.Error,
                    )
                }
                (state as? UnlockUiState.Failed)?.let { failed ->
                    if (failed.error != UnlockError.WrongPassword && failed.error != UnlockError.WrongPasswordOrKeyFile) {
                        KeepBanner(stringResource(failed.error.message()), tone = KeepBannerTone.Error)
                    }
                    if (failed.error == UnlockError.DamagedRestorable) {
                        KeepButton(stringResource(R.string.unlock_restore), viewModel::restoreLastSave, style = KeepButtonStyle.Tonal, size = KeepButtonSize.Medium)
                    }
                }
                if (state == UnlockUiState.Restored) KeepBanner(stringResource(R.string.restore_done))
                if (state == UnlockUiState.RestoreFailed) KeepBanner(stringResource(R.string.restore_failed), tone = KeepBannerTone.Error)
                (state as? UnlockUiState.BiometricFailed)?.let { failed ->
                    KeepBanner(stringResource(failed.problem.message()), tone = KeepBannerTone.Error)
                }
                if (biometric == BiometricStatus.NeedsSetup && state !is UnlockUiState.BiometricFailed) {
                    KeepBanner(stringResource(R.string.biometric_needs_setup))
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (unlocking) {
                    Column(
                        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(KeepShapes.pill),
                            color = colors.accent,
                            trackColor = colors.surfaceRaised,
                        )
                        Text(stringResource(R.string.unlock_progress), style = KeepDesign.type.caption, color = colors.textSecondary)
                    }
                } else {
                    MasterPasswordField(
                        state = viewModel.password,
                        visible = visible,
                        onToggleVisible = { visible = !visible },
                        enabled = true,
                        error = (state as? UnlockUiState.Failed)?.error
                            ?.takeIf { it == UnlockError.WrongPassword || it == UnlockError.WrongPasswordOrKeyFile }
                            ?.let { stringResource(it.message()) },
                        focus = focus,
                        onDone = viewModel::unlock,
                        onSubmit = viewModel::unlock,
                    )
                    if (biometric is BiometricStatus.On) {
                        KeepButton(
                            stringResource(R.string.unlock_biometric),
                            { viewModel.unlockWithBiometrics(promptText) },
                            style = KeepButtonStyle.Tonal,
                            icon = VazieIcons.Biometric,
                        )
                    }
                }
            }
        }
    }
}


private fun UnlockError.message() = when (this) {
    UnlockError.WrongPassword -> R.string.error_wrong_password
    UnlockError.WrongPasswordOrKeyFile -> R.string.error_wrong_password_or_key_file
    UnlockError.MissingCredentials -> R.string.error_missing_credentials
    UnlockError.KeyFileUnavailable -> R.string.error_key_file_unavailable
    UnlockError.KeyFileTooLarge -> R.string.error_key_file_too_large
    UnlockError.InvalidKeyFile -> R.string.error_key_file_invalid
    UnlockError.VaultMismatch -> R.string.biometric_needs_setup
    UnlockError.Damaged -> R.string.error_damaged
    UnlockError.DamagedRestorable -> R.string.error_damaged_restorable
    UnlockError.Unsupported -> R.string.error_unsupported
    UnlockError.NotAVault -> R.string.error_not_a_vault
    UnlockError.FileUnavailable -> R.string.error_file_unavailable
    UnlockError.TooLarge -> R.string.error_too_large
    UnlockError.Unknown -> R.string.error_unknown
}

private fun BiometricProblem.message() = when (this) {
    BiometricProblem.LockedOut -> R.string.biometric_locked_out
    BiometricProblem.Failed -> R.string.biometric_failed
    BiometricProblem.NeedsSetup -> R.string.biometric_needs_setup
}

@Composable
private fun KeyFileChip(keyFile: KeyFileUi, enabled: Boolean, onChoose: () -> Unit, onRemove: () -> Unit) {
    val colors = KeepDesign.colors
    val label = stringResource(R.string.key_file_label)
    if (keyFile.name == null) {
        KeepButton(
            stringResource(R.string.key_file_choose),
            onChoose,
            style = KeepButtonStyle.Tonal,
            size = KeepButtonSize.Pill,
            icon = VazieIcons.Key,
            enabled = enabled,
            fullWidth = false,
        )
    } else {
        Row(
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                .background(colors.surfaceField)
                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(VazieIcons.Key, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                    Text(label, style = KeepDesign.type.caption, color = colors.textSecondary)
                    Text(keyFile.name, style = KeepDesign.type.mono.copy(fontSize = 13.sp), color = colors.text, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.padding(vertical = 8.dp))
                    Icon(VazieIcons.Check, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                }
            }
            KeepButton(stringResource(R.string.key_file_change), onChoose, style = KeepButtonStyle.Text, size = KeepButtonSize.Pill, enabled = enabled)
            KeepButton(stringResource(R.string.key_file_remove), onRemove, style = KeepButtonStyle.Text, size = KeepButtonSize.Pill, enabled = enabled)
        }
    }
}

@Composable
private fun MasterPasswordField(
    state: TextFieldState,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    enabled: Boolean,
    error: String?,
    focus: FocusRequester,
    onDone: () -> Unit,
    onSubmit: () -> Unit,
) {
    ProtectedTextField(
        state = state,
        label = stringResource(R.string.unlock_password_label),
        visible = visible,
        onToggleVisible = onToggleVisible,
        enabled = enabled,
        error = error,
        focus = focus,
        onDone = onDone,
        placeholder = stringResource(R.string.unlock_password_label),
        showLabel = false,
        extraTrailing = {
            KeepIconButton(
                VazieIcons.ArrowForward,
                stringResource(R.string.unlock_action),
                onSubmit,
                tone = KeepIconTone.Action,
                visualSize = 48,
                shape = KeepShapes.innerButton,
                enabled = enabled,
            )
        },
    )
}
