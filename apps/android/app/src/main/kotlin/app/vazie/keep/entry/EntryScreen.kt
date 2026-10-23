package app.vazie.keep.entry

import app.vazie.keep.ui.designsystem.KeepSettingRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import app.vazie.keep.ui.designsystem.KeepShapes
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import app.vazie.keep.core.AttachmentInfo
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Icon
import app.vazie.keep.ui.components.formatDate
import app.vazie.keep.ui.designsystem.KeepBanner
import app.vazie.keep.ui.designsystem.KeepBannerTone
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vazie.keep.R
import app.vazie.keep.save.SaveFlowHost
import app.vazie.keep.ui.components.DeleteEntryDialog
import app.vazie.keep.ui.components.EntryGlyph
import app.vazie.keep.ui.components.IconPickerDialog
import app.vazie.keep.ui.icons.ItemIcon
import app.vazie.keep.ui.components.EmptyState
import app.vazie.keep.ui.components.MoveDialog
import app.vazie.keep.ui.components.displayOr
import app.vazie.keep.ui.designsystem.HiddenDots
import app.vazie.keep.ui.designsystem.KeepBadge
import app.vazie.keep.ui.designsystem.KeepBadgeTone
import app.vazie.keep.ui.designsystem.KeepButton
import app.vazie.keep.ui.designsystem.KeepButtonSize
import app.vazie.keep.ui.designsystem.KeepButtonStyle
import app.vazie.keep.ui.designsystem.KeepCard
import app.vazie.keep.ui.designsystem.KeepDesign
import app.vazie.keep.ui.designsystem.KeepDialog
import app.vazie.keep.ui.designsystem.KeepDialogAction
import app.vazie.keep.ui.designsystem.KeepHairline
import app.vazie.keep.ui.designsystem.KeepIconButton
import app.vazie.keep.ui.designsystem.KeepIconTone
import app.vazie.keep.ui.designsystem.KeepScreen
import app.vazie.keep.ui.designsystem.KeepSectionLabel
import app.vazie.keep.ui.designsystem.KeepSnackbarHost
import app.vazie.keep.ui.designsystem.KeepTopBar
import app.vazie.keep.ui.designsystem.Monogram
import app.vazie.keep.ui.designsystem.secretText
import app.vazie.keep.core.EntryText
import app.vazie.keep.core.isTotpFieldName
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.vazie.keep.ui.icons.VazieIcons
import kotlinx.coroutines.launch

