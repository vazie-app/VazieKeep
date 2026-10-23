package app.vazie.keep.create

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vazie.keep.R
import app.vazie.keep.biometric.PromptText
import app.vazie.keep.ui.components.PasswordStrength
import app.vazie.keep.ui.designsystem.KeepBanner
import app.vazie.keep.ui.designsystem.KeepBannerTone
import app.vazie.keep.ui.designsystem.KeepButton
import app.vazie.keep.ui.designsystem.KeepButtonStyle
import app.vazie.keep.ui.designsystem.KeepCard
import app.vazie.keep.ui.designsystem.KeepDesign
import app.vazie.keep.ui.designsystem.KeepIconButton
import app.vazie.keep.ui.designsystem.KeepScreen
import app.vazie.keep.ui.designsystem.KeepSecureField
import app.vazie.keep.ui.designsystem.KeepSectionLabel
import app.vazie.keep.ui.designsystem.KeepStrengthMeter
import app.vazie.keep.ui.designsystem.KeepTextField
import app.vazie.keep.ui.designsystem.KeepTopBar
import app.vazie.keep.ui.icons.VazieIcons

@Composable
fun CreateVaultScreen(viewModel: CreateVaultViewModel, onBack: () -> Unit, onOpenVault: () -> Unit, onOpenList: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val destination = rememberLauncherForActivityResult(CreateVaultDocument()) { uri ->
        viewModel.onDestinationPicked(uri?.toString())
    }
    val keyFileSave = rememberLauncherForActivityResult(CreateVaultDocument()) { uri ->
        viewModel.onKeyFileSaved(uri?.toString(), uri?.let { displayName(context, it) })
    }
    val keyFilePick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.onKeyFilePicked(uri?.toString(), uri?.let { displayName(context, it) })
    }
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is CreateEvent.PickDestination -> destination.launch(event.fileName)
                is CreateEvent.SaveKeyFile -> keyFileSave.launch(event.fileName)
            }
        }
    }
    val phase = state.phase
    KeepScreen {
        Column(Modifier.fillMaxSize()) {
            KeepTopBar(title = stringResource(R.string.create_title), onBack = if (phase is CreatePhase.Ready) null else onBack)
            when (phase) {
                is CreatePhase.Ready -> ReadyContent(viewModel, phase, onOpenVault)
                else -> FormContent(viewModel, state, keyFilePick::launch, onOpenList)
            }
        }
    }
}

private fun displayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
} catch (_: SecurityException) {
    null
}

