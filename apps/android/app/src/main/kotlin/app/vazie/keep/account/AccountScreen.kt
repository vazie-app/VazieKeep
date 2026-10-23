package app.vazie.keep.account

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vazie.keep.BuildConfig
import app.vazie.keep.R
import app.vazie.keep.core.KeepAccessState
import app.vazie.keep.ui.designsystem.KeepBanner
import app.vazie.keep.ui.designsystem.KeepBannerTone
import app.vazie.keep.ui.designsystem.KeepButton
import app.vazie.keep.ui.designsystem.KeepButtonSize
import app.vazie.keep.ui.designsystem.KeepButtonStyle
import app.vazie.keep.ui.designsystem.KeepCard
import app.vazie.keep.ui.designsystem.KeepChip
import app.vazie.keep.ui.designsystem.KeepDesign
import app.vazie.keep.ui.designsystem.KeepDialog
import app.vazie.keep.ui.designsystem.KeepDialogAction
import app.vazie.keep.ui.designsystem.KeepHairline
import app.vazie.keep.ui.designsystem.KeepScreen
import app.vazie.keep.ui.designsystem.KeepSectionLabel
import app.vazie.keep.ui.designsystem.KeepSettingRow
import app.vazie.keep.ui.designsystem.KeepSpacing
import app.vazie.keep.ui.designsystem.KeepTextField
import app.vazie.keep.ui.designsystem.KeepTopBar
import app.vazie.keep.ui.icons.VazieIcons
import java.util.Date

@Composable
fun AccountScreen(viewModel: AccountViewModel, onBack: () -> Unit, onGetPlus: () -> Unit, onDeleteAccount: () -> Unit) {
    val entitlement by viewModel.entitlement.collectAsStateWithLifecycle()
    val ui by viewModel.state.collectAsStateWithLifecycle()
    KeepScreen {
        Column(Modifier.fillMaxSize()) {
            KeepTopBar(title = stringResource(R.string.account_title), onBack = onBack)
            Column(
                Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = KeepSpacing.gutter, vertical = 8.dp),
            ) {
                AnimatedContent(
                    targetState = entitlement.account == null,
                    transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) },
                    label = "account",
                ) { signedOut ->
                    Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (signedOut) SignedOut(viewModel, ui) else SignedIn(viewModel, entitlement, ui, onGetPlus, onDeleteAccount)
                    }
                }
            }
        }
    }
    if (ui.confirmingSignOut) {
        KeepDialog(
            onDismissRequest = { viewModel.askSignOut(false) },
            title = stringResource(R.string.account_sign_out_title),
            message = stringResource(R.string.account_sign_out_message),
            actions = {
                KeepDialogAction(stringResource(R.string.action_cancel), { viewModel.askSignOut(false) })
                KeepDialogAction(stringResource(R.string.account_sign_out), viewModel::signOut, style = KeepButtonStyle.Caution)
            },
        )
    }
}

@Composable
private fun SignedOut(viewModel: AccountViewModel, ui: AccountUiState) {
    val colors = KeepDesign.colors
    KeepCard {
        Column(Modifier.padding(KeepSpacing.cardPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.account_intro_title), style = KeepDesign.type.rowTitle, color = colors.text)
            Text(stringResource(R.string.account_intro_body), style = KeepDesign.type.rowSubtitle, color = colors.textSecondary)
        }
    }
    val sentTo = ui.codeSentTo
    if (sentTo == null) {
        Column {
            KeepTextField(
                state = viewModel.email,
                label = stringResource(R.string.account_email_label),
                enabled = !ui.busy,
                error = if (ui.message == AccountMessage.InvalidEmail) stringResource(R.string.account_error_email) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                onKeyboardAction = viewModel::sendCode,
            )
            EmailSuggestionRow(viewModel.email)
        }
        KeepButton(stringResource(R.string.account_send_code), viewModel::sendCode, enabled = !ui.busy, loading = ui.busy)
        PrivacyNote()
    } else {
        Text(stringResource(R.string.account_code_sent, sentTo), style = KeepDesign.type.rowSubtitle, color = colors.textSecondary)
        KeepTextField(
            state = viewModel.code,
            label = stringResource(R.string.account_code_label),
            enabled = !ui.busy,
            error = if (ui.message == AccountMessage.InvalidCode) stringResource(R.string.account_error_code) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            onKeyboardAction = viewModel::signIn,
        )
        KeepButton(stringResource(R.string.account_sign_in), viewModel::signIn, enabled = !ui.busy, loading = ui.busy && !ui.resending)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KeepButton(
                if (ui.resendIn > 0) {
                    stringResource(R.string.account_send_again_in, "%d:%02d".format(ui.resendIn / 60, ui.resendIn % 60))
                } else {
                    stringResource(R.string.account_send_again)
                },
                viewModel::sendCode,
                style = KeepButtonStyle.Tonal,
                size = KeepButtonSize.Medium,
                icon = VazieIcons.Mail,
                enabled = !ui.busy && ui.resendIn == 0,
                loading = ui.resending,
            )
            KeepButton(
                stringResource(R.string.account_change_email),
                viewModel::changeEmail,
                style = KeepButtonStyle.Tonal,
                size = KeepButtonSize.Medium,
                icon = VazieIcons.Pencil,
                enabled = !ui.busy,
            )
        }
    }
    when (ui.message) {
        AccountMessage.RateLimited -> Appear { KeepBanner(stringResource(R.string.account_error_rate), tone = KeepBannerTone.Error) }
        AccountMessage.Unavailable -> Appear { KeepBanner(stringResource(R.string.account_error_unavailable), tone = KeepBannerTone.Caution) }
        else -> Unit
    }
    if (BuildConfig.DEV_SIGN_IN) {
        KeepButton(stringResource(R.string.account_dev_sign_in), viewModel::devSignIn, style = KeepButtonStyle.Tonal, enabled = !ui.busy)
    }
}