@Composable
fun EntryScreen(
    viewModel: EntryViewModel,
    suggestedCopyName: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onGetPlus: () -> Unit = {},
    onHistory: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val canEdit by viewModel.canEdit.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var menuOpen by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var choosingIcon by remember { mutableStateOf(false) }
    var removingPasskey by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is EntryEvent.Copied -> {
                    val seconds = event.clearsAfterSeconds
                    snackbar.showSnackbar(
                        if (seconds == null) {
                            resources.getString(R.string.entry_copied_kept)
                        } else {
                            resources.getQuantityString(R.plurals.entry_copied, seconds, seconds)
                        },
                    )
                }
                EntryEvent.Deleted -> onBack()
                EntryEvent.Moved -> snackbar.showSnackbar(resources.getString(R.string.entry_moved))
                is EntryEvent.AttachmentExported -> snackbar.showSnackbar(
                    resources.getString(if (event.written) R.string.attachment_saved else R.string.attachment_save_failed),
                )
            }
        }
    }
    DisposableEffect(viewModel) { onDispose { viewModel.hide() } }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.setTotpVisible(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.setTotpVisible(false) }
    val loaded = state as? EntryUiState.Loaded
    val hiddenLabel = stringResource(R.string.value_protected)
    val colors = KeepDesign.colors
    KeepScreen {
        Column(Modifier.fillMaxSize()) {
            KeepTopBar(
                onBack = onBack,
                actions = {
                    if (loaded != null && canEdit) {
                        val favorite = loaded.detail.summary.favorite
                        KeepIconButton(
                            if (favorite) VazieIcons.StarFilled else VazieIcons.Star,
                            stringResource(if (favorite) R.string.entry_unfavorite else R.string.entry_favorite),
                            viewModel::toggleFavorite,
                            tint = if (favorite) colors.accent else colors.text,
                        )
                        KeepIconButton(VazieIcons.Pencil, stringResource(R.string.action_edit), onEdit, tint = colors.text)
                        Box {
                            KeepIconButton(VazieIcons.More, stringResource(R.string.action_more), { menuOpen = true })
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_change_icon)) },
                                    onClick = { menuOpen = false; choosingIcon = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_move)) },
                                    onClick = { menuOpen = false; moving = true },
                                    enabled = loaded.moveTargets.isNotEmpty(),
                                )
                                if (loaded?.detail?.summary?.passkey != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.passkey_remove)) },
                                        onClick = { menuOpen = false; removingPasskey = true },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_delete), color = colors.caution) },
                                    onClick = { menuOpen = false; deleting = true },
                                )
                            }
                        }
                    }
                },
            )
            when (val s = state) {
                EntryUiState.Loading, EntryUiState.Locked -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }
                EntryUiState.NotFound -> EmptyState(
                    VazieIcons.Key,
                    stringResource(R.string.entry_missing_title),
                    stringResource(R.string.entry_missing_message),
                )
                is EntryUiState.Loaded -> EntryContent(s, viewModel, snackbar, onGetPlus, onHistory, Modifier.weight(1f))
            }
        }
        KeepSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    if (moving && loaded != null) {
        MoveDialog(
            targets = loaded.moveTargets,
            onSelect = { viewModel.move(it); moving = false },
            onDismiss = { moving = false },
        )
    }
    if (removingPasskey) {
        KeepDialog(
            onDismissRequest = { removingPasskey = false },
            title = stringResource(R.string.passkey_remove_title),
            message = stringResource(R.string.passkey_remove_message),
        ) {
            KeepDialogAction(stringResource(R.string.passkey_remove), { removingPasskey = false; viewModel.removePasskey() }, KeepButtonStyle.Caution)
            KeepDialogAction(stringResource(R.string.action_cancel), { removingPasskey = false })
        }
    }
    if (choosingIcon && loaded != null) {
        IconPickerDialog(
            title = stringResource(R.string.icon_picker_entry),
            selected = if (loaded.detail.summary.customIcon != null) null else ItemIcon.forEntry(loaded.detail.summary.icon) ?: ItemIcon.Key,
            onSelect = { viewModel.setIcon(it.id); choosingIcon = false },
            onDismiss = { choosingIcon = false },
        )
    }
    if (deleting && loaded != null) {
        DeleteEntryDialog(
            title = loaded.detail.summary.title.displayOr(hiddenLabel).ifEmpty { stringResource(R.string.entry_untitled) },
            toBin = loaded.deletesToBin,
            onDelete = { permanent -> viewModel.delete(permanent); deleting = false },
            onDismiss = { deleting = false },
        )
    }
    SaveFlowHost(viewModel.saveFlow, suggestedCopyName, snackbar)
    loaded?.confirmReadAloud?.let {
        KeepDialog(
            onDismissRequest = { viewModel.confirmReadAloud(false) },
            title = stringResource(R.string.read_aloud_title),
            message = stringResource(R.string.read_aloud_warning),
        ) {
            KeepDialogAction(stringResource(R.string.read_aloud_confirm), { viewModel.confirmReadAloud(true) }, KeepButtonStyle.Action)
            KeepDialogAction(stringResource(R.string.action_cancel), { viewModel.confirmReadAloud(false) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryContent(
    state: EntryUiState.Loaded,
    viewModel: EntryViewModel,
    snackbar: SnackbarHostState,
    onGetPlus: () -> Unit,
    onHistory: () -> Unit,
    modifier: Modifier,
) {
    val summary = state.detail.summary
    val canEdit by viewModel.canEdit.collectAsStateWithLifecycle()
    val colors = KeepDesign.colors
    val hidden = stringResource(R.string.value_protected)
    val title = summary.title.displayOr(hidden).ifEmpty { stringResource(R.string.entry_untitled) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            EntryGlyph(title, summary.icon, size = 64.dp, radius = 20.dp, textSize = 26f, image = summary.customIcon)
            Text(
                title,
                style = KeepDesign.type.headline,
                color = colors.text,
                modifier = Modifier.weight(1f, fill = false).semantics { heading() },
            )
            if (summary.favorite && !canEdit) {
                Icon(VazieIcons.StarFilled, contentDescription = stringResource(R.string.entry_favorite_state), tint = colors.accent, modifier = Modifier.size(20.dp))
            }
        }
        summary.expiresAt?.let { expires ->
            if (state.expired) {
                KeepBanner(stringResource(R.string.entry_expired_message, formatDate(expires)), tone = KeepBannerTone.Caution, icon = VazieIcons.Clock)
            }
        }
        val plainRows = buildList<@Composable () -> Unit> {
            if (summary.username.displayOr(hidden).isNotEmpty()) add { PlainField(stringResource(R.string.field_username), summary.username, copyable = true, snackbar = snackbar) }
            if (summary.hasPassword) add { SecretRow(state, SecretKey.Password, stringResource(R.string.field_password), viewModel, copyable = true, primary = true) }
            if (summary.url.displayOr(hidden).isNotEmpty()) add { PlainField(stringResource(R.string.field_url), summary.url, link = true) }
        }
        if (plainRows.isNotEmpty()) {
            KeepCard {
                plainRows.forEachIndexed { index, row ->
                    if (index > 0) KeepHairline()
                    row()
                }
            }
        }
        when (val totp = state.totp) {
            TotpState.None -> Unit
            TotpState.Locked -> TotpLockedCard(onGetPlus)
            TotpState.Waiting -> TotpCard(null, viewModel::copyTotp)
            is TotpState.Active -> TotpCard(totp, viewModel::copyTotp)
        }
        val hideTotpFields = state.totp is TotpState.Active || state.totp is TotpState.Waiting
        val notes = state.detail.notes
        val extras = buildList<@Composable () -> Unit> {
            when (notes) {
                is EntryText.Plain -> if (notes.value.isNotEmpty()) add { PlainField(stringResource(R.string.field_notes), notes) }
                EntryText.Hidden -> add { SecretRow(state, SecretKey.Notes, stringResource(R.string.field_notes), viewModel, copyable = false) }
            }
            state.detail.customFields.filterNot { hideTotpFields && isTotpFieldName(it.name) }.forEach { field ->
                when (val value = field.value) {
                    is EntryText.Plain -> if (value.value.isNotEmpty()) add { PlainField(field.name, value, snackbar = snackbar, copyable = true) }
                    EntryText.Hidden -> add { SecretRow(state, SecretKey.Custom(field.name), field.name, viewModel, copyable = true) }
                }
            }
        }
        if (extras.isNotEmpty()) {
            KeepSectionLabel(stringResource(R.string.entry_section_more))
            KeepCard {
                extras.forEachIndexed { index, row ->
                    if (index > 0) KeepHairline()
                    row()
                }
            }
        }
        if (state.detail.attachments.isNotEmpty()) {
            AttachmentsCard(state.detail.attachments, viewModel::exportAttachment)
        }
        summary.expiresAt?.let { expires ->
            if (!state.expired) {
                KeepCard {
                    FieldRowFrame {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(VazieIcons.Calendar, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(stringResource(R.string.field_expires), style = KeepDesign.type.label, color = colors.textSecondary)
                                Text(formatDate(expires), style = KeepDesign.type.body, color = colors.text)
                            }
                        }
                    }
                }
            }
        }
        summary.passkey?.let { passkey ->
            KeepCard {
                KeepSettingRow(
                    title = stringResource(R.string.passkey_title),
                    subtitle = if (passkey.userName.isEmpty()) passkey.rpId else stringResource(R.string.passkey_subject, passkey.rpId, passkey.userName),
                    icon = VazieIcons.Shield,
                )
            }
        }
        if (summary.tags.isNotEmpty()) {
            KeepSectionLabel(stringResource(R.string.field_tags))
            FlowRow(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                summary.tags.forEach { tag -> KeepBadge(tag, tone = KeepBadgeTone.Neutral) }
            }
        }
        if (state.detail.historyCount > 0) {
            HistoryRow(state.detail.historyCount, state.historyUnlocked, if (state.historyUnlocked) onHistory else onGetPlus)
        }
        if (state.revealed != null) {
            val seconds = REVEAL_DURATION.inWholeSeconds.toInt()
            Text(
                pluralStringResource(R.plurals.entry_auto_hide, seconds, seconds),
                style = KeepDesign.type.caption,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun HistoryRow(count: Int, unlocked: Boolean, onClick: () -> Unit) {
    val colors = KeepDesign.colors
    KeepCard(Modifier.clip(KeepShapes.card).clickable(role = Role.Button, onClick = onClick)) {
        FieldRowFrame {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(VazieIcons.History, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(R.string.history_title), style = KeepDesign.type.rowTitle, color = colors.text)
                    Text(pluralStringResource(R.plurals.history_versions, count, count), style = KeepDesign.type.caption, color = colors.textSecondary)
                }
                if (!unlocked) KeepBadge(stringResource(R.string.totp_locked_badge), tone = KeepBadgeTone.Accent)
                Icon(VazieIcons.ChevronRight, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp).padding(end = 2.dp))
            }
        }
    }
}

@Composable
private fun AttachmentsCard(attachments: List<AttachmentInfo>, onExport: (String, String?) -> Unit) {
    val colors = KeepDesign.colors
    val context = LocalContext.current
    var exporting by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        exporting?.let { onExport(it, uri?.toString()) }
        exporting = null
    }
    KeepSectionLabel(stringResource(R.string.entry_section_attachments))
    KeepCard {
        attachments.forEachIndexed { index, attachment ->
            if (index > 0) KeepHairline()
            FieldRowFrame {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(VazieIcons.Paperclip, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(attachment.name, style = KeepDesign.type.rowTitle, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.attachment_stored, Formatter.formatShortFileSize(context, attachment.size)),
                            style = KeepDesign.type.caption,
                            color = colors.textSecondary,
                        )
                    }
                    KeepIconButton(
                        VazieIcons.Download,
                        stringResource(R.string.attachment_save, attachment.name),
                        { exporting = attachment.name; picker.launch(attachment.name) },
                        tone = KeepIconTone.Raised,
                        tint = colors.text,
                    )
                }
            }
        }
    }
}

