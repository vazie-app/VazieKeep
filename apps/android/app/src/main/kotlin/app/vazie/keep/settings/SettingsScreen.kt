package app.vazie.keep.settings

import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.vazie.keep.BuildConfig
import app.vazie.keep.R
import app.vazie.keep.account.SettingsAccount
import app.vazie.keep.autofill.AutofillStatus
import app.vazie.keep.autofill.AutofillSystem
import app.vazie.keep.biometric.BiometricAvailability
import app.vazie.keep.biometric.BiometricStatus
import app.vazie.keep.biometric.BiometricUnlock
import app.vazie.keep.biometric.EnableError
import app.vazie.keep.biometric.EnableResult
import app.vazie.keep.biometric.PromptText
import app.vazie.keep.data.VaultState
import app.vazie.keep.links.AppLink
import app.vazie.keep.links.LinkSection
import app.vazie.keep.links.ShippedLinks
import app.vazie.keep.links.inSection
import app.vazie.keep.plus.CurrentPlus
import app.vazie.keep.review.openFirst
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
import app.vazie.keep.ui.designsystem.KeepPlusBadge
import app.vazie.keep.ui.designsystem.KeepScreen
import app.vazie.keep.ui.designsystem.KeepSectionLabel
import app.vazie.keep.ui.designsystem.KeepSettingRow
import app.vazie.keep.ui.designsystem.KeepShapes
import app.vazie.keep.ui.designsystem.KeepSwitch
import app.vazie.keep.ui.designsystem.KeepTopBar
import app.vazie.keep.ui.icons.VazieIcons
import app.vazie.keep.update.Release
import app.vazie.keep.update.UpdateState
import app.vazie.keep.update.downloadLinks
import java.util.Date
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: SecuritySettingsRepository,
    val settings: StateFlow<SecuritySettings>,
    private val biometrics: BiometricUnlock,
    val vaultState: StateFlow<VaultState>,
    private val icons: LauncherIconManager,
    private val autofill: AutofillSystem,
) : ViewModel() {
    private val mutableAutofill = MutableStateFlow(autofill.status())
    val autofillStatus: StateFlow<AutofillStatus> = mutableAutofill.asStateFlow()

    fun autofillIntent() = autofill.enableIntent()

    fun refreshAutofill() {
        mutableAutofill.value = autofill.status()
    }

    val biometricStatus: StateFlow<BiometricStatus> = biometrics.status

    val appIcon: StateFlow<LauncherIcon> = icons.current

    val appIconPlates: StateFlow<Map<AppIcon, IconPlate>> = icons.plates

    private val mutableIconFailed = MutableStateFlow(false)
    val iconFailed: StateFlow<Boolean> = mutableIconFailed.asStateFlow()

    fun selectIcon(icon: AppIcon) {
        viewModelScope.launch { mutableIconFailed.value = !icons.select(icon) }
    }

    fun selectPlate(plate: IconPlate) {
        viewModelScope.launch { mutableIconFailed.value = !icons.select(plate) }
    }

    private val mutableBiometricError = MutableStateFlow<EnableError?>(null)
    val biometricError: StateFlow<EnableError?> = mutableBiometricError.asStateFlow()

    private val mutableBusy = MutableStateFlow(false)
    val biometricBusy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    init {
        viewModelScope.launch { biometrics.refresh() }
    }

    fun setBiometric(enabled: Boolean, text: PromptText) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        mutableBiometricError.value = null
        viewModelScope.launch {
            try {
                if (enabled) {
                    (biometrics.enable(text) as? EnableResult.Failure)?.let { failure ->
                        mutableBiometricError.value = failure.error.takeIf { it != EnableError.Cancelled }
                    }
                } else {
                    biometrics.disable()
                }
            } finally {
                mutableBusy.value = false
            }
        }
    }

    fun setAutoLock(value: AutoLockTimeout) = update { repository.setAutoLockTimeout(value) }

    fun setLockOnDeviceLock(value: Boolean) = update { repository.setLockOnDeviceLock(value) }

    fun setClipboard(value: ClipboardTimeout) = update { repository.setClipboardTimeout(value) }

    fun setScreenshotsAllowed(value: Boolean) = update { repository.setScreenshotsAllowed(value) }

    fun setTheme(value: ThemeMode) = update { repository.setThemeMode(value) }

    fun setAutofillSave(value: Boolean) = update { repository.setAutofillSaveOffers(value) }

    fun setSiteIconsAutomatic(value: Boolean) = update { repository.setSiteIconsAutomatic(value) }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

