package app.vazie.keep.chooser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vazie.keep.R
import app.vazie.keep.data.VaultDescriptor
import app.vazie.keep.ui.designsystem.KeepButtonStyle
import app.vazie.keep.ui.designsystem.KeepDesign
import app.vazie.keep.ui.designsystem.KeepDialog
import app.vazie.keep.ui.designsystem.KeepDialogAction

// Shown once, when the account has no Keep+ and the app knows several vaults. It can't be dismissed:
// until a main vault is chosen none of them can be edited.
@Composable
fun MainVaultDialog(vaults: List<VaultDescriptor>, onChoose: (String) -> Unit, onGetPlus: () -> Unit) {
    val colors = KeepDesign.colors
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    KeepDialog(
        onDismissRequest = {},
        title = stringResource(R.string.main_vault_title),
        message = stringResource(R.string.main_vault_message),
        content = {
            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                vaults.forEach { vault ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected == vault.id, role = Role.RadioButton) { selected = vault.id }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RadioButton(
                            selected = selected == vault.id,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = colors.accent, unselectedColor = colors.textSecondary),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(vault.name, style = KeepDesign.type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(vault.fileName, style = KeepDesign.type.rowSubtitle, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        actions = {
            KeepDialogAction(stringResource(R.string.totp_locked_action), onGetPlus)
            KeepDialogAction(stringResource(R.string.main_vault_confirm), { selected?.let(onChoose) }, style = KeepButtonStyle.Action, enabled = selected != null)
        },
    )
}
