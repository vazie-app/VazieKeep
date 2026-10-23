package app.vazie.keep.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vazie.keep.R
import app.vazie.keep.ui.designsystem.KeepDesign
import app.vazie.keep.ui.designsystem.KeepDialog
import app.vazie.keep.ui.designsystem.KeepDialogAction
import app.vazie.keep.ui.designsystem.KeepShapes
import app.vazie.keep.ui.designsystem.KeepTextField
import app.vazie.keep.ui.icons.ItemIcon
import app.vazie.keep.ui.icons.VazieIcons

// Icons whose name (in the app's language) or English keywords hold every word of the query.
fun matchingIcons(query: String, label: (ItemIcon) -> String): List<ItemIcon> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return ItemIcon.entries
    return ItemIcon.entries.filter { icon ->
        val text = (label(icon) + " " + icon.keywords).lowercase()
        words.all { it in text }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IconPickerDialog(title: String, selected: ItemIcon?, onSelect: (ItemIcon) -> Unit, onDismiss: () -> Unit) {
    val colors = KeepDesign.colors
    val resources = LocalResources.current
    val query = rememberTextFieldState()
    val icons = matchingIcons(query.text.toString()) { resources.getString(it.label) }
    KeepDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                KeepTextField(query, label = null, placeholder = stringResource(R.string.icon_search), leadingIcon = VazieIcons.Search, height = 48)
                if (icons.isEmpty()) {
                    Text(stringResource(R.string.icon_none_found), style = KeepDesign.type.body, color = colors.textSecondary)
                }
                FlowRow(
                    Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    icons.forEach { icon ->
                        val name = stringResource(icon.label)
                        val chosen = icon == selected
                        Box(
                            Modifier
                                .size(52.dp)
                                .clip(KeepShapes.tile)
                                .background(if (chosen) colors.accentContainer else colors.surfaceField)
                                .then(if (chosen) Modifier.border(2.dp, colors.accent, KeepShapes.tile) else Modifier)
                                .clickable(role = Role.RadioButton) { onSelect(icon) }
                                .semantics {
                                    contentDescription = name
                                    this.selected = chosen
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(icon.image, contentDescription = null, tint = if (chosen) colors.onAccentContainer else colors.text, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        },
        actions = { KeepDialogAction(stringResource(R.string.action_cancel), onDismiss) },
    )
}
