package app.vazie.keep.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vazie.keep.R
import app.vazie.keep.data.VaultDescriptor
import app.vazie.keep.ui.designsystem.KeepButton
import app.vazie.keep.ui.designsystem.KeepButtonStyle
import app.vazie.keep.ui.designsystem.KeepDesign
import app.vazie.keep.ui.designsystem.KeepShapes
import app.vazie.keep.ui.designsystem.Monogram
import app.vazie.keep.ui.icons.VazieIcons

class VaultSwitcher(
    val vaults: List<VaultDescriptor>,
    val activeId: String?,
    val onSwitch: (String) -> Unit,
    val onAll: () -> Unit,
)

// Switching locks the open vault first: nothing a vault showed is carried over to the next one.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultSwitcherSheet(switcher: VaultSwitcher, onDismiss: () -> Unit) {
    val colors = KeepDesign.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.background,
        shape = KeepShapes.sheet,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.switcher_title),
                style = KeepDesign.type.headline,
                color = colors.text,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp).semantics { heading() },
            )
            switcher.vaults.forEach { vault ->
                val current = vault.id == switcher.activeId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(KeepShapes.card)
                        .background(if (current) colors.accentContainer else colors.surfaceList)
                        .clickable(role = Role.Button) { if (current) onDismiss() else switcher.onSwitch(vault.id) }
                        .semantics { selected = current }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Monogram(vault.name, size = 40.dp, radius = 12.dp)
                    Column(Modifier.weight(1f)) {
                        Text(vault.name, style = KeepDesign.type.rowTitle, color = if (current) colors.onAccentContainer else colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(vault.fileName, style = KeepDesign.type.rowSubtitle, color = if (current) colors.onAccentContainer else colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (current) Icon(VazieIcons.Check, contentDescription = stringResource(R.string.hub_current), tint = colors.onAccentContainer)
                }
            }
            KeepButton(stringResource(R.string.switcher_all), switcher.onAll, style = KeepButtonStyle.Tonal, icon = VazieIcons.Folder)
        }
    }
}
