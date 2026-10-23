package app.vazie.keep.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vazie.keep.R
import app.vazie.keep.core.ChangedField
import app.vazie.keep.core.EntryText
import app.vazie.keep.core.HistoryVersion
import app.vazie.keep.core.isTotpFieldName
import app.vazie.keep.entry.FieldRowFrame
import app.vazie.keep.entry.PlainField
import app.vazie.keep.entry.SecretField
import app.vazie.keep.entry.SecretKey
import app.vazie.keep.save.SaveFlowHost
import app.vazie.keep.ui.components.EmptyState
import app.vazie.keep.ui.components.displayOr
import app.vazie.keep.ui.components.formatDateTime
import app.vazie.keep.ui.designsystem.KeepBadge
import app.vazie.keep.ui.designsystem.KeepBadgeTone
import app.vazie.keep.ui.designsystem.KeepBanner
import app.vazie.keep.ui.designsystem.KeepButton
import app.vazie.keep.ui.designsystem.KeepButtonSize
import app.vazie.keep.ui.designsystem.KeepButtonStyle
import app.vazie.keep.ui.designsystem.KeepCard
import app.vazie.keep.ui.designsystem.KeepDesign
import app.vazie.keep.ui.designsystem.KeepDialog
import app.vazie.keep.ui.designsystem.KeepDialogAction
import app.vazie.keep.ui.designsystem.KeepHairline
import app.vazie.keep.ui.designsystem.KeepScreen
import app.vazie.keep.ui.designsystem.KeepSectionLabel
import app.vazie.keep.ui.designsystem.KeepSnackbarHost
import app.vazie.keep.ui.designsystem.KeepTopBar
import app.vazie.keep.ui.icons.VazieIcons

@Composable
fun HistoryScreen(viewModel: HistoryViewModel, suggestedCopyName: String, onBack: () -> Unit, onGetPlus: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var confirming by remember { mutableStateOf(false) }
    val opened = (state as? HistoryUiState.Loaded)?.opened
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HistoryEvent.Copied -> {
                    val seconds = event.clearsAfterSeconds
                    snackbar.showSnackbar(
                        if (seconds == null) {
                            resources.getString(R.string.entry_copied_kept)
                        } else {
                            resources.getQuantityString(R.plurals.entry_copied, seconds, seconds)
                        },
                    )
                }
                HistoryEvent.Restored -> onBack()
            }
        }
    }
    DisposableEffect(viewModel) { onDispose { viewModel.hide() } }
    BackHandler(enabled = opened != null) { viewModel.closeVersion() }
    val colors = KeepDesign.colors
    KeepScreen {
        Column(Modifier.fillMaxSize()) {
            KeepTopBar(
                title = opened?.let { versionTitle(it.version) } ?: stringResource(R.string.history_title),
                onBack = { if (!viewModel.closeVersion()) onBack() },
            )
            when (val s = state) {
                HistoryUiState.Loading, HistoryUiState.Locked -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }
                HistoryUiState.NotFound -> EmptyState(
                    VazieIcons.Key,
                    stringResource(R.string.entry_missing_title),
                    stringResource(R.string.entry_missing_message),
                )
                HistoryUiState.NeedsPlus -> NeedsPlus(onGetPlus)
                is HistoryUiState.Loaded -> when {
                    s.opened != null -> VersionContent(s.opened, s.canRestore, viewModel, snackbar) { confirming = true }
                    s.versions.isEmpty() -> EmptyState(
                        VazieIcons.History,
                        stringResource(R.string.history_empty_title),
                        stringResource(R.string.history_empty_message),
                    )
                    else -> VersionList(s.versions, viewModel::open)
                }
            }
        }
        KeepSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    if (confirming && opened != null) {
        KeepDialog(
            onDismissRequest = { confirming = false },
            title = stringResource(R.string.history_restore_title),
            message = stringResource(R.string.history_restore_message),
        ) {
            KeepDialogAction(stringResource(R.string.history_restore_confirm), { confirming = false; viewModel.restore() }, KeepButtonStyle.Action)
            KeepDialogAction(stringResource(R.string.action_cancel), { confirming = false })
        }
    }
    SaveFlowHost(viewModel.saveFlow, suggestedCopyName, snackbar)
}

@Composable
private fun versionTitle(version: HistoryVersion): String =
    version.modifiedAt?.let { stringResource(R.string.history_version_of, formatDateTime(it)) } ?: stringResource(R.string.history_version_undated)

@Composable
private fun differsText(differs: List<ChangedField>): String {
    if (differs.isEmpty()) return stringResource(R.string.history_same)
    val names = differs.map {
        stringResource(
            when (it) {
                ChangedField.Title -> R.string.history_field_title
                ChangedField.UserName -> R.string.history_field_username
                ChangedField.Password -> R.string.history_field_password
                ChangedField.Url -> R.string.history_field_url
                ChangedField.Notes -> R.string.history_field_notes
                ChangedField.CustomFields -> R.string.history_field_custom
                ChangedField.Tags -> R.string.history_field_tags
                ChangedField.Favorite -> R.string.history_field_favorite
                ChangedField.Attachments -> R.string.history_field_attachments
                ChangedField.Expiry -> R.string.history_field_expiry
            },
        )
    }
    return stringResource(R.string.history_differs, names.joinToString(", "))
}