private enum class Picker { AutoLock, Clipboard }

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    coreVersion: String,
    account: SettingsAccount,
    onBack: () -> Unit,
    onAccount: () -> Unit = {},
    onPlus: () -> Unit = {},
    onAbout: () -> Unit = {},
    onAppearance: () -> Unit = {},
    onGuides: () -> Unit = {},
    onFaq: () -> Unit = {},
    feedback: AppLink? = ShippedLinks.inSection(LinkSection.FEEDBACK).firstOrNull(),
    support: AppLink? = ShippedLinks.supportLink(),
    onCheckForUpdates: suspend () -> UpdateState? = { null },
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val biometric by viewModel.biometricStatus.collectAsStateWithLifecycle()
    val biometricError by viewModel.biometricError.collectAsStateWithLifecycle()
    val biometricBusy by viewModel.biometricBusy.collectAsStateWithLifecycle()
    val vault by viewModel.vaultState.collectAsStateWithLifecycle()
    val appIcon by viewModel.appIcon.collectAsStateWithLifecycle()
    val autofillStatus by viewModel.autofillStatus.collectAsStateWithLifecycle()
    val enableAutofill = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.refreshAutofill() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshAutofill() }
    val enablePrompt = PromptText(
        title = stringResource(R.string.biometric_enable_prompt_title),
        subtitle = stringResource(R.string.biometric_enable_prompt_subtitle),
        negative = stringResource(R.string.action_cancel),
    )
    var picker by remember { mutableStateOf<Picker?>(null) }
    var confirmScreenshots by remember { mutableStateOf(false) }
    val colors = KeepDesign.colors
    val uriHandler = LocalUriHandler.current
    var checking by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf<UpdateCheck?>(null) }
    val scope = rememberCoroutineScope()
    KeepScreen {
        Column(Modifier.fillMaxSize()) {
            KeepTopBar(onBack = onBack)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    stringResource(R.string.settings_title),
                    style = KeepDesign.type.headline,
                    color = colors.text,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp).semantics { heading() },
                )

                // Vazie Account: the account, and Keep+ under it with its mark, as Vazie VPN shows VPN Plus.
                KeepSectionLabel(stringResource(R.string.settings_account_section))
                KeepCard {
                    KeepSettingRow(
                        title = if (account.signedIn) account.email ?: stringResource(R.string.account_row_signed_in) else stringResource(R.string.account_title),
                        subtitle = stringResource(if (account.signedIn) R.string.account_row_open else R.string.account_row_signed_out),
                        chevron = true,
                        onClick = onAccount,
                    )
                    KeepHairline()
                    KeepSettingRow(
                        title = stringResource(R.string.settings_plus_title),
                        subtitle = plusLine(account.plus),
                        subtitleMaxLines = 1,
                        leading = { KeepPlusBadge() },
                        chevron = true,
                        onClick = onPlus,
                    )
                }

                KeepSectionLabel(stringResource(R.string.settings_look_and_feel))
                KeepCard {
                    KeepSettingRow(
                        title = stringResource(R.string.settings_appearance),
                        subtitle = themeLabel(settings.themeMode) + " · " + stringResource(appIcon.icon.label),
                        chevron = true,
                        onClick = onAppearance,
                    )
                }

                // What only Keep has: the vault's security, AutoFill and passkeys, entries.
                KeepSectionLabel(stringResource(R.string.settings_security_section))
                KeepCard {
                    ValueRow(
                        title = stringResource(R.string.settings_auto_lock),
                        value = autoLockLabel(settings.autoLockTimeout),
                        onClick = { picker = Picker.AutoLock },
                    )
                    KeepHairline()
                    ValueRow(
                        title = stringResource(R.string.settings_clipboard),
                        value = clipboardLabel(settings.clipboardTimeout),
                        onClick = { picker = Picker.Clipboard },
                    )
                    KeepHairline()
                    SwitchRow(
                        title = stringResource(R.string.settings_lock_on_device_lock),
                        description = stringResource(R.string.settings_lock_on_device_lock_hint),
                        checked = settings.lockOnDeviceLock,
                        onChange = viewModel::setLockOnDeviceLock,
                    )
                    KeepHairline()
                    SwitchRow(
                        title = stringResource(R.string.settings_screenshots),
                        description = stringResource(R.string.settings_screenshots_hint),
                        checked = settings.screenshotsAllowed,
                        onChange = { allow -> if (allow) confirmScreenshots = true else viewModel.setScreenshotsAllowed(false) },
                    )
                    KeepHairline()
                    BiometricRow(
                        status = biometric,
                        vault = vault,
                        busy = biometricBusy,
                        onChange = { viewModel.setBiometric(it, enablePrompt) },
                    )
                }
                if (settings.clipboardTimeout == ClipboardTimeout.Never) {
                    KeepBanner(stringResource(R.string.settings_clipboard_never_warning), tone = KeepBannerTone.Caution, modifier = Modifier.padding(top = 8.dp))
                }
                biometricError?.let { error ->
                    KeepBanner(stringResource(error.message()), tone = KeepBannerTone.Error, modifier = Modifier.padding(top = 8.dp))
                }

                KeepSectionLabel(stringResource(R.string.settings_autofill_section))
                KeepCard {
                    AutofillRow(autofillStatus, onEnable = { enableAutofill.launch(viewModel.autofillIntent()) })
                    if (autofillStatus != AutofillStatus.NotAvailable) {
                        KeepHairline()
                        SwitchRow(
                            title = stringResource(R.string.settings_autofill_save),
                            description = stringResource(R.string.settings_autofill_save_hint),
                            checked = settings.autofillSaveOffers,
                            onChange = viewModel::setAutofillSave,
                        )
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val context = LocalContext.current
                        KeepHairline()
                        KeepSettingRow(
                            title = stringResource(R.string.settings_passkeys),
                            subtitle = stringResource(R.string.settings_passkeys_hint),
                            chevron = true,
                            onClick = { context.openPasskeySettings() },
                        )
                    }
                }

                KeepSectionLabel(stringResource(R.string.settings_entries_section))
                KeepCard {
                    SwitchRow(
                        title = stringResource(R.string.settings_site_icons),
                        description = stringResource(R.string.settings_site_icons_hint),
                        checked = settings.siteIconsAutomatic,
                        onChange = viewModel::setSiteIconsAutomatic,
                    )
                }

                KeepSectionLabel(stringResource(R.string.settings_help_section))
                KeepCard {
                    KeepSettingRow(
                        title = stringResource(R.string.settings_guides_row),
                        subtitle = stringResource(R.string.settings_guides_row_body),
                        chevron = true,
                        onClick = onGuides,
                    )
                    KeepHairline()
                    KeepSettingRow(
                        title = stringResource(R.string.settings_faq_row),
                        subtitle = stringResource(R.string.settings_faq_row_body),
                        chevron = true,
                        onClick = onFaq,
                    )
                    // The words and the address are the admin panel's, as everywhere in Keep: the first FEEDBACK link and
                    // the first `mailto:` link. No published link, no row: nothing here would open.
                    if (feedback != null) {
                        KeepHairline()
                        LinkRow(feedback, russian(), chevron = true)
                    }
                    if (support != null) {
                        KeepHairline()
                        LinkRow(support, russian(), chevron = true)
                    }
                }

                KeepSectionLabel(stringResource(R.string.settings_about_section))
                KeepCard {
                    KeepSettingRow(
                        title = stringResource(R.string.about_row_title),
                        subtitle = stringResource(R.string.settings_about_row_body),
                        chevron = true,
                        onClick = onAbout,
                    )
                    KeepHairline()
                    // An action, not a place: no chevron. The subtitle shows only while a check runs.
                    KeepSettingRow(
                        title = stringResource(R.string.settings_check_updates),
                        subtitle = if (checking) stringResource(R.string.settings_check_updates_checking) else null,
                        onClick = {
                            if (!checking) {
                                checking = true
                                scope.launch {
                                    checked = when (val result = onCheckForUpdates()) {
                                        null -> UpdateCheck.Failed
                                        UpdateState.Current -> UpdateCheck.Current
                                        is UpdateState.Available -> UpdateCheck.Available(result.release)
                                        is UpdateState.Required -> UpdateCheck.Available(result.release)
                                    }
                                    checking = false
                                }
                            }
                        },
                    )
                }

                SettingsFooter(coreVersion)
            }
        }
    }
    checked?.let { result ->
        val context = LocalContext.current
        KeepDialog(
            onDismissRequest = { checked = null },
            title = stringResource(R.string.settings_check_updates),
            message = when (result) {
                UpdateCheck.Current -> stringResource(R.string.settings_update_current)
                UpdateCheck.Failed -> stringResource(R.string.settings_update_failed)
                is UpdateCheck.Available -> stringResource(R.string.settings_update_available, result.release.latestVersionName)
            },
        ) {
            if (result is UpdateCheck.Available) {
                KeepDialogAction(stringResource(R.string.update_download), { openFirst(context, result.release.downloadLinks(context.packageName)); checked = null }, KeepButtonStyle.Action)
            }
            KeepDialogAction(stringResource(R.string.action_ok), { checked = null })
        }
    }
    when (picker) {
        Picker.AutoLock -> ChoiceDialog(
            title = stringResource(R.string.settings_auto_lock),
            options = AutoLockTimeout.entries,
            selected = settings.autoLockTimeout,
            label = { autoLockLabel(it) },
            onSelect = { viewModel.setAutoLock(it); picker = null },
            onDismiss = { picker = null },
        )
        Picker.Clipboard -> ChoiceDialog(
            title = stringResource(R.string.settings_clipboard),
            options = ClipboardTimeout.entries,
            selected = settings.clipboardTimeout,
            label = { clipboardLabel(it) },
            onSelect = { viewModel.setClipboard(it); picker = null },
            onDismiss = { picker = null },
            footer = stringResource(R.string.settings_clipboard_never_warning),
        )
        null -> Unit
    }
    if (confirmScreenshots) {
        KeepDialog(
            onDismissRequest = { confirmScreenshots = false },
            title = stringResource(R.string.settings_screenshots),
            message = stringResource(R.string.settings_screenshots_warning),
        ) {
            KeepDialogAction(stringResource(R.string.settings_screenshots_confirm), { viewModel.setScreenshotsAllowed(true); confirmScreenshots = false }, KeepButtonStyle.Caution)
            KeepDialogAction(stringResource(R.string.action_cancel), { confirmScreenshots = false }, KeepButtonStyle.Action)
        }
    }
}

