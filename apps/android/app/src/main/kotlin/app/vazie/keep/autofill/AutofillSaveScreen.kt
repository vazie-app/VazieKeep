package app.vazie.keep.autofill

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vazie.keep.R
import app.vazie.keep.biometric.PromptText
import app.vazie.keep.ui.designsystem.HiddenDots
import app.vazie.keep.ui.designsystem.KeepButton
import app.vazie.keep.ui.designsystem.KeepButtonSize
import app.vazie.keep.ui.designsystem.KeepButtonStyle
import app.vazie.keep.ui.designsystem.KeepCard
import app.vazie.keep.ui.designsystem.KeepDesign
import app.vazie.keep.ui.designsystem.KeepHairline
import app.vazie.keep.ui.designsystem.KeepIconButton
import app.vazie.keep.ui.designsystem.KeepSectionLabel
import app.vazie.keep.ui.designsystem.KeepShapes
import app.vazie.keep.ui.designsystem.Monogram
import app.vazie.keep.ui.designsystem.monogramTone
import app.vazie.keep.ui.designsystem.secretText
import app.vazie.keep.ui.icons.VazieIcons

@Composable
fun AutofillSaveScreen(viewModel: AutofillSaveViewModel, onOpenApp: () -> Unit, onCancel: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val subject = (state as? SaveUiState.Unlock)?.subject.orEmpty()
    val prompt = PromptText(
        title = stringResource(R.string.biometric_prompt_title),
        subtitle = stringResource(R.string.autofill_save_prompt_subtitle, subject),
        negative = stringResource(R.string.biometric_prompt_use_password),
    )
    LaunchedEffect(Unit) { viewModel.start(prompt) }
    val updating = (state as? SaveUiState.Confirm)?.selected is SaveChoice.Update
    val avatar = when (val current = state) {
        is SaveUiState.Confirm -> current.subject
        is SaveUiState.Unlock -> current.subject
        else -> stringResource(R.string.app_name)
    }
    AutofillSheet(
        title = stringResource(if (updating) R.string.autofill_save_title_update else R.string.autofill_save_title),
        subtitle = (state as? SaveUiState.Confirm)?.subject,
        avatar = avatar,
        onCancel = onCancel,
    ) {
        when (val current = state) {
            SaveUiState.Loading, SaveUiState.Cancelled, is SaveUiState.Done -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = KeepDesign.colors.accent)
            }
            is SaveUiState.Confirm -> ConfirmPane(current, viewModel::select, viewModel::toggleShown, viewModel::confirm, onCancel)
            is SaveUiState.Unlock -> UnlockForm(
                summaryTitle = current.subject,
                summarySubtitle = current.username,
                biometric = current.biometric,
                busy = current.busy,
                message = current.message,
                keyFileName = current.keyFileName,
                password = viewModel.password,
                focusRequests = viewModel.focusRequests,
                confirmLabel = R.string.autofill_save_unlock_save,
                onUnlock = viewModel::unlockWithPassword,
                onBiometric = viewModel::useBiometric,
                onCancel = onCancel,
            )
            is SaveUiState.Saving -> Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (current.slow) LinearProgressIndicator(Modifier.fillMaxWidth().clip(KeepShapes.pill), color = KeepDesign.colors.accent, trackColor = KeepDesign.colors.surfaceRaised)
                Text(
                    stringResource(R.string.autofill_save_saving),
                    style = KeepDesign.type.bodyLarge,
                    color = KeepDesign.colors.text,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            is SaveUiState.Failed -> FailedPane(current.problem, onOpenApp, onCancel)
        }
    }
}