@Composable
private fun SecretRow(state: EntryUiState.Loaded, key: SecretKey, label: String, viewModel: EntryViewModel, copyable: Boolean, primary: Boolean = false) {
    val revealed = state.revealed?.takeIf { it.key == key }
    SecretField(
        label = label,
        revealed = revealed?.text,
        announce = revealed?.announce == true,
        onReveal = { viewModel.reveal(key) },
        onHide = viewModel::hide,
        onReadAloud = { viewModel.requestReadAloud(key) },
        onCopy = if (copyable) ({ viewModel.copy(key) }) else null,
        primary = primary,
    )
}

@Composable
internal fun FieldRowFrame(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 14.dp)) { content() }
}

@Composable
internal fun PlainField(label: String, value: EntryText, copyable: Boolean = false, link: Boolean = false, snackbar: SnackbarHostState? = null) {
    val text = value.displayOr(stringResource(R.string.value_protected))
    if (text.isEmpty()) return
    val colors = KeepDesign.colors
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.entry_copied_plain)
    FieldRowFrame {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(label, style = KeepDesign.type.label, color = colors.textSecondary)
                SelectionContainer {
                    Text(text, style = KeepDesign.type.body, color = if (link) colors.accent else colors.text)
                }
            }
            if (copyable) {
                KeepIconButton(
                    VazieIcons.Copy,
                    stringResource(R.string.action_copy_secret, label),
                    {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText(label, text)))
                            snackbar?.showSnackbar(copiedMessage)
                        }
                    },
                    tone = KeepIconTone.Raised,
                    tint = colors.text,
                )
            }
        }
    }
}

