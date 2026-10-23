package app.vazie.keep.chooser

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vazie.keep.R
import app.vazie.keep.data.SelectError
import app.vazie.keep.ui.components.ErrorBanner
import app.vazie.keep.data.VaultDescriptor
import app.vazie.keep.ui.designsystem.KeepBadge
import app.vazie.keep.ui.designsystem.KeepBadgeTone
import app.vazie.keep.ui.designsystem.KeepBanner
import app.vazie.keep.ui.designsystem.KeepBannerTone
import app.vazie.keep.ui.designsystem.KeepDialog
import app.vazie.keep.ui.designsystem.KeepDialogAction
import app.vazie.keep.ui.designsystem.KeepSectionLabel
import app.vazie.keep.ui.designsystem.KeepShapes
import app.vazie.keep.ui.designsystem.KeepTextField
import app.vazie.keep.ui.designsystem.Monogram
import app.vazie.keep.ui.designsystem.KeepButton
import app.vazie.keep.data.VaultPlan
import app.vazie.keep.data.VaultRules
import app.vazie.keep.ui.designsystem.KeepButtonSize
import app.vazie.keep.ui.designsystem.KeepButtonStyle
import app.vazie.keep.ui.designsystem.KeepCard
import app.vazie.keep.ui.designsystem.KeepDesign
import app.vazie.keep.ui.designsystem.KeepHairline
import app.vazie.keep.ui.designsystem.KeepIconButton
import app.vazie.keep.ui.designsystem.KeepScreen
import app.vazie.keep.ui.designsystem.KeepSettingRow
import app.vazie.keep.ui.icons.VazieIcons

@Composable
fun ChooserScreen(
    viewModel: ChooserViewModel,
    coreVersion: String,
    onVaultReady: () -> Unit,
    onCreate: () -> Unit,
    onSettings: () -> Unit,
    rules: VaultRules = VaultRules.of(VaultPlan.Plus, emptyList(), null),
    onGetPlus: () -> Unit = {},
    banner: @Composable () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(OpenVaultDocument()) { uri ->
        viewModel.onFilePicked(uri?.toString())
    }
    var locating by remember { mutableStateOf<String?>(null) }
    val locate = rememberLauncherForActivityResult(OpenVaultDocument()) { uri ->
        locating?.let { viewModel.locate(it, uri?.toString()) }
        locating = null
    }
    var renaming by remember { mutableStateOf<VaultDescriptor?>(null) }
    var forgetting by remember { mutableStateOf<VaultDescriptor?>(null) }
    LaunchedEffect(state.selected) {
        if (state.selected) {
            viewModel.onNavigated()
            onVaultReady()
        }
    }
    val colors = KeepDesign.colors
    KeepScreen {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_name), style = KeepDesign.type.title.copy(fontSize = 20.sp), color = colors.text, modifier = Modifier.weight(1f).semantics { heading() })
                Text(stringResource(R.string.welcome_by), style = KeepDesign.type.caption, color = colors.textSecondary)
                KeepIconButton(VazieIcons.Settings, stringResource(R.string.settings_title), onSettings)
            }
            banner()
            if (state.vaults.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.welcome_line_1), style = KeepDesign.type.display, color = colors.text)
                    Text(stringResource(R.string.welcome_line_2), style = KeepDesign.type.display, color = colors.text)
                    Text(stringResource(R.string.welcome_line_3), style = KeepDesign.type.display, color = colors.accent)
                }
                Text(
                    stringResource(R.string.chooser_tagline),
                    style = KeepDesign.type.body.copy(fontSize = 15.sp),
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                KeepCard {
                    KeepSettingRow(stringResource(R.string.welcome_offline_title), subtitle = stringResource(R.string.welcome_offline_body), icon = VazieIcons.Offline)
                    KeepHairline()
                    KeepSettingRow(stringResource(R.string.welcome_account_title), subtitle = stringResource(R.string.welcome_account_body), icon = VazieIcons.User)
                    KeepHairline()
                    KeepSettingRow(stringResource(R.string.welcome_kdbx_title), subtitle = stringResource(R.string.welcome_kdbx_body), icon = VazieIcons.File)
                }
                Spacer(Modifier.weight(1f, fill = false).height(4.dp))
            } else {
                KeepSectionLabel(stringResource(R.string.hub_title))
                state.vaults.forEach { vault ->
                    VaultHubCard(
                        vault = vault,
                        role = when {
                            rules.editable == null -> null
                            rules.canEdit(vault.descriptor.id) -> R.string.hub_main
                            else -> R.string.hub_read_only
                        },
                        onOpen = { viewModel.open(vault.descriptor.id) },
                        onLocate = {
                            locating = vault.descriptor.id
                            locate.launch(arrayOf("*/*"))
                        },
                        onRename = { renaming = vault.descriptor },
                        onForget = { forgetting = vault.descriptor },
                    )
                }
            }
            if (rules.canAdd) {
                KeepButton(
                    stringResource(R.string.hub_create),
                    onCreate,
                    style = KeepButtonStyle.Action,
                    icon = VazieIcons.Add,
                    enabled = !state.checking,
                )
                KeepButton(
                    stringResource(R.string.hub_open_file),
                    { picker.launch(arrayOf("*/*")) },
                    style = KeepButtonStyle.Tonal,
                    icon = VazieIcons.File,
                    enabled = !state.checking,
                    loading = state.checking,
                )
            } else {
                KeepCard {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        KeepBadge(stringResource(R.string.totp_locked_badge), tone = KeepBadgeTone.Accent)
                        Text(stringResource(R.string.hub_more_vaults_title), style = KeepDesign.type.title, color = colors.text)
                        Text(
                            stringResource(if (rules.editable == null) R.string.hub_more_vaults_one else R.string.hub_more_vaults_several),
                            style = KeepDesign.type.body,
                            color = colors.textSecondary,
                        )
                        KeepButton(stringResource(R.string.totp_locked_action), onGetPlus, style = KeepButtonStyle.Tonal, size = KeepButtonSize.Medium)
                    }
                }
            }
            state.error?.let { error ->
                ErrorBanner(
                    stringResource(
                        when (error) {
                            SelectError.NotAVault -> R.string.error_not_a_vault
                            SelectError.FileUnavailable -> R.string.error_file_unavailable
                            SelectError.NeedsPlus -> R.string.hub_more_vaults_title
                        },
                    ),
                )
            }
            if (state.relocateFailed) KeepBanner(stringResource(R.string.hub_locate_failed), tone = KeepBannerTone.Error)
            Text(
                stringResource(R.string.chooser_footer, coreVersion),
                style = KeepDesign.type.mono,
                color = colors.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
    renaming?.let { vault ->
        val field = rememberTextFieldState(vault.name)
        KeepDialog(
            onDismissRequest = { renaming = null },
            title = stringResource(R.string.hub_rename_title),
            message = stringResource(R.string.hub_rename_message),
            content = { KeepTextField(field, label = stringResource(R.string.create_name_label)) },
            actions = {
                KeepDialogAction(stringResource(R.string.action_cancel), { renaming = null })
                KeepDialogAction(stringResource(R.string.action_save), {
                    viewModel.rename(vault.id, field.text.toString())
                    renaming = null
                })
            },
        )
    }
    forgetting?.let { vault ->
        KeepDialog(
            onDismissRequest = { forgetting = null },
            title = stringResource(R.string.hub_forget_title, vault.name),
            message = stringResource(R.string.hub_forget_message),
            actions = {
                KeepDialogAction(stringResource(R.string.action_cancel), { forgetting = null })
                KeepDialogAction(stringResource(R.string.hub_forget), {
                    viewModel.forget(vault.id)
                    forgetting = null
                }, style = KeepButtonStyle.Caution)
            },
        )
    }
}