@Composable
internal fun ConfirmPane(
    state: SaveUiState.Confirm,
    onSelect: (SaveChoice) -> Unit,
    onToggleShown: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val update = state.selected as? SaveChoice.Update
    val colors = KeepDesign.colors
    Summary(state, onToggleShown)
    when (val plan = state.plan) {
        is SavePlan.Update -> {
            Choices(
                heading = stringResource(R.string.autofill_save_existing),
                options = listOf(plan.entry),
                selected = update?.entry,
                onSelect = { onSelect(SaveChoice.Update(it)) },
            )
            CreateRow(state.selected == SaveChoice.Create) { onSelect(SaveChoice.Create) }
        }
        is SavePlan.Choose -> {
            Choices(
                heading = stringResource(R.string.autofill_save_pick),
                options = plan.candidates,
                selected = update?.entry,
                onSelect = { onSelect(SaveChoice.Update(it)) },
            )
            CreateRow(state.selected == SaveChoice.Create) { onSelect(SaveChoice.Create) }
        }
        is SavePlan.Create -> if (plan.alternatives.isNotEmpty()) {
            CreateRow(state.selected == SaveChoice.Create) { onSelect(SaveChoice.Create) }
            Choices(
                heading = stringResource(R.string.autofill_save_other_entries),
                options = plan.alternatives,
                selected = update?.entry,
                onSelect = { onSelect(SaveChoice.Update(it)) },
            )
        }
    }
    if (update != null) {
        Text(stringResource(R.string.autofill_save_stays), style = KeepDesign.type.caption, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 4.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        KeepButton(stringResource(R.string.action_cancel), onCancel, modifier = Modifier.weight(0.8f), style = KeepButtonStyle.Tonal, size = KeepButtonSize.Medium)
        KeepButton(
            stringResource(if (update != null) R.string.autofill_save_update else R.string.autofill_save_create),
            onConfirm,
            modifier = Modifier.weight(1.3f),
            size = KeepButtonSize.Medium,
            enabled = state.selected != null,
            icon = VazieIcons.Check,
        )
    }
}

@Composable
private fun Summary(state: SaveUiState.Confirm, onToggleShown: () -> Unit) {
    val colors = KeepDesign.colors
    val label = stringResource(R.string.autofill_save_password)
    val hidden = stringResource(R.string.secret_hidden, label)
    val shown = stringResource(R.string.secret_shown, label)
    KeepCard {
        SummaryRow(stringResource(R.string.autofill_save_where)) {
            Text(state.subject, style = KeepDesign.type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        KeepHairline()
        SummaryRow(stringResource(R.string.autofill_save_username)) {
            Text(
                state.username.ifEmpty { stringResource(R.string.autofill_save_no_username) },
                style = KeepDesign.type.rowTitle,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        KeepHairline()
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The value is drawn but never exposed to accessibility; only "hidden" or "shown".
            Row(
                Modifier
                    .weight(1f)
                    .clearAndSetSemantics { contentDescription = if (state.shown != null) shown else hidden },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = KeepDesign.type.rowSubtitle, color = colors.textSecondary, modifier = Modifier.weight(1f))
                if (state.shown != null) {
                    Text(
                        secretText(state.shown, colors),
                        style = KeepDesign.type.secret.copy(fontSize = 14.sp, lineHeight = 22.sp),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    )
                } else {
                    HiddenDots(count = 12)
                }
            }
            KeepIconButton(
                if (state.shown != null) VazieIcons.EyeOff else VazieIcons.Eye,
                stringResource(if (state.shown != null) R.string.autofill_save_hide else R.string.autofill_save_show),
                onToggleShown,
            )
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(label, style = KeepDesign.type.rowSubtitle, color = KeepDesign.colors.textSecondary, modifier = Modifier.weight(1f))
        Box(contentAlignment = Alignment.CenterEnd) { value() }
    }
}

@Composable
private fun Choices(heading: String, options: List<IndexedEntry>, selected: IndexedEntry?, onSelect: (IndexedEntry) -> Unit) {
    KeepSectionLabel(heading)
    options.forEach { entry ->
        val title = entry.title.ifBlank { stringResource(R.string.entry_untitled) }
        Option(selected = entry == selected, onClick = { onSelect(entry) }) {
            Monogram(title, size = 38.dp, radius = 12.dp, tone = monogramTone(KeepDesign.colors, title), textSize = 16f)
            Column(Modifier.weight(1f)) {
                Text(title, style = KeepDesign.type.rowTitle, color = KeepDesign.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (entry.username.isNotEmpty()) {
                    Text(entry.username, style = KeepDesign.type.rowSubtitle, color = KeepDesign.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun CreateRow(selected: Boolean, onClick: () -> Unit) {
    Option(selected = selected, onClick = onClick) {
        Box(Modifier.size(38.dp).clip(KeepShapes.tile).background(KeepDesign.colors.accentContainer), contentAlignment = Alignment.Center) {
            Icon(VazieIcons.Add, contentDescription = null, tint = KeepDesign.colors.onAccentContainer, modifier = Modifier.size(20.dp))
        }
        Text(stringResource(R.string.autofill_save_create), style = KeepDesign.type.rowTitle, color = KeepDesign.colors.text, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Option(selected: Boolean, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val colors = KeepDesign.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(KeepShapes.field)
            .background(if (selected) colors.accentContainer else colors.surfaceList)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
        if (selected) Icon(VazieIcons.Check, contentDescription = null, tint = colors.onAccentContainer, modifier = Modifier.size(20.dp))
    }
}
