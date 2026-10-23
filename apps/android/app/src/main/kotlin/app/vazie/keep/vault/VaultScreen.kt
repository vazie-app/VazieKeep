package app.vazie.keep.vault

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vazie.keep.importer.ImportDialog
import app.vazie.keep.importer.ImportViewModel
import app.vazie.keep.R
import app.vazie.keep.chooser.OpenVaultDocument
import app.vazie.keep.core.EntryId
import app.vazie.keep.core.EntrySummary
import app.vazie.keep.core.GroupId
import app.vazie.keep.core.VaultGroup
import app.vazie.keep.save.SaveFlowHost
import app.vazie.keep.ui.components.EmptyState
import app.vazie.keep.ui.components.EntryRow
import app.vazie.keep.ui.components.IconPickerDialog
import app.vazie.keep.ui.icons.ItemIcon
import app.vazie.keep.ui.components.GroupActions
import app.vazie.keep.ui.components.GroupRow
import app.vazie.keep.ui.designsystem.DockTab
import app.vazie.keep.ui.designsystem.KeepBanner
import app.vazie.keep.ui.designsystem.KeepBannerTone
import app.vazie.keep.ui.designsystem.KeepButton
import app.vazie.keep.ui.designsystem.KeepButtonSize
import app.vazie.keep.ui.designsystem.KeepButtonStyle
import app.vazie.keep.ui.designsystem.KeepCard
import app.vazie.keep.ui.designsystem.KeepChip
import app.vazie.keep.ui.designsystem.KeepDesign
import app.vazie.keep.ui.designsystem.isLargeFont
import app.vazie.keep.ui.designsystem.KeepDialog
import app.vazie.keep.ui.designsystem.KeepDialogAction
import app.vazie.keep.ui.designsystem.KeepDock
import app.vazie.keep.ui.designsystem.KeepIconButton
import app.vazie.keep.ui.designsystem.KeepSectionLabel
import app.vazie.keep.ui.designsystem.KeepShapes
import app.vazie.keep.ui.designsystem.KeepSnackbarHost
import app.vazie.keep.ui.designsystem.KeepTextField
import app.vazie.keep.ui.designsystem.Monogram
import app.vazie.keep.ui.designsystem.BottomFade
import app.vazie.keep.ui.designsystem.KeepScreen
import app.vazie.keep.ui.icons.VazieIcons

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VaultScreen(
    viewModel: VaultViewModel,
    suggestedCopyName: String,
    onOpenEntry: (EntryId) -> Unit,
    onNewEntry: (GroupId) -> Unit,
    onSettings: () -> Unit,
    onGenerator: () -> Unit = {},
    switcher: VaultSwitcher? = null,
    onGetPlus: () -> Unit = {},
    importer: ImportViewModel? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val canEdit by viewModel.canEdit.collectAsStateWithLifecycle()
    val needsGrant by viewModel.needsWriteGrant.collectAsStateWithLifecycle()
    val readOnlyByPlan by viewModel.readOnlyByPlan.collectAsStateWithLifecycle()
    val unsaved by viewModel.hasUnsavedChanges.collectAsStateWithLifecycle()
    val recovered by viewModel.hasRecoveredCopy.collectAsStateWithLifecycle()
    val saving by viewModel.saveInProgress.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val focus = LocalFocusManager.current
    val browsing = state as? VaultUiState.Browsing
    var addMenu by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    val colors = KeepDesign.colors
    val grantPicker = rememberLauncherForActivityResult(OpenVaultDocument()) { uri -> viewModel.regrantAccess(uri?.toString()) }
    val recoveredPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        viewModel.saveRecoveredCopy(uri?.toString())
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importer?.onFilePicked(uri?.toString())
    }
    val unsavedPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        viewModel.saveFlow.saveCopy(uri?.toString())
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbar.showSnackbar(
                when (message) {
                    VaultMessage.PasswordCopied -> viewModel.lastCopySeconds?.let { seconds ->
                        resources.getQuantityString(R.plurals.entry_copied, seconds, seconds)
                    } ?: resources.getString(R.string.entry_copied_kept)
                    VaultMessage.IconsAssigned -> resources.getQuantityString(R.plurals.icons_assigned, viewModel.lastIconCount, viewModel.lastIconCount)
                    else -> resources.getString(
                        when (message) {
                            VaultMessage.InvalidName -> R.string.vault_error_name
                            VaultMessage.CouldNotEdit -> R.string.vault_error_edit
                            VaultMessage.CopySaved -> R.string.copy_saved
                            VaultMessage.CopyFailed -> R.string.copy_failed
                            VaultMessage.AccessNotGranted -> R.string.vault_access_not_granted
                            VaultMessage.PasswordCopied -> R.string.entry_copied_kept
                            VaultMessage.IconsAssigned, VaultMessage.IconsNothing -> R.string.icons_nothing
                            VaultMessage.IconsUnavailable -> R.string.icons_unavailable
                        },
                    )
                },
            )
        }
    }
    val imeVisible = WindowInsets.isImeVisible
    BackHandler(enabled = !imeVisible && (query.isNotEmpty() || !browsing?.path.isNullOrEmpty() || browsing?.filter != VaultFilter.All)) {
        if (query.isNotEmpty()) viewModel.onQueryChange("") else viewModel.up()
    }
    val vaultName = when (val s = state) {
        is VaultUiState.Browsing -> s.vaultName
        is VaultUiState.Searching -> s.vaultName
        else -> stringResource(R.string.app_name)
    }
    val nested = !browsing?.path.isNullOrEmpty()
    val lockLabel = stringResource(R.string.action_lock)
    val lockShort = stringResource(R.string.action_lock_short)
    val searchState = rememberTextFieldState(query)
    LaunchedEffect(query) {
        if (searchState.text.toString() != query) searchState.edit { replace(0, length, query) }
    }
    LaunchedEffect(searchState) {
        androidx.compose.runtime.snapshotFlow { searchState.text.toString() }.collect { text -> if (text != query) viewModel.onQueryChange(text) }
    }

    if (switching && switcher != null) {
        VaultSwitcherSheet(
            VaultSwitcher(switcher.vaults, switcher.activeId, { id -> switching = false; switcher.onSwitch(id) }, { switching = false; switcher.onAll() }),
            onDismiss = { switching = false },
        )
    }
    KeepScreen {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (nested) KeepIconButton(VazieIcons.Back, stringResource(R.string.action_back), { viewModel.up() }, visualSize = 44, tint = colors.text)
                Row(Modifier.weight(1f).padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!nested) Monogram(vaultName, size = 44.dp, radius = 13.dp, tone = colors.vaultTiles.first(), textSize = 19f)
                    val switchLabel = stringResource(R.string.switcher_description, vaultName)
                    Column(
                        Modifier
                            .weight(1f)
                            .then(
                                if (switcher != null && !nested) {
                                    Modifier
                                        .clip(KeepShapes.field)
                                        .clickable(role = Role.Button) { switching = true }
                                        .semantics { contentDescription = switchLabel }
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        Text(
                            vaultName,
                            style = KeepDesign.type.title.copy(fontSize = 23.sp),
                            color = colors.text,
                            maxLines = if (isLargeFont()) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() },
                        )
                        browsing?.let { b ->
                            val count = b.entries.size + b.groups.size
                            Text(
                                pluralStringResource(R.plurals.vault_item_count, count, count),
                                style = KeepDesign.type.caption,
                                color = colors.textSecondary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .height(44.dp)
                        .clip(KeepShapes.pill)
                        .background(colors.surfaceField)
                        .clickable(role = Role.Button, onClick = viewModel::requestLock)
                        .semantics(mergeDescendants = true) { contentDescription = lockLabel }
                        .padding(start = 12.dp, end = if (isLargeFont()) 12.dp else 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(VazieIcons.Lock, contentDescription = null, tint = colors.text, modifier = Modifier.size(18.dp))
                    if (!isLargeFont()) Text(lockShort, style = KeepDesign.type.label.copy(fontSize = 14.sp), color = colors.text, maxLines = 1, softWrap = false)
                }
                KeepIconButton(VazieIcons.Settings, stringResource(R.string.settings_title), onSettings)
            }
            if (saving) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(KeepShapes.pill), color = colors.accent, trackColor = colors.surfaceRaised)
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (unsaved) {
                    KeepBanner(
                        message = stringResource(R.string.vault_unsaved_message),
                        tone = KeepBannerTone.Error,
                        icon = VazieIcons.Warning,
                    ) {
                        KeepButton(stringResource(R.string.save_copy), { unsavedPicker.launch(suggestedCopyName) }, style = KeepButtonStyle.Raised, size = KeepButtonSize.Pill, fullWidth = false)
                        KeepButton(stringResource(R.string.save_try_again), { viewModel.saveFlow.retry() }, size = KeepButtonSize.Pill, fullWidth = false)
                    }
                }
                if (recovered) {
                    KeepBanner(message = stringResource(R.string.vault_recovered_message)) {
                        KeepButton(stringResource(R.string.action_delete), viewModel::discardRecoveredCopy, style = KeepButtonStyle.Caution, size = KeepButtonSize.Pill, fullWidth = false)
                        KeepButton(stringResource(R.string.save_copy), { recoveredPicker.launch(suggestedCopyName) }, size = KeepButtonSize.Pill, fullWidth = false)
                    }
                }
                if (readOnlyByPlan) {
                    KeepBanner(message = stringResource(R.string.vault_read_only_plan), icon = VazieIcons.Lock) {
                        KeepButton(stringResource(R.string.totp_locked_action), onGetPlus, style = KeepButtonStyle.Raised, size = KeepButtonSize.Pill, fullWidth = false)
                    }
                }
                if (needsGrant) {
                    KeepBanner(message = stringResource(R.string.vault_read_only_message), icon = VazieIcons.Pencil) {
                        KeepButton(stringResource(R.string.vault_allow_editing), { grantPicker.launch(arrayOf("*/*")) }, style = KeepButtonStyle.Raised, size = KeepButtonSize.Pill, fullWidth = false)
                    }
                }
                KeepTextField(
                    state = searchState,
                    label = null,
                    placeholder = stringResource(R.string.vault_search_hint),
                    leadingIcon = VazieIcons.Search,
                    height = 52,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                    onKeyboardAction = { focus.clearFocus() },
                    trailing = if (query.isNotEmpty()) {
                        { KeepIconButton(VazieIcons.Close, stringResource(R.string.action_clear_search), { viewModel.onQueryChange("") }) }
                    } else {
                        null
                    },
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val s = state) {
                    VaultUiState.Loading, VaultUiState.Locked -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.accent)
                    }
                    is VaultUiState.Searching -> SearchResults(s, onOpenEntry, viewModel::copyPassword)
                    is VaultUiState.Browsing -> GroupContent(
                        state = s,
                        canEdit = canEdit,
                        onOpenGroup = viewModel::openGroup,
                        onOpenEntry = onOpenEntry,
                        onCopy = viewModel::copyPassword,
                        onRename = viewModel::requestRename,
                        onIcon = viewModel::requestIcon,
                        onDelete = viewModel::requestDelete,
                        onAdd = { onNewEntry(s.currentGroup) },
                        onFilter = viewModel::selectFilter,
                    )
                }
                BottomFade(Modifier.align(Alignment.BottomCenter))
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).imePadding()) {
            if (!imeVisible) {
                KeepDock(
                    selected = DockTab.Keep,
                    onKeep = {},
                    onGenerator = onGenerator,
                    onAdd = if (canEdit && browsing != null) ({ addMenu = true }) else null,
                )
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 96.dp)) {
            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_new_entry)) },
                    leadingIcon = { Icon(VazieIcons.Key, contentDescription = null) },
                    onClick = { addMenu = false; browsing?.let { onNewEntry(it.currentGroup) } },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_new_group)) },
                    leadingIcon = { Icon(VazieIcons.FolderAdd, contentDescription = null) },
                    onClick = { addMenu = false; viewModel.requestNewGroup() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.icons_assign_action)) },
                    leadingIcon = { Icon(VazieIcons.Globe, contentDescription = null) },
                    onClick = { addMenu = false; viewModel.requestSiteIcons() },
                )
                if (importer != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.import_action)) },
                        leadingIcon = { Icon(VazieIcons.Download, contentDescription = null) },
                        onClick = { addMenu = false; importPicker.launch(arrayOf("*/*")) },
                    )
                }
            }
        }
        KeepSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
    }
    if (importer != null) {
        ImportDialog(importer)
        SaveFlowHost(importer.saveFlow, suggestedCopyName, snackbar)
    }
    when (val d = dialog) {
        VaultDialog.NewGroup -> NameDialog(
            title = stringResource(R.string.group_new_title),
            confirm = stringResource(R.string.action_create),
            initial = "",
            onConfirm = viewModel::createGroup,
            onDismiss = viewModel::dismissDialog,
        )
        is VaultDialog.RenameGroup -> NameDialog(
            title = stringResource(R.string.group_rename_title),
            confirm = stringResource(R.string.action_rename),
            initial = d.group.name,
            onConfirm = { viewModel.renameGroup(d.group, it) },
            onDismiss = viewModel::dismissDialog,
        )
        is VaultDialog.GroupIcon -> IconPickerDialog(
            title = stringResource(R.string.icon_picker_group, d.group.name),
            selected = ItemIcon.forGroup(d.group.icon),
            onSelect = { viewModel.setGroupIcon(d.group, it.id) },
            onDismiss = viewModel::dismissDialog,
        )
        is VaultDialog.DeleteGroup -> DeleteGroupDialog(
            dialog = d,
            onDelete = { permanent -> viewModel.deleteGroup(d.group, permanent) },
            onDismiss = viewModel::dismissDialog,
        )
        VaultDialog.ConfirmLock -> KeepDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = stringResource(R.string.lock_unsaved_title),
            message = stringResource(R.string.lock_unsaved_message),
        ) {
            KeepDialogAction(stringResource(R.string.action_save), viewModel::lockSavingFirst, KeepButtonStyle.Action)
            KeepDialogAction(stringResource(R.string.editor_discard), viewModel::lockDiscarding, KeepButtonStyle.Caution)
            KeepDialogAction(stringResource(R.string.action_cancel), viewModel::dismissDialog)
        }
        VaultDialog.SiteIcons -> KeepDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = stringResource(R.string.site_icons_title),
            message = stringResource(R.string.site_icons_message),
        ) {
            KeepDialogAction(stringResource(R.string.site_icons_confirm), viewModel::assignIcons, KeepButtonStyle.Action)
            KeepDialogAction(stringResource(R.string.action_cancel), viewModel::dismissDialog)
        }
        null -> Unit
    }
    val iconProgress by viewModel.iconProgress.collectAsStateWithLifecycle()
    iconProgress?.let { progress ->
        KeepDialog(
            onDismissRequest = {},
            title = stringResource(R.string.icons_assign_action),
            message = stringResource(R.string.site_icons_progress, progress.done, progress.total),
        ) {
            KeepDialogAction(stringResource(R.string.action_cancel), viewModel::cancelSiteIcons)
        }
    }
    SaveFlowHost(viewModel.saveFlow, suggestedCopyName, snackbar)
}

