package app.vazie.keep.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.snapshotFlow
import app.vazie.keep.ui.components.formatDate
import app.vazie.keep.data.AttachmentFile
import app.vazie.keep.ui.designsystem.KeepChip
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vazie.keep.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import app.vazie.keep.qr.QrImage
import app.vazie.keep.qr.QrScannerScreen
import app.vazie.keep.core.TotpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.vazie.keep.core.VaultEditException
import app.vazie.keep.generator.GeneratorPanel
import app.vazie.keep.save.SaveFlowHost
import app.vazie.keep.ui.components.EmptyState
import app.vazie.keep.ui.components.ErrorBanner
import app.vazie.keep.ui.components.FieldAction
import app.vazie.keep.ui.components.PasswordStrength
import app.vazie.keep.ui.components.ProtectedTextField
import app.vazie.keep.ui.designsystem.KeepButton
import app.vazie.keep.ui.designsystem.KeepButtonSize
import app.vazie.keep.ui.designsystem.KeepButtonStyle
import app.vazie.keep.ui.designsystem.KeepCard
import app.vazie.keep.ui.designsystem.KeepDesign
import app.vazie.keep.ui.designsystem.KeepDialog
import app.vazie.keep.ui.designsystem.KeepDialogAction
import app.vazie.keep.ui.designsystem.KeepIconButton
import app.vazie.keep.ui.designsystem.KeepScreen
import app.vazie.keep.ui.designsystem.KeepSectionLabel
import app.vazie.keep.ui.designsystem.KeepShapes
import app.vazie.keep.ui.designsystem.KeepSnackbarHost
import app.vazie.keep.ui.designsystem.KeepStrengthMeter
import app.vazie.keep.ui.designsystem.KeepSwitch
import app.vazie.keep.ui.designsystem.KeepTextField
import app.vazie.keep.ui.designsystem.KeepTopBar
import app.vazie.keep.ui.designsystem.Monogram
import app.vazie.keep.ui.icons.VazieIcons
import app.vazie.keep.vault.GroupChoice

