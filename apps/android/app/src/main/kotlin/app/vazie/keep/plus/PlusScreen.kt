package app.vazie.keep.plus

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vazie.keep.R
import app.vazie.keep.ui.designsystem.KeepBanner
import app.vazie.keep.ui.designsystem.KeepBannerTone
import app.vazie.keep.ui.designsystem.KeepButton
import app.vazie.keep.ui.designsystem.KeepButtonStyle
import app.vazie.keep.ui.designsystem.KeepCard
import app.vazie.keep.ui.designsystem.KeepDesign
import app.vazie.keep.ui.designsystem.KeepHairline
import app.vazie.keep.ui.designsystem.KeepPlusBadge
import app.vazie.keep.ui.designsystem.KeepScreen
import app.vazie.keep.ui.designsystem.KeepSectionLabel
import app.vazie.keep.ui.designsystem.KeepSpacing
import app.vazie.keep.ui.designsystem.KeepTopBar
import app.vazie.keep.ui.icons.VazieIcons
import java.util.Date

/** Keep+ the account already has: for good, or until [untilSeconds] (`null`: no end date). */
data class CurrentPlus(val lifetime: Boolean, val untilSeconds: Long?)

@Composable
fun PlusScreen(viewModel: PlusViewModel, signedIn: Boolean, onSignIn: () -> Unit, onBack: () -> Unit, current: CurrentPlus? = null) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(Unit) { viewModel.openUrl.collect { uriHandler.openUri(it) } }
    LifecycleResumeEffect(Unit) {
        viewModel.onResumed()
        onPauseOrDispose {}
    }
    KeepScreen {
        Column(Modifier.fillMaxSize()) {
            KeepTopBar(title = stringResource(if (current != null) R.string.plus_manage_title else R.string.plus_title), onBack = onBack)
            Column(
                Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = KeepSpacing.gutter, vertical = 8.dp),
            ) {
                if (current != null) {
                    CurrentPlusCard(current)
                    Spacer(Modifier.height(14.dp))
                }
                if (current?.lifetime == true) return@Column
                AnimatedContent(
                    targetState = ui.prices != null,
                    transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) },
                    label = "plus",
                ) { loaded ->
                    Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        val prices = ui.prices
                        if (loaded && prices != null) Loaded(viewModel, ui, prices, signedIn, onSignIn, extending = current != null) else Waiting(ui.loading, viewModel::load)
                    }
                }
            }
        }
    }
}

@Composable
private fun Waiting(loading: Boolean, retry: () -> Unit) {
    if (loading) {
        Text(stringResource(R.string.plus_loading), style = KeepDesign.type.rowSubtitle, color = KeepDesign.colors.textSecondary)
    } else {
        KeepBanner(stringResource(R.string.plus_unavailable), tone = KeepBannerTone.Caution)
        KeepButton(stringResource(R.string.plus_retry), retry, style = KeepButtonStyle.Tonal)
    }
}

