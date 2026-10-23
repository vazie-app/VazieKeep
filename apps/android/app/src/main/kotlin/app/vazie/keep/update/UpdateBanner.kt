package app.vazie.keep.update

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.vazie.keep.R
import app.vazie.keep.review.openFirst
import app.vazie.keep.settings.russian
import app.vazie.keep.ui.designsystem.KeepBanner
import app.vazie.keep.ui.designsystem.KeepBannerTone
import app.vazie.keep.ui.designsystem.KeepButton
import app.vazie.keep.ui.designsystem.KeepButtonSize
import app.vazie.keep.ui.designsystem.KeepButtonStyle

@Composable
fun UpdateBanner(state: UpdateState, onLater: () -> Unit, modifier: Modifier = Modifier) {
    val release = when (state) {
        UpdateState.Current -> return
        is UpdateState.Available -> state.release
        is UpdateState.Required -> state.release
    }
    val required = state is UpdateState.Required
    val context = LocalContext.current
    val text = release.message?.pick(russian())
        ?: stringResource(if (required) R.string.update_required else R.string.update_available, release.latestVersionName)
    KeepBanner(text, modifier, tone = if (required) KeepBannerTone.Caution else KeepBannerTone.Info) {
        KeepButton(stringResource(R.string.update_download), { openFirst(context, release.downloadLinks(context.packageName)) }, size = KeepButtonSize.Pill)
        if (!required) KeepButton(stringResource(R.string.update_later), onLater, style = KeepButtonStyle.Text, size = KeepButtonSize.Pill)
    }
}