/** What signing in starts, and where the policy is: information, not a checkbox — nothing is held back on it. */
@Composable
private fun PrivacyNote() {
    val uriHandler = LocalUriHandler.current
    Text(stringResource(R.string.account_privacy_note), style = KeepDesign.type.rowSubtitle, color = KeepDesign.colors.textSecondary)
    KeepButton(
        stringResource(R.string.account_privacy_link),
        { runCatching { uriHandler.openUri(PRIVACY_POLICY_URL) } },
        style = KeepButtonStyle.Text,
    )
}

private const val PRIVACY_POLICY_URL = "https://vazie.app/legal/privacy"

@Composable
private fun SignedIn(viewModel: AccountViewModel, entitlement: KeepEntitlementState, ui: AccountUiState, onGetPlus: () -> Unit, onDeleteAccount: () -> Unit) {
    val access = entitlement.access
    val context = LocalContext.current
    val date = { seconds: Long -> DateFormat.getMediumDateFormat(context).format(Date(seconds * 1000)) }
    val end = entitlement.lease?.entitlementExpiresAt
    KeepCard {
        KeepSettingRow(
            title = stringResource(R.string.account_email_label),
            subtitle = entitlement.account?.email ?: stringResource(R.string.account_title),
        )
        KeepHairline()
        KeepSettingRow(
            title = stringResource(R.string.account_status_row),
            subtitle = stringResource(if (entitlement.problem == null) R.string.account_status_active else R.string.account_status_unchecked),
        )
        KeepHairline()
        KeepSettingRow(
            title = stringResource(R.string.account_plan_plus),
            subtitle = when {
                access.state == KeepAccessState.PlusLifetime -> stringResource(R.string.account_plan_lifetime)
                !access.plusFeatures -> stringResource(R.string.account_plan_free)
                end == null -> stringResource(R.string.account_plan_forever)
                else -> stringResource(R.string.account_plan_until, date(end))
            },
            chevron = true,
            onClick = onGetPlus,
        )
        val until = access.leaseValidUntil
        if (access.plusFeatures && until != null) {
            KeepHairline()
            KeepSettingRow(title = stringResource(R.string.account_offline_until, date(until)))
        }
    }
    if (access.state == KeepAccessState.PlusExpired && entitlement.problem == null) {
        Appear { KeepBanner(stringResource(R.string.account_expired), tone = KeepBannerTone.Caution) }
    }
    entitlement.problem?.let { problem ->
        Appear { KeepBanner(
            stringResource(
                when (problem) {
                    EntitlementProblem.Offline -> R.string.account_problem_offline
                    EntitlementProblem.SessionExpired -> R.string.account_problem_session
                    EntitlementProblem.ClockOff -> R.string.account_problem_clock
                    EntitlementProblem.Failed -> R.string.account_problem_failed
                },
            ),
            tone = KeepBannerTone.Caution,
        ) }
    }
    if (entitlement.problem != null || access.state == KeepAccessState.PlusExpired) {
        KeepButton(
            stringResource(R.string.account_refresh),
            viewModel::refresh,
            style = KeepButtonStyle.Tonal,
            enabled = !entitlement.refreshing,
            loading = entitlement.refreshing,
        )
    }
    if (!access.plusFeatures) KeepButton(stringResource(R.string.account_get_plus), onGetPlus)
    KeepButton(stringResource(R.string.account_sign_out), { viewModel.askSignOut(true) }, style = KeepButtonStyle.Destructive, enabled = !ui.busy)
    // Apart from everything else, and only a way to the deletion screen, never the deletion itself.
    Spacer(Modifier.height(10.dp))
    KeepCard {
        KeepSettingRow(
            title = stringResource(R.string.account_delete),
            subtitle = stringResource(R.string.account_delete_row_body),
            icon = VazieIcons.Trash,
            chevron = true,
            destructive = true,
            onClick = { if (!ui.busy) onDeleteAccount() },
        )
    }
}

@Composable
private fun Appear(content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(state, enter = fadeIn(tween(260)) + expandVertically(tween(260))) { content() }
}

@Composable
private fun EmailSuggestionRow(email: TextFieldState) {
    val current = EmailSuggestions.completions(email.text.toString())
    var shown by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(current) { if (current.isNotEmpty()) shown = current }
    AnimatedVisibility(current.isNotEmpty(), enter = fadeIn(tween(200)) + expandVertically(tween(200)), exit = fadeOut(tween(120)) + shrinkVertically(tween(120))) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (current.ifEmpty { shown }).forEach { address ->
                KeepChip(address, selected = false, onClick = { email.setTextAndPlaceCursorAtEnd(address) })
            }
        }
    }
}