@Composable
private fun Loaded(viewModel: PlusViewModel, ui: PlusUiState, prices: KeepPrices, signedIn: Boolean, onSignIn: () -> Unit, extending: Boolean) {
    val colors = KeepDesign.colors
    if (extending) {
        KeepSectionLabel(stringResource(R.string.plus_manage_extend))
        Text(stringResource(R.string.plus_manage_extend_body), style = KeepDesign.type.rowSubtitle, color = colors.textSecondary)
    } else {
        Text(stringResource(R.string.plus_intro), style = KeepDesign.type.rowSubtitle, color = colors.textSecondary)
        KeepSectionLabel(stringResource(R.string.plus_plans))
    }
    KeepCard {
        prices.plans.forEachIndexed { index, plan ->
            if (index > 0) KeepHairline()
            PlanRow(plan, selected = ui.selectedPlan == plan.id) { viewModel.selectPlan(plan.id) }
        }
    }
    if (!prices.checkoutAvailable) {
        KeepBanner(stringResource(R.string.plus_checkout_soon), tone = KeepBannerTone.Info)
    } else {
        Text(stringResource(R.string.plus_on_site), style = KeepDesign.type.rowSubtitle, color = colors.textSecondary)
    }
    if (prices.checkoutAvailable && !signedIn && !ui.activated) {
        KeepBanner(stringResource(R.string.plus_sign_in_first), tone = KeepBannerTone.Info)
        KeepButton(stringResource(R.string.plus_sign_in), onSignIn, style = KeepButtonStyle.Tonal)
    }
    if (ui.activated) {
        KeepBanner(stringResource(R.string.plus_activated), tone = KeepBannerTone.Info)
    } else if (ui.awaitingPayment) {
        KeepBanner(
            stringResource(
                when {
                    ui.checking -> R.string.plus_checking
                    ui.notSeen -> R.string.plus_not_seen
                    else -> R.string.plus_awaiting
                },
            ),
            tone = KeepBannerTone.Info,
        )
        KeepButton(stringResource(R.string.plus_check), viewModel::checkNow, style = KeepButtonStyle.Tonal, enabled = !ui.checking, loading = ui.checking)
    }
    if (!ui.activated) {
        KeepButton(
            stringResource(R.string.plus_continue),
            viewModel::openSite,
            enabled = prices.checkoutAvailable && ui.selectedPlan != null && signedIn,
        )
    }
}

/** What the account has now: Keep+, until when, as on the VPN Plus screen. */
@Composable
private fun CurrentPlusCard(current: CurrentPlus) {
    val colors = KeepDesign.colors
    val context = LocalContext.current
    KeepCard {
        Column(Modifier.padding(KeepSpacing.cardPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The same mark and name as the Keep+ row in Settings.
                KeepPlusBadge()
                Text(stringResource(R.string.settings_plus_title), style = KeepDesign.type.rowTitle, color = colors.text)
            }
            Text(
                when {
                    current.lifetime -> stringResource(R.string.plus_manage_lifetime)
                    current.untilSeconds == null -> stringResource(R.string.plus_manage_open)
                    else -> stringResource(R.string.plus_manage_until, DateFormat.getMediumDateFormat(context).format(Date(current.untilSeconds * 1000)))
                },
                style = KeepDesign.type.rowSubtitle,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun PlanRow(plan: PlanPrice, selected: Boolean, onClick: () -> Unit) {
    val title = stringResource(
        when {
            plan.isLifetime -> R.string.plus_plan_lifetime
            plan.id.endsWith("year") -> R.string.plus_plan_year
            else -> R.string.plus_plan_month
        },
    )
    val note = when {
        plan.isLifetime && plan.earlyRemaining != null -> stringResource(R.string.plus_early_left, plan.earlyRemaining)
        plan.isLifetime -> stringResource(R.string.plus_lifetime_note)
        plan.id.endsWith("year") -> stringResource(R.string.plus_year_note)
        else -> stringResource(R.string.plus_month_note)
    }
    ChoiceRow(title, note, selected, price = formatRubles(plan.amountKopecks), regular = plan.regularKopecks?.let(::formatRubles), onClick = onClick)
}

@Composable
private fun ChoiceRow(title: String, note: String?, selected: Boolean, price: String? = null, regular: String? = null, onClick: () -> Unit) {
    val colors = KeepDesign.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(KeepSpacing.cardPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = KeepDesign.type.rowTitle, color = colors.text)
            if (note != null) Text(note, style = KeepDesign.type.rowSubtitle, color = colors.textSecondary)
        }
        if (price != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(price, style = KeepDesign.type.rowTitle, color = colors.text)
                if (regular != null) Text(regular, style = KeepDesign.type.caption.copy(textDecoration = TextDecoration.LineThrough), color = colors.textTertiary)
            }
        }
        Icon(
            VazieIcons.Check,
            contentDescription = null,
            tint = if (selected) colors.accent else colors.textTertiary.copy(alpha = 0f),
            modifier = Modifier.size(22.dp),
        )
    }
}