@Composable
fun EntryEditorScreen(viewModel: EntryEditorViewModel, suggestedCopyName: String, saveInProgress: Boolean, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val generator by viewModel.generator.collectAsStateWithLifecycle()
    val canEdit by viewModel.canEdit.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var generatorOpen by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var groupPicker by remember { mutableStateOf(false) }
    var totpDialog by remember { mutableStateOf(false) }
    var totpError by remember { mutableStateOf<Int?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var passwordVisible by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { if (it is EditorEvent.Saved) onClose() }
    }
    LaunchedEffect(viewModel) { viewModel.hideSignal.collect { passwordVisible = false } }
    val ready = state as? EditorUiState.Ready
    val saving = ready?.saving == true
    val back = {
        if (viewModel.hasChanges()) confirmDiscard = true else onClose()
    }
    BackHandler(enabled = !saving, onBack = back)
    val colors = KeepDesign.colors
    KeepScreen {
        Column(Modifier.fillMaxSize()) {
            KeepTopBar(
                title = stringResource(if (ready?.isNew == false) R.string.editor_title_edit else R.string.editor_title_new),
                onBack = if (saving) null else back,
                actions = {
                    KeepButton(
                        stringResource(R.string.action_save),
                        viewModel::save,
                        size = KeepButtonSize.Pill,
                        enabled = ready != null && !saving && canEdit,
                        fullWidth = false,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                },
            )
            if (saving && saveInProgress) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(KeepShapes.pill), color = colors.accent, trackColor = colors.surfaceRaised)
            }
            when (val s = state) {
                EditorUiState.Loading, EditorUiState.Locked -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }
                EditorUiState.NotFound -> EmptyState(
                    VazieIcons.Key,
                    stringResource(R.string.entry_missing_title),
                    stringResource(R.string.entry_missing_message),
                )
                is EditorUiState.Ready -> EditorForm(
                    viewModel = viewModel,
                    state = s,
                    passwordVisible = passwordVisible,
                    onTogglePassword = { passwordVisible = !passwordVisible },
                    onGenerator = {
                        viewModel.openGenerator()
                        generatorOpen = true
                    },
                    onPickGroup = { groupPicker = true },
                    onAddTotp = if (viewModel.canAddTotp) ({ totpDialog = true }) else null,
                )
            }
        }
        KeepSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    if (generatorOpen) {
        GeneratorSheet(
            state = generator,
            onOptions = viewModel::setGeneratorOptions,
            onRegenerate = viewModel::generate,
            onReveal = viewModel::revealGenerated,
            onHide = viewModel::hideGenerated,
            onUse = {
                viewModel.useGenerated()
                generatorOpen = false
            },
            onDismiss = {
                viewModel.closeGenerator()
                generatorOpen = false
            },
        )
    }
    val context = LocalContext.current
    val submitTotp = { input: String ->
        val reason = viewModel.addTotp(input)
        totpError = reason?.message()
        if (reason == null) totpDialog = false
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.Default) { QrImage.decode(context, uri) }
                if (text == null) totpError = R.string.totp_qr_not_found else submitTotp(text)
            }
        }
    }
    if (totpDialog) {
        TotpDialog(
            error = totpError,
            onAdd = submitTotp,
            onScan = { scanning = true },
            onPickImage = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onDismiss = { totpDialog = false; totpError = null },
        )
    }
    if (scanning) {
        QrScannerScreen(
            onResult = { text -> scanning = false; submitTotp(text) },
            onDismiss = { scanning = false },
        )
    }
    if (groupPicker && ready != null) {
        GroupPickerDialog(
            title = stringResource(R.string.field_group),
            groups = ready.groups,
            selected = ready.group,
            onSelect = {
                viewModel.selectGroup(it)
                groupPicker = false
            },
            onDismiss = { groupPicker = false },
        )
    }
    if (confirmDiscard) {
        KeepDialog(
            onDismissRequest = { confirmDiscard = false },
            title = stringResource(R.string.editor_discard_title),
            message = stringResource(R.string.editor_discard_message),
        ) {
            KeepDialogAction(stringResource(R.string.editor_keep_editing), { confirmDiscard = false }, KeepButtonStyle.Action)
            KeepDialogAction(stringResource(R.string.editor_discard), { confirmDiscard = false; onClose() }, KeepButtonStyle.Caution)
        }
    }
    SaveFlowHost(viewModel.saveFlow, suggestedCopyName, snackbar)
}