@Composable
private fun ValueRow(title: String, value: String, onClick: () -> Unit) {
    KeepSettingRow(title = title, value = value, chevron = true, onClick = onClick)
}

@Composable
private fun BiometricRow(status: BiometricStatus, vault: VaultState, busy: Boolean, onChange: (Boolean) -> Unit) {
    val unlocked = vault as? VaultState.Unlocked
    val checked = status is BiometricStatus.On || status is BiometricStatus.NeedsSetup
    val canEnable = status !is BiometricStatus.Unavailable && unlocked?.materialRetained == true
    val description = when {
        status is BiometricStatus.Unavailable -> when (status.reason) {
            BiometricAvailability.NoneEnrolled -> R.string.settings_biometric_none_enrolled
            BiometricAvailability.NoHardware -> R.string.settings_biometric_no_hardware
            else -> R.string.settings_biometric_unavailable
        }
        status is BiometricStatus.On && status.keyFileRequired -> R.string.settings_biometric_on_key_file
        status is BiometricStatus.On -> R.string.settings_biometric_on
        status is BiometricStatus.NeedsSetup -> R.string.settings_biometric_needs_setup
        unlocked != null && !unlocked.usedPassword -> R.string.settings_biometric_key_file_only
        canEnable -> R.string.settings_biometric_ready
        else -> R.string.settings_biometric_unlock_first
    }
    SwitchRow(
        title = stringResource(R.string.settings_biometric),
        description = stringResource(description),
        checked = checked,
        enabled = !busy && (checked || canEnable),
        onChange = onChange,
    )
}