@Composable
private fun VersionList(versions: List<HistoryVersion>, onOpen: (Int) -> Unit) {
    val colors = KeepDesign.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(R.string.history_intro),
            style = KeepDesign.type.body,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        KeepCard {
            versions.forEachIndexed { position, version ->
                if (position > 0) KeepHairline()
                Box(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(version.index) }) {
                    FieldRowFrame {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    version.modifiedAt?.let { formatDateTime(it) } ?: stringResource(R.string.history_version_undated),
                                    style = KeepDesign.type.rowTitle,
                                    color = colors.text,
                                )
                                Text(differsText(version.differs), style = KeepDesign.type.caption, color = colors.textSecondary)
                            }
                            Icon(VazieIcons.ChevronRight, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VersionContent(
    opened: OpenedVersion,
    canRestore: Boolean,
    viewModel: HistoryViewModel,
    snackbar: SnackbarHostState,
    onRestore: () -> Unit,
) {
    val colors = KeepDesign.colors
    val detail = opened.detail
    val summary = detail.summary
    val hidden = stringResource(R.string.value_protected)
    val title = summary.title.displayOr(hidden).ifEmpty { stringResource(R.string.entry_untitled) }
    @Composable
    fun secret(key: SecretKey, label: String, copyable: Boolean, primary: Boolean = false) {
        val revealed = opened.revealed?.takeIf { it.key == key }
        SecretField(
            label = label,
            revealed = revealed?.text,
            announce = false,
            onReveal = { viewModel.reveal(key) },
            onHide = viewModel::hide,
            onReadAloud = null,
            onCopy = if (copyable) ({ viewModel.copy(key) }) else null,
            primary = primary,
        )
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            title,
            style = KeepDesign.type.headline,
            color = colors.text,
            modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() },
        )
        KeepBanner(differsText(opened.version.differs), icon = VazieIcons.History)
        val main = buildList<@Composable () -> Unit> {
            if (summary.username.displayOr(hidden).isNotEmpty()) add { PlainField(stringResource(R.string.field_username), summary.username, copyable = true, snackbar = snackbar) }
            if (summary.hasPassword) add { secret(SecretKey.Password, stringResource(R.string.field_password), copyable = true, primary = true) }
            if (summary.url.displayOr(hidden).isNotEmpty()) add { PlainField(stringResource(R.string.field_url), summary.url) }
        }
        if (main.isNotEmpty()) {
            KeepCard {
                main.forEachIndexed { index, row ->
                    if (index > 0) KeepHairline()
                    row()
                }
            }
        }
        val notes = detail.notes
        val extras = buildList<@Composable () -> Unit> {
            when (notes) {
                is EntryText.Plain -> if (notes.value.isNotEmpty()) add { PlainField(stringResource(R.string.field_notes), notes) }
                EntryText.Hidden -> add { secret(SecretKey.Notes, stringResource(R.string.field_notes), copyable = false) }
            }
            detail.customFields.forEach { field ->
                when (val value = field.value) {
                    is EntryText.Plain -> if (value.value.isNotEmpty()) add { PlainField(field.name, value, snackbar = snackbar, copyable = !isTotpFieldName(field.name)) }
                    EntryText.Hidden -> add { secret(SecretKey.Custom(field.name), field.name, copyable = true) }
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
        if (detail.attachments.isNotEmpty()) {
            KeepSectionLabel(stringResource(R.string.entry_section_attachments))
            KeepCard {
                detail.attachments.forEachIndexed { index, attachment ->
                    if (index > 0) KeepHairline()
                    FieldRowFrame {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(VazieIcons.Paperclip, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                            Text(attachment.name, style = KeepDesign.type.rowTitle, color = colors.text, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        if (summary.tags.isNotEmpty()) {
            KeepSectionLabel(stringResource(R.string.field_tags))
            FlowRow(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                summary.tags.forEach { tag -> KeepBadge(tag, tone = KeepBadgeTone.Neutral) }
            }
        }
        if (canRestore) {
            KeepButton(stringResource(R.string.history_restore), onRestore, Modifier.fillMaxWidth(), style = KeepButtonStyle.Tonal, icon = VazieIcons.History)
        }
    }
}

@Composable
private fun NeedsPlus(onGetPlus: () -> Unit) {
    val colors = KeepDesign.colors
    Column(Modifier.padding(horizontal = 16.dp)) {
        KeepCard {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KeepBadge(stringResource(R.string.totp_locked_badge), tone = KeepBadgeTone.Accent)
                Text(stringResource(R.string.history_locked_title), style = KeepDesign.type.title, color = colors.text)
                Text(stringResource(R.string.history_locked_message), style = KeepDesign.type.body, color = colors.textSecondary)
                KeepButton(stringResource(R.string.totp_locked_action), onGetPlus, style = KeepButtonStyle.Tonal, size = KeepButtonSize.Medium)
            }
        }
    }
}