@Composable
private fun EditorForm(
    viewModel: EntryEditorViewModel,
    state: EditorUiState.Ready,
    passwordVisible: Boolean,
    onTogglePassword: () -> Unit,
    onGenerator: () -> Unit,
    onPickGroup: () -> Unit,
    onAddTotp: (() -> Unit)?,
) {
    val fields = viewModel.fields
    val enabled = !state.saving
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.invalid?.let { ErrorBanner(stringResource(it.message())) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Monogram(fields.title.text.toString().ifBlank { "·" }, size = 56.dp, radius = 18.dp, textSize = 23f)
            Box(Modifier.weight(1f)) {
                TextInput(fields.title, stringResource(R.string.field_title), enabled, imeAction = ImeAction.Next, height = 56, showLabel = false)
            }
        }
        TextInput(fields.username, stringResource(R.string.field_username), enabled, imeAction = ImeAction.Next)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ProtectedTextField(
                state = fields.password,
                label = stringResource(R.string.field_password),
                visible = passwordVisible,
                onToggleVisible = onTogglePassword,
                enabled = enabled,
                imeAction = ImeAction.Next,
                actions = listOf(FieldAction(VazieIcons.Dice, stringResource(R.string.generator_open), onGenerator)),
            )
            val estimate = PasswordStrength.estimate(fields.password.text)
            if (estimate != null) {
                KeepStrengthMeter(
                    estimate.level,
                    stringResource(estimate.label),
                    bits = pluralStringResource(R.plurals.strength_bits, estimate.bits, estimate.bits),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        TextInput(fields.url, stringResource(R.string.field_url), enabled, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, leadingIcon = VazieIcons.Globe)
        GroupField(state.groups, state.group, enabled, onPickGroup)
        TextInput(fields.notes, stringResource(R.string.field_notes), enabled, multiline = true)
        val knownTags by viewModel.knownTags.collectAsStateWithLifecycle()
        TagsEditor(fields.tags, fields.tagInput, knownTags, enabled, onAdd = viewModel::addTag, onRemove = viewModel::removeTag)
        ExpiryField(fields.expiresAt.value, enabled, onChange = viewModel::setExpiry)
        FavoriteRow(fields.favorite.value, enabled, onChange = viewModel::setFavorite)
        val attachmentProblem by viewModel.attachmentProblem.collectAsStateWithLifecycle()
        AttachmentsEditor(fields.attachments, enabled, viewModel.canAddAttachments, attachmentProblem, onPick = viewModel::addAttachmentFrom, onRemove = viewModel::removeAttachment)
        KeepSectionLabel(stringResource(R.string.editor_custom_fields))
        fields.custom.forEach { field ->
            CustomFieldEditor(field, enabled, onRemove = { viewModel.removeField(field.key) })
        }
        KeepButton(
            stringResource(R.string.editor_add_field),
            viewModel::addField,
            style = KeepButtonStyle.Tonal,
            size = KeepButtonSize.Medium,
            icon = VazieIcons.Add,
            enabled = enabled,
        )
        if (onAddTotp != null) {
            KeepButton(
                stringResource(R.string.editor_add_totp),
                onAddTotp,
                style = KeepButtonStyle.Tonal,
                size = KeepButtonSize.Medium,
                icon = VazieIcons.Add,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun TotpDialog(error: Int?, onAdd: (String) -> Unit, onScan: () -> Unit, onPickImage: () -> Unit, onDismiss: () -> Unit) {
    val input = remember { TextFieldState() }
    var visible by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { input.edit { replace(0, length, "") } } }
    KeepDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.totp_dialog_title),
        message = stringResource(R.string.totp_dialog_hint),
        content = {
            ProtectedTextField(
                state = input,
                label = stringResource(R.string.totp_dialog_field),
                visible = visible,
                onToggleVisible = { visible = !visible },
                error = error?.let { stringResource(it) },
                imeAction = ImeAction.Done,
                onDone = { onAdd(input.text.toString()) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeepButton(stringResource(R.string.totp_scan), onScan, style = KeepButtonStyle.Tonal, size = KeepButtonSize.Medium, fullWidth = false)
                KeepButton(stringResource(R.string.totp_from_image), onPickImage, style = KeepButtonStyle.Tonal, size = KeepButtonSize.Medium, fullWidth = false)
            }
        },
    ) {
        KeepDialogAction(stringResource(R.string.totp_dialog_add), { onAdd(input.text.toString()) }, KeepButtonStyle.Action)
        KeepDialogAction(stringResource(R.string.action_cancel), onDismiss)
    }
}

private fun TotpException.Reason.message() = when (this) {
    TotpException.Reason.Empty, TotpException.Reason.MissingSecret -> R.string.totp_error_empty
    TotpException.Reason.InvalidSecret, TotpException.Reason.InvalidText -> R.string.totp_error_secret
    TotpException.Reason.UnsupportedType -> R.string.totp_error_type
    TotpException.Reason.InvalidDigits -> R.string.totp_error_digits
    TotpException.Reason.InvalidPeriod -> R.string.totp_error_period
    TotpException.Reason.InvalidAlgorithm -> R.string.totp_error_algorithm
    TotpException.Reason.NotEntitled -> R.string.totp_error_entitled
    TotpException.Reason.Migration -> R.string.totp_error_migration
    else -> R.string.totp_error_generic
}

@Composable
private fun TextInput(
    state: TextFieldState,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    multiline: Boolean = false,
    supporting: String? = null,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    height: Int = 60,
    showLabel: Boolean = true,
) {
    KeepTextField(
        state = state,
        label = if (showLabel) label else null,
        placeholder = if (showLabel) null else label,
        modifier = modifier,
        enabled = enabled,
        supporting = supporting,
        multiline = multiline,
        leadingIcon = leadingIcon,
        height = height,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
    )
}

@Composable
private fun GroupField(groups: List<GroupChoice>, selected: app.vazie.keep.core.GroupId, enabled: Boolean, onClick: () -> Unit) {
    val colors = KeepDesign.colors
    val label = stringResource(R.string.field_group)
    val path = groups.firstOrNull { it.id == selected }?.path.orEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(KeepShapes.field)
            .background(colors.surfaceField)
            .clickable(enabled = enabled, role = Role.DropdownList, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(VazieIcons.Folder, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = KeepDesign.type.badge, color = colors.textSecondary)
            Text(path, style = KeepDesign.type.rowTitle, color = colors.text)
        }
        Icon(VazieIcons.ChevronRight, contentDescription = null, tint = colors.textTertiary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsEditor(
    tags: List<String>,
    input: TextFieldState,
    known: List<String>,
    enabled: Boolean,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val colors = KeepDesign.colors
    val typed = input.text.toString()
    LaunchedEffect(input) {
        snapshotFlow { input.text.toString() }.collect { text -> if (text.contains(',') || text.contains(';')) onAdd(text) }
    }
    val suggestions = known
        .filter { tag -> tags.none { it == tag } && (typed.isBlank() || tag.contains(typed.trim(), ignoreCase = true)) }
        .take(MAX_TAG_SUGGESTIONS)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KeepTextField(
            state = input,
            label = stringResource(R.string.field_tags),
            placeholder = stringResource(R.string.editor_tag_placeholder),
            enabled = enabled,
            leadingIcon = VazieIcons.Tag,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
            onKeyboardAction = { onAdd(input.text.toString()) },
            trailing = if (typed.isNotBlank()) {
                { KeepIconButton(VazieIcons.Add, stringResource(R.string.editor_tag_add), { onAdd(input.text.toString()) }, enabled = enabled) }
            } else {
                null
            },
        )
        if (tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { tag ->
                    val remove = stringResource(R.string.editor_tag_remove, tag)
                    Row(
                        modifier = Modifier
                            .heightIn(min = 36.dp)
                            .clip(KeepShapes.chip)
                            .background(colors.accentContainer)
                            .clickable(enabled = enabled, role = Role.Button, onClickLabel = remove) { onRemove(tag) }
                            .padding(start = 12.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(tag, style = KeepDesign.type.label, color = colors.onAccentContainer)
                        Icon(VazieIcons.Close, contentDescription = null, tint = colors.onAccentContainer, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        if (suggestions.isNotEmpty() && enabled) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { tag -> KeepChip(tag, selected = false, onClick = { onAdd(tag) }, icon = VazieIcons.Add) }
            }
        }
    }
}

@Composable
private fun AttachmentsEditor(
    attachments: List<AttachmentItem>,
    enabled: Boolean,
    canAdd: Boolean,
    problem: AttachmentFile?,
    onPick: (String?) -> Unit,
    onRemove: (Long) -> Unit,
) {
    if (attachments.isEmpty() && !canAdd) return
    val colors = KeepDesign.colors
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> onPick(uri?.toString()) }
    KeepSectionLabel(stringResource(R.string.entry_section_attachments))
    if (attachments.isNotEmpty()) {
        KeepCard {
            attachments.forEachIndexed { index, item ->
                if (index > 0) app.vazie.keep.ui.designsystem.KeepHairline()
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(VazieIcons.Paperclip, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = KeepDesign.type.rowTitle, color = colors.text, maxLines = 2)
                        Text(android.text.format.Formatter.formatShortFileSize(context, item.size), style = KeepDesign.type.caption, color = colors.textSecondary)
                    }
                    KeepIconButton(VazieIcons.Trash, stringResource(R.string.attachment_remove, item.name), { onRemove(item.key) }, enabled = enabled, tint = colors.caution)
                }
            }
        }
    }
    when (problem) {
        AttachmentFile.TooLarge -> ErrorBanner(stringResource(R.string.attachment_too_large))
        AttachmentFile.Unavailable -> ErrorBanner(stringResource(R.string.attachment_unreadable))
        else -> Unit
    }
    if (canAdd) {
        KeepButton(
            stringResource(R.string.attachment_add),
            { picker.launch(arrayOf("*/*")) },
            style = KeepButtonStyle.Tonal,
            size = KeepButtonSize.Medium,
            icon = VazieIcons.Paperclip,
            enabled = enabled,
        )
    }
}

@Composable
private fun ExpiryField(expiresAt: Long?, enabled: Boolean, onChange: (Long?) -> Unit) {
    val colors = KeepDesign.colors
    var choosing by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(KeepShapes.field)
            .background(colors.surfaceField)
            .clickable(enabled = enabled, role = Role.Button) { choosing = true }
            .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(VazieIcons.Calendar, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(stringResource(R.string.field_expires), style = KeepDesign.type.badge, color = colors.textSecondary)
            Text(
                expiresAt?.let(::formatDate) ?: stringResource(R.string.editor_expires_never),
                style = KeepDesign.type.rowTitle,
                color = if (expiresAt == null) colors.textSecondary else colors.text,
            )
        }
        if (expiresAt != null) {
            KeepIconButton(VazieIcons.Close, stringResource(R.string.editor_expires_clear), { onChange(null) }, enabled = enabled)
        } else {
            Icon(VazieIcons.ChevronRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.padding(end = 10.dp))
        }
    }
    if (choosing) {
        KeepDialog(
            onDismissRequest = { choosing = false },
            title = stringResource(R.string.field_expires),
            content = {
                Column {
                    EXPIRY_PRESETS.forEach { (months, label) ->
                        ExpiryOption(stringResource(label)) {
                            onChange(endOfDay(LocalDate.now().plusMonths(months)))
                            choosing = false
                        }
                    }
                    ExpiryOption(stringResource(R.string.editor_expires_pick)) {
                        choosing = false
                        picking = true
                    }
                }
            },
        ) {
            KeepDialogAction(stringResource(R.string.action_cancel), { choosing = false })
        }
    }
    if (picking) ExpiryDatePicker(expiresAt, onPick = { onChange(it); picking = false }, onDismiss = { picking = false })
}

@Composable
private fun ExpiryOption(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(KeepShapes.chip)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = KeepDesign.type.body, color = KeepDesign.colors.text)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpiryDatePicker(current: Long?, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val colors = KeepDesign.colors
    val initial = (current?.let { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).toLocalDate() } ?: LocalDate.now().plusMonths(1))
        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val picker = rememberDatePickerState(initialSelectedDateMillis = initial)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            KeepButton(
                stringResource(R.string.action_done),
                { picker.selectedDateMillis?.let { onPick(endOfDay(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())) } },
                style = KeepButtonStyle.Text,
                size = KeepButtonSize.Pill,
                fullWidth = false,
                enabled = picker.selectedDateMillis != null,
            )
        },
        dismissButton = { KeepButton(stringResource(R.string.action_cancel), onDismiss, style = KeepButtonStyle.Text, size = KeepButtonSize.Pill, fullWidth = false) },
        colors = DatePickerDefaults.colors(containerColor = colors.surfaceRaised),
    ) {
        DatePicker(state = picker, colors = DatePickerDefaults.colors(containerColor = colors.surfaceRaised))
    }
}