@Composable
private fun NameDialog(title: String, confirm: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val name = rememberTextFieldState(initial)
    val focus = remember { FocusRequester() }
    val text = name.text.toString()
    KeepDialog(onDismissRequest = onDismiss, title = title, content = {
        KeepTextField(
            state = name,
            label = stringResource(R.string.group_name_label),
            focus = focus,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            onKeyboardAction = { if (text.isNotBlank()) onConfirm(text) },
        )
    }) {
        KeepDialogAction(confirm, { onConfirm(text) }, KeepButtonStyle.Action, enabled = text.isNotBlank())
        KeepDialogAction(stringResource(R.string.action_cancel), onDismiss)
    }
}

@Composable
private fun DeleteGroupDialog(dialog: VaultDialog.DeleteGroup, onDelete: (permanent: Boolean) -> Unit, onDismiss: () -> Unit) {
    val empty = dialog.groups == 0 && dialog.entries == 0
    val message = when {
        empty && dialog.toBin -> stringResource(R.string.delete_group_empty_bin_message)
        empty -> stringResource(R.string.delete_group_empty_permanent_message)
        else -> {
            val contents = pluralStringResource(R.plurals.delete_group_entries, dialog.entries, dialog.entries) +
                if (dialog.groups > 0) " " + pluralStringResource(R.plurals.delete_group_subgroups, dialog.groups, dialog.groups) else ""
            contents + " " + stringResource(if (dialog.toBin) R.string.delete_group_bin_message else R.string.delete_group_permanent_message)
        }
    }
    KeepDialog(onDismissRequest = onDismiss, title = stringResource(R.string.delete_group_title, dialog.group.name), message = message) {
        if (dialog.toBin) KeepDialogAction(stringResource(R.string.delete_to_bin), { onDelete(false) }, KeepButtonStyle.Action)
        KeepDialogAction(stringResource(R.string.delete_permanently), { onDelete(true) }, KeepButtonStyle.Caution)
        KeepDialogAction(stringResource(R.string.action_cancel), onDismiss)
    }
}

