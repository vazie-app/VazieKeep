package app.vazie.keep.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vazie.keep.R
import app.vazie.keep.ui.designsystem.KeepBanner
import app.vazie.keep.ui.designsystem.KeepBannerTone
import app.vazie.keep.ui.designsystem.KeepButton
import app.vazie.keep.ui.designsystem.KeepButtonStyle
import app.vazie.keep.ui.designsystem.KeepDesign
import app.vazie.keep.ui.designsystem.KeepScreen
import app.vazie.keep.ui.designsystem.KeepSpacing
import app.vazie.keep.ui.designsystem.KeepTextField
import app.vazie.keep.ui.designsystem.KeepTopBar
import app.vazie.keep.ui.icons.VazieIcons

@Composable
fun DeleteAccountScreen(viewModel: AccountViewModel, onBack: () -> Unit) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val entitlement by viewModel.entitlement.collectAsStateWithLifecycle()
    val colors = KeepDesign.colors
    // Typing is read through the text state, so the button follows every keystroke.
    val ready = entitlement.account?.email?.let { viewModel.confirmsDeletion() } == true
    KeepScreen {
        Column(Modifier.fillMaxSize()) {
            KeepTopBar(title = stringResource(R.string.account_delete_title), onBack = onBack)
            Column(
                Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = KeepSpacing.gutter, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(stringResource(R.string.account_delete_body), style = KeepDesign.type.body, color = colors.text)
                Text(
                    stringResource(R.string.account_delete_type_prompt, entitlement.account?.email.orEmpty()),
                    style = KeepDesign.type.rowSubtitle,
                    color = colors.textSecondary,
                )
                KeepTextField(
                    state = viewModel.deleteEmail,
                    label = stringResource(R.string.account_email_label),
                    enabled = !ui.deleting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                )
                if (ui.deleteFailed) KeepBanner(stringResource(R.string.account_delete_failed), tone = KeepBannerTone.Error)
                KeepButton(
                    stringResource(R.string.account_delete_confirm),
                    { viewModel.deleteAccount(onDeleted = onBack) },
                    style = KeepButtonStyle.Destructive,
                    icon = VazieIcons.Trash,
                    enabled = ready && !ui.deleting,
                    loading = ui.deleting,
                )
            }
        }
    }
}