private fun EnableError.message() = when (this) {
    EnableError.NotUnlocked, EnableError.NoPassword -> R.string.settings_biometric_unlock_first
    EnableError.NotAvailable -> R.string.settings_biometric_unavailable
    EnableError.LockedOut -> R.string.biometric_locked_out
    EnableError.Cancelled, EnableError.Failed -> R.string.biometric_enable_failed
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeepSettingRow(title = title, subtitle = description, modifier = Modifier.weight(1f))
        Box(Modifier.padding(end = 16.dp)) { KeepSwitch(checked = checked, enabled = enabled) }
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    footer: String? = null,
) {
    val colors = KeepDesign.colors
    val uriHandler = LocalUriHandler.current
    KeepDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                options.forEach { option ->
                    val isSelected = option == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(KeepShapes.chip)
                            .background(if (isSelected) colors.accentContainer else androidx.compose.ui.graphics.Color.Transparent)
                            .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option) })
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label(option), style = KeepDesign.type.body, color = if (isSelected) colors.onAccentContainer else colors.text, modifier = Modifier.weight(1f))
                        if (isSelected) Icon(VazieIcons.Check, contentDescription = null, tint = colors.onAccentContainer, modifier = Modifier.size(20.dp))
                    }
                }
                if (footer != null) {
                    Text(footer, style = KeepDesign.type.caption, color = colors.textSecondary, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
                }
            }
        },
    ) {
        KeepDialogAction(stringResource(R.string.action_cancel), onDismiss)
    }
}