@Composable
internal fun SecretField(
    label: String,
    revealed: String?,
    announce: Boolean,
    onReveal: () -> Unit,
    onHide: () -> Unit,
    onReadAloud: (() -> Unit)?,
    onCopy: (() -> Unit)?,
    primary: Boolean,
) {
    val colors = KeepDesign.colors
    val shownDescription = stringResource(R.string.secret_shown, label)
    val hiddenDescription = stringResource(R.string.secret_hidden, label)
    val shownState = stringResource(R.string.state_shown)
    val hiddenState = stringResource(R.string.state_hidden)
    val readAloud = stringResource(R.string.read_aloud_action, label)
    FieldRowFrame {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // One node for label and value. The revealed characters are drawn but never exposed to
            // accessibility services unless the user explicitly asks to hear them (ADR 0008).
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics {
                        contentDescription = when {
                            revealed != null && announce -> "$label: $revealed"
                            revealed != null -> shownDescription
                            else -> hiddenDescription
                        }
                        if (announce) liveRegion = LiveRegionMode.Assertive
                        if (onReadAloud != null) customActions = listOf(CustomAccessibilityAction(readAloud) { onReadAloud(); true })
                    },
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(label, style = KeepDesign.type.label, color = colors.textSecondary)
                if (revealed != null) {
                    Text(
                        secretText(revealed, colors),
                        style = KeepDesign.type.secret.copy(fontSize = 17.5.sp),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.heightIn(min = 28.dp).horizontalScroll(rememberScrollState()),
                    )
                } else {
                    HiddenDots()
                }
                if (primary) {
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (revealed != null) {
                            KeepBadge(stringResource(R.string.entry_visible_badge), icon = VazieIcons.Eye, tone = KeepBadgeTone.Accent)
                        } else {
                            KeepBadge(hiddenState, icon = VazieIcons.EyeOff)
                        }
                    }
                }
            }
            KeepIconButton(
                icon = if (revealed != null) VazieIcons.EyeOff else VazieIcons.Eye,
                contentDescription = stringResource(if (revealed != null) R.string.action_hide_secret else R.string.action_show_secret, label),
                onClick = if (revealed != null) onHide else onReveal,
                tone = KeepIconTone.Raised,
                tint = colors.text,
                modifier = Modifier.semantics { stateDescription = if (revealed != null) shownState else hiddenState },
            )
            if (onCopy != null) {
                KeepIconButton(VazieIcons.Copy, stringResource(R.string.action_copy_secret, label), onCopy, tone = KeepIconTone.Raised, tint = colors.text)
            }
        }
    }
}