@Composable
private fun FavoriteRow(favorite: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val colors = KeepDesign.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(KeepShapes.field)
            .background(colors.surfaceField)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!favorite) }
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (favorite) VazieIcons.StarFilled else VazieIcons.Star,
            contentDescription = null,
            tint = if (favorite) colors.accent else colors.textTertiary,
            modifier = Modifier.size(22.dp),
        )
        Text(stringResource(R.string.editor_favorite), style = KeepDesign.type.body, color = colors.text, modifier = Modifier.weight(1f))
        KeepSwitch(checked = favorite, enabled = enabled)
    }
}

private const val MAX_TAG_SUGGESTIONS = 8

private val EXPIRY_PRESETS = listOf(
    1L to R.string.editor_expires_month,
    3L to R.string.editor_expires_three_months,
    6L to R.string.editor_expires_half_year,
    12L to R.string.editor_expires_year,
)

private fun endOfDay(date: LocalDate): Long = date.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toEpochSecond()

@Composable
private fun CustomFieldEditor(field: CustomFieldState, enabled: Boolean, onRemove: () -> Unit) {
    var visible by remember(field.key) { mutableStateOf(false) }
    val protected by field.isProtected
    val colors = KeepDesign.colors
    val nameLabel = stringResource(R.string.editor_field_name)
    val valueLabel = stringResource(R.string.editor_field_value)
    KeepCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextInput(field.name, nameLabel, enabled, imeAction = ImeAction.Next)
            if (protected) {
                ProtectedTextField(
                    state = field.value,
                    label = valueLabel,
                    visible = visible,
                    onToggleVisible = { visible = !visible },
                    enabled = enabled,
                    imeAction = ImeAction.Next,
                )
            } else {
                TextInput(field.value, valueLabel, enabled, multiline = false)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(KeepShapes.chip)
                        .clickable(enabled = enabled, role = Role.Switch) { field.isProtected.value = !protected }
                        .semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.editor_field_protected), style = KeepDesign.type.body, color = colors.text, modifier = Modifier.weight(1f))
                    KeepSwitch(checked = protected, enabled = enabled)
                }
                KeepIconButton(VazieIcons.Trash, stringResource(R.string.editor_remove_field), onRemove, enabled = enabled, tint = colors.caution)
            }
        }
    }
}