@Composable
private fun VaultHubCard(vault: HubVault, role: Int?, onOpen: () -> Unit, onLocate: () -> Unit, onRename: () -> Unit, onForget: () -> Unit) {
    val colors = KeepDesign.colors
    val descriptor = vault.descriptor
    val missing = vault.available == false
    var menu by remember { mutableStateOf(false) }
    val subtitle = if (descriptor.lastUsedAt > 0) {
        stringResource(R.string.hub_last_opened, DateUtils.getRelativeTimeSpanString(descriptor.lastUsedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
    } else {
        stringResource(R.string.hub_never_opened)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(KeepShapes.card)
            .background(colors.surfaceList),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (missing) Modifier else Modifier.clickable(role = Role.Button, onClick = onOpen))
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Monogram(descriptor.name, size = 44.dp, radius = 13.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(descriptor.name, style = KeepDesign.type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(descriptor.fileName, style = KeepDesign.type.rowSubtitle, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(subtitle, style = KeepDesign.type.caption, color = colors.textTertiary)
                    if (descriptor.keyFileUri != null) KeepBadge(stringResource(R.string.hub_with_key_file), icon = VazieIcons.Key)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (vault.active && !missing) KeepBadge(stringResource(R.string.hub_current), tone = KeepBadgeTone.Accent)
                    if (role != null) KeepBadge(stringResource(role), icon = if (role == R.string.hub_read_only) VazieIcons.Lock else VazieIcons.Pencil)
                }
                if (missing) KeepBadge(stringResource(R.string.hub_unavailable), icon = VazieIcons.Warning, tone = KeepBadgeTone.Caution)
            }
            androidx.compose.foundation.layout.Box {
                KeepIconButton(VazieIcons.More, stringResource(R.string.hub_actions, descriptor.name), { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.hub_rename)) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.hub_locate)) }, onClick = { menu = false; onLocate() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.hub_forget)) }, onClick = { menu = false; onForget() })
                }
            }
        }
        if (missing) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.hub_unavailable_body), style = KeepDesign.type.rowSubtitle, color = colors.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeepButton(stringResource(R.string.hub_locate), onLocate, style = KeepButtonStyle.Tonal, size = app.vazie.keep.ui.designsystem.KeepButtonSize.Pill, fullWidth = false)
                    KeepButton(stringResource(R.string.hub_forget), onForget, style = KeepButtonStyle.Caution, size = app.vazie.keep.ui.designsystem.KeepButtonSize.Pill, fullWidth = false)
                }
            }
        }
    }
}