@Composable
private fun TotpCard(totp: TotpState.Active?, onCopy: () -> Unit) {
    val colors = KeepDesign.colors
    val label = stringResource(R.string.totp_label)
    val remaining = totp?.secondsRemaining ?: 0
    val period = totp?.period ?: 30
    val urgent = totp != null && remaining <= TOTP_URGENT_SECONDS
    val ring = if (urgent) colors.caution else colors.accent
    val description = if (totp != null) stringResource(R.string.totp_description, remaining) else stringResource(R.string.secret_hidden, label)
    KeepCard {
        FieldRowFrame {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    Modifier.weight(1f).clearAndSetSemantics { contentDescription = description },
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(label, style = KeepDesign.type.label, color = colors.textSecondary)
                    if (totp != null) {
                        Text(
                            groupCode(totp.code),
                            style = KeepDesign.type.secret.copy(fontSize = 30.sp),
                            color = if (urgent) colors.caution else colors.text,
                            maxLines = 1,
                            softWrap = false,
                        )
                    } else {
                        HiddenDots()
                    }
                }
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { if (totp == null) 0f else remaining.toFloat() / period },
                        modifier = Modifier.size(44.dp),
                        color = ring,
                        trackColor = colors.hairline,
                        strokeWidth = 3.dp,
                    )
                    if (totp != null) Text(stringResource(R.string.totp_seconds, remaining), style = KeepDesign.type.caption, color = colors.textSecondary)
                }
                KeepIconButton(
                    VazieIcons.Copy,
                    stringResource(R.string.action_copy_secret, label),
                    onCopy,
                    enabled = totp != null,
                    tone = KeepIconTone.Raised,
                    tint = colors.text,
                )
            }
        }
    }
}

@Composable
private fun TotpLockedCard(onGetPlus: () -> Unit) {
    val colors = KeepDesign.colors
    KeepCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KeepBadge(stringResource(R.string.totp_locked_badge), tone = KeepBadgeTone.Accent)
            Text(stringResource(R.string.totp_locked_title), style = KeepDesign.type.title, color = colors.text)
            Text(stringResource(R.string.totp_locked_message), style = KeepDesign.type.body, color = colors.textSecondary)
            KeepButton(stringResource(R.string.totp_locked_action), onGetPlus, style = KeepButtonStyle.Tonal, size = KeepButtonSize.Medium)
        }
    }
}

private const val TOTP_URGENT_SECONDS = 5

private fun groupCode(code: String): String = when (code.length) {
    6 -> "${code.take(3)} ${code.drop(3)}"
    8 -> "${code.take(4)} ${code.drop(4)}"
    else -> code
}