@Composable
private fun FormContent(viewModel: CreateVaultViewModel, state: CreateUiState, pickKeyFile: (Array<String>) -> Unit, onOpenList: () -> Unit) {
    val colors = KeepDesign.colors
    val creating = state.phase == CreatePhase.Creating
    val typed = viewModel.password.text
    val strength = PasswordStrength.estimate(typed)
    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        KeepTextField(
            state = viewModel.name,
            label = stringResource(R.string.create_name_label),
            enabled = !creating,
            supporting = stringResource(R.string.create_name_supporting),
            error = if (state.problem == CreateProblem.NameMissing) stringResource(R.string.create_name_missing) else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )

        KeepSecureField(
            state = viewModel.password,
            label = stringResource(R.string.create_password_label),
            visible = state.passwordVisible,
            enabled = !creating,
            error = if (state.problem == CreateProblem.PasswordMissing) stringResource(R.string.create_password_missing) else null,
            trailing = {
                KeepIconButton(
                    if (state.passwordVisible) VazieIcons.EyeOff else VazieIcons.Eye,
                    stringResource(if (state.passwordVisible) R.string.action_hide_password else R.string.action_show_password),
                    viewModel::toggleVisible,
                )
            },
        )
        if (strength != null) {
            KeepStrengthMeter(
                level = strength.level,
                caption = stringResource(strength.label),
                bits = pluralStringResource(R.plurals.strength_bits, strength.bits, strength.bits),
            )
        }
        KeepSecureField(
            state = viewModel.confirmation,
            label = stringResource(R.string.create_confirm_label),
            visible = state.passwordVisible,
            enabled = !creating,
            error = if (state.problem == CreateProblem.PasswordMismatch) stringResource(R.string.create_password_mismatch) else null,
        )
        KeepButton(
            stringResource(R.string.create_generate),
            viewModel::generatePassword,
            style = KeepButtonStyle.Tonal,
            icon = VazieIcons.Dice,
            enabled = !creating,
        )
        if (state.passwordGenerated) KeepBanner(stringResource(R.string.create_generated_warning), tone = KeepBannerTone.Caution)
        KeepBanner(stringResource(R.string.create_no_recovery), tone = KeepBannerTone.Info, icon = VazieIcons.Warning)

        KeepSectionLabel(stringResource(R.string.create_keyfile_title))
        KeepCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.create_keyfile_body), style = KeepDesign.type.rowSubtitle, color = colors.textSecondary)
                if (state.keyFileName == null) {
                    KeepButton(stringResource(R.string.create_keyfile_choose), { pickKeyFile(arrayOf("*/*")) }, style = KeepButtonStyle.Tonal, icon = VazieIcons.File, enabled = !creating)
                    KeepButton(stringResource(R.string.create_keyfile_generate), viewModel::generateKeyFile, style = KeepButtonStyle.Tonal, icon = VazieIcons.Key, enabled = !creating)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        androidx.compose.material3.Icon(VazieIcons.Key, contentDescription = null, tint = colors.accent)
                        Text(stringResource(R.string.create_keyfile_selected, state.keyFileName), style = KeepDesign.type.rowTitle, color = colors.text, modifier = Modifier.weight(1f))
                    }
                    KeepBanner(stringResource(R.string.create_keyfile_warning), tone = KeepBannerTone.Caution)
                    KeepButton(stringResource(R.string.create_keyfile_remove), viewModel::removeKeyFile, style = KeepButtonStyle.Text, enabled = !creating)
                }
                when (state.problem) {
                    CreateProblem.KeyFileUnreadable -> KeepBanner(stringResource(R.string.create_keyfile_unreadable), tone = KeepBannerTone.Error)
                    CreateProblem.KeyFileNotSaved -> KeepBanner(stringResource(R.string.create_keyfile_not_saved), tone = KeepBannerTone.Error)
                    else -> Unit
                }
            }
        }

        KeepSectionLabel(stringResource(R.string.create_security_title))
        KeepCard {
            Column(Modifier.selectableGroup()) {
                PresetRow(SecurityPreset.Balanced, R.string.create_preset_balanced, R.string.create_preset_balanced_body, state, creating) { viewModel.selectPreset(it) }
                PresetRow(SecurityPreset.Stronger, R.string.create_preset_stronger, R.string.create_preset_stronger_body, state, creating) { viewModel.selectPreset(it) }
            }
        }

        if (state.unlockFailed) {
            KeepBanner(
                stringResource(R.string.create_unlock_failed),
                tone = KeepBannerTone.Caution,
                actions = { KeepButton(stringResource(R.string.hub_title), onOpenList, style = KeepButtonStyle.Text, size = app.vazie.keep.ui.designsystem.KeepButtonSize.Pill, fullWidth = false) },
            )
        }
        when (state.problem) {
            CreateProblem.Write -> KeepBanner(stringResource(R.string.create_error_write), tone = KeepBannerTone.Error)
            CreateProblem.Other -> KeepBanner(stringResource(R.string.create_error_other), tone = KeepBannerTone.Error)
            else -> Unit
        }
        KeepButton(
            stringResource(if (creating) R.string.create_creating else R.string.create_action),
            viewModel::requestCreate,
            icon = VazieIcons.Lock,
            enabled = !creating,
            loading = creating,
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PresetRow(
    preset: SecurityPreset,
    title: Int,
    body: Int,
    state: CreateUiState,
    disabled: Boolean,
    onSelect: (SecurityPreset) -> Unit,
) {
    val colors = KeepDesign.colors
    val selected = state.preset == preset
    val ms = state.benchmarkMs[preset]
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = !disabled, role = Role.RadioButton, onClick = { onSelect(preset) })
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = !disabled)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = KeepDesign.type.rowTitle, color = colors.text)
            Text(stringResource(body), style = KeepDesign.type.rowSubtitle, color = colors.textSecondary)
            Text(
                if (ms == null) stringResource(R.string.create_preset_measuring) else stringResource(R.string.create_preset_time, String.format(LocalLocale.current.platformLocale, "%.1f", ms / 1000.0)),
                style = KeepDesign.type.caption,
                color = colors.textTertiary,
            )
        }
    }
}

@Composable
private fun ReadyContent(viewModel: CreateVaultViewModel, phase: CreatePhase.Ready, onOpenVault: () -> Unit) {
    val colors = KeepDesign.colors
    val prompt = PromptText(
        title = stringResource(R.string.biometric_enable_prompt_title),
        subtitle = stringResource(R.string.biometric_enable_prompt_subtitle),
        negative = stringResource(R.string.action_cancel),
    )
    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.create_done_title), style = KeepDesign.type.headline, color = colors.text)
        Text(stringResource(R.string.create_done_body), style = KeepDesign.type.body, color = colors.textSecondary)
        if (phase.biometricsFailed) KeepBanner(stringResource(R.string.create_biometric_failed), tone = KeepBannerTone.Caution)
        if (phase.canOfferBiometrics) {
            KeepButton(stringResource(R.string.create_biometric_offer), { viewModel.enableBiometrics(prompt) }, style = KeepButtonStyle.Tonal, icon = VazieIcons.Biometric)
        }
        KeepButton(stringResource(R.string.create_open), onOpenVault, icon = VazieIcons.Folder)
    }
}