@Composable
private fun autoLockLabel(value: AutoLockTimeout): String =
    if (value == AutoLockTimeout.Immediately) stringResource(R.string.timeout_immediately) else durationLabel(value.duration)

@Composable
private fun clipboardLabel(value: ClipboardTimeout): String =
    value.duration?.let { durationLabel(it) } ?: stringResource(R.string.timeout_never)

@Composable
internal fun themeLabel(value: ThemeMode): String = stringResource(
    when (value) {
        ThemeMode.System -> R.string.theme_system
        ThemeMode.Light -> R.string.theme_light
        ThemeMode.Dark -> R.string.theme_dark
    },
)

@Composable
private fun durationLabel(duration: Duration): String {
    val minutes = duration.inWholeMinutes.toInt()
    val seconds = duration.inWholeSeconds.toInt()
    return if (minutes > 0 && seconds % 60 == 0) {
        pluralStringResource(R.plurals.duration_minutes, minutes, minutes)
    } else {
        pluralStringResource(R.plurals.duration_seconds, seconds, seconds)
    }
}

@Composable
private fun AutofillRow(status: AutofillStatus, onEnable: () -> Unit) {
    val colors = KeepDesign.colors
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        KeepSettingRow(
            title = stringResource(R.string.settings_autofill),
            subtitle = stringResource(
                when (status) {
                    AutofillStatus.Enabled -> R.string.settings_autofill_enabled
                    AutofillStatus.Disabled -> R.string.settings_autofill_disabled
                    AutofillStatus.NotAvailable -> R.string.settings_autofill_unavailable
                },
            ),
            modifier = Modifier.semantics(mergeDescendants = true) {},
        )
        Text(
            stringResource(if (status == AutofillStatus.Enabled) R.string.autofill_enabled_hint else R.string.settings_autofill_hint),
            style = KeepDesign.type.caption,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
        if (status == AutofillStatus.Disabled) {
            KeepButton(stringResource(R.string.autofill_enable), onEnable, size = KeepButtonSize.Medium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
        }
    }
}

/** Keep+ in one line, as Vazie VPN shows VPN Plus under the account: «Дополнительные возможности» without it,
 * «Активна до …» with it («Активна» with no end date, «Активна навсегда» for a lifetime). */
@Composable
private fun plusLine(plus: CurrentPlus?): String {
    val context = LocalContext.current
    return when {
        plus == null -> stringResource(R.string.plus_row_body)
        plus.lifetime -> stringResource(R.string.plus_row_lifetime)
        plus.untilSeconds == null -> stringResource(R.string.plus_row_active)
        else -> stringResource(R.string.plus_manage_until, DateFormat.getMediumDateFormat(context).format(Date(plus.untilSeconds * 1000)))
    }
}

/**
 * The link Settings opens for «Contact support»: the first published `mailto:` link. Chosen by what it is, not by its
 * key — the operator may rename keys in the admin panel. «Feedback» is likewise the first link of the FEEDBACK card.
 */
fun List<AppLink>.supportLink(): AppLink? = firstOrNull { it.url.startsWith("mailto:") }

private sealed interface UpdateCheck {
    data object Current : UpdateCheck
    data object Failed : UpdateCheck
    data class Available(val release: Release) : UpdateCheck
}

/** The app, its version and its core, and who made it. Quiet, centred, and not a button. */
@Composable
private fun SettingsFooter(coreVersion: String) {
    val colors = KeepDesign.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            stringResource(R.string.settings_footer, BuildConfig.VERSION_NAME) + " · " + stringResource(R.string.about_core_version) + " " + coreVersion,
            style = KeepDesign.type.caption,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Text(stringResource(R.string.settings_footer_made_by), style = KeepDesign.type.caption, color = colors.textSecondary, textAlign = TextAlign.Center)
    }
}

private fun android.content.Context.openPasskeySettings() {
    val settings = android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
    val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        android.content.Intent(android.provider.Settings.ACTION_CREDENTIAL_PROVIDER)
            .setData(android.net.Uri.fromParts("package", packageName, null))
    } else {
        settings
    }
    try {
        startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        startActivity(settings)
    }
}