@Composable
private fun SearchResults(state: VaultUiState.Searching, onOpenEntry: (EntryId) -> Unit, onCopy: (EntryId) -> Unit) {
    val results = state.results
    when {
        results == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            CircularProgressIndicator(modifier = Modifier.padding(32.dp), color = KeepDesign.colors.accent)
        }
        results.isEmpty() -> EmptyState(
            VazieIcons.Search,
            stringResource(R.string.vault_search_empty_title),
            stringResource(R.string.vault_search_empty_message),
        )
        else -> EntryList(results, onOpenEntry, onCopy)
    }
}

@Composable
private fun GroupContent(
    state: VaultUiState.Browsing,
    canEdit: Boolean,
    onOpenGroup: (GroupId) -> Unit,
    onOpenEntry: (EntryId) -> Unit,
    onCopy: (EntryId) -> Unit,
    onRename: (VaultGroup) -> Unit,
    onIcon: (VaultGroup) -> Unit,
    onDelete: (VaultGroup) -> Unit,
    onAdd: () -> Unit,
    onFilter: (VaultFilter) -> Unit,
) {
    Column {
        if (state.path.isEmpty() && state.filters.isNotEmpty()) {
            FilterChips(state.filters, state.filter, onFilter)
        }
        if (state.path.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.padding(top = 12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.path, key = { it.id.value }) { group ->
                    KeepChip(group.name, selected = group == state.path.last(), onClick = {})
                }
            }
        }
        if (state.groups.isEmpty() && state.entries.isEmpty()) {
            val firstRun = canEdit && state.path.isEmpty()
            EmptyState(
                VazieIcons.Folder,
                stringResource(if (firstRun) R.string.vault_new_title else R.string.vault_empty_title),
                stringResource(if (firstRun) R.string.vault_new_message else if (canEdit) R.string.vault_empty_message_editable else R.string.vault_empty_message),
                action = if (canEdit) {
                    { KeepButton(stringResource(if (firstRun) R.string.vault_first_entry else R.string.action_new_entry), onAdd, icon = VazieIcons.Add, fullWidth = true) }
                } else {
                    null
                },
            )
            return
        }
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 128.dp)) {
            if (state.groups.isNotEmpty()) {
                item { KeepSectionLabel(stringResource(R.string.vault_section_groups)) }
                item {
                    KeepCard {
                        state.groups.forEachIndexed { index, group ->
                            GroupRow(
                                name = group.name,
                                onClick = { onOpenGroup(group.id) },
                                actions = if (canEdit) GroupActions(onRename = { onRename(group) }, onDelete = { onDelete(group) }, onIcon = { onIcon(group) }) else null,
                                icon = group.icon,
                                divider = index > 0,
                            )
                        }
                    }
                }
            }
            if (state.entries.isNotEmpty()) {
                item { KeepSectionLabel(if (state.filter == VaultFilter.All) stringResource(R.string.vault_section_entries) else filterLabel(state.filter)) }
                item {
                    KeepCard {
                        state.entries.forEachIndexed { index, entry ->
                            EntryRow(entry, onClick = { onOpenEntry(entry.id) }, divider = index > 0, onCopy = if (entry.hasPassword) ({ onCopy(entry.id) }) else null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChips(filters: List<FilterChoice>, selected: VaultFilter, onFilter: (VaultFilter) -> Unit) {
    LazyRow(
        modifier = Modifier.padding(top = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(filters, key = { it.filter.toString() }) { choice ->
            KeepChip(
                label = filterLabel(choice.filter),
                selected = choice.filter == selected,
                onClick = { onFilter(choice.filter) },
                count = choice.count.toString(),
                icon = when (choice.filter) {
                    VaultFilter.All -> null
                    VaultFilter.Favorites -> VazieIcons.StarFilled
                    VaultFilter.Expired -> VazieIcons.Clock
                    is VaultFilter.Tag -> VazieIcons.Tag
                },
            )
        }
    }
}

@Composable
private fun filterLabel(filter: VaultFilter): String = when (filter) {
    VaultFilter.All -> stringResource(R.string.vault_filter_all)
    VaultFilter.Favorites -> stringResource(R.string.vault_filter_favorites)
    VaultFilter.Expired -> stringResource(R.string.vault_filter_expired)
    is VaultFilter.Tag -> filter.name
}

@Composable
private fun EntryList(entries: List<EntrySummary>, onOpenEntry: (EntryId) -> Unit, onCopy: (EntryId) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 128.dp)) {
        item {
            KeepCard {
                entries.forEachIndexed { index, entry ->
                    EntryRow(entry, onClick = { onOpenEntry(entry.id) }, divider = index > 0, onCopy = if (entry.hasPassword) ({ onCopy(entry.id) }) else null)
                }
            }
        }
    }
}