@Composable
private fun GroupPickerDialog(title: String, groups: List<GroupChoice>, selected: app.vazie.keep.core.GroupId, onSelect: (app.vazie.keep.core.GroupId) -> Unit, onDismiss: () -> Unit) {
    val colors = KeepDesign.colors
    KeepDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                groups.forEach { group ->
                    val isSelected = group.id == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(KeepShapes.chip)
                            .background(if (isSelected) colors.accentContainer else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable(role = Role.RadioButton) { onSelect(group.id) }
                            .semantics { this.selected = isSelected }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(VazieIcons.Folder, contentDescription = null, tint = if (isSelected) colors.onAccentContainer else colors.caution, modifier = Modifier.size(20.dp))
                        Text(group.path, style = KeepDesign.type.body, color = if (isSelected) colors.onAccentContainer else colors.text, modifier = Modifier.weight(1f))
                        if (isSelected) Icon(VazieIcons.Check, contentDescription = null, tint = colors.onAccentContainer, modifier = Modifier.size(20.dp))
                    }
                }
            }
        },
    ) {
        KeepDialogAction(stringResource(R.string.action_cancel), onDismiss)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneratorSheet(
    state: GeneratorState,
    onOptions: (app.vazie.keep.core.PasswordOptions) -> Unit,
    onRegenerate: () -> Unit,
    onReveal: () -> Unit,
    onHide: () -> Unit,
    onUse: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KeepDesign.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.background,
        shape = KeepShapes.sheet,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.generator_title), style = KeepDesign.type.headline, color = colors.text, modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() })
            GeneratorPanel(
                state = state,
                onOptions = onOptions,
                onRegenerate = onRegenerate,
                onReveal = onReveal,
                onHide = onHide,
                primaryLabel = stringResource(R.string.generator_use),
                onPrimary = onUse,
            )
        }
    }
}

private fun VaultEditException.Reason.message() = when (this) {
    VaultEditException.Reason.InvalidFieldName -> R.string.editor_error_field_name
    VaultEditException.Reason.InvalidText -> R.string.editor_error_text
    VaultEditException.Reason.ReadOnly -> R.string.editor_error_read_only
    VaultEditException.Reason.NotEntitled -> R.string.editor_error_attachment_plus
    VaultEditException.Reason.AttachmentTooLarge -> R.string.attachment_too_large
    else -> R.string.editor_error_generic
}
