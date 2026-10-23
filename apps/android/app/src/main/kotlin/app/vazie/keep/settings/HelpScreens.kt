package app.vazie.keep.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vazie.keep.R
import app.vazie.keep.links.FaqState
import app.vazie.keep.ui.designsystem.KeepButton
import app.vazie.keep.ui.designsystem.KeepButtonSize
import app.vazie.keep.ui.designsystem.KeepButtonStyle
import app.vazie.keep.ui.designsystem.KeepCard
import app.vazie.keep.ui.designsystem.KeepDesign
import app.vazie.keep.ui.designsystem.KeepHairline
import app.vazie.keep.ui.designsystem.KeepScreen
import app.vazie.keep.ui.designsystem.KeepSettingRow
import app.vazie.keep.ui.designsystem.KeepSpacing
import app.vazie.keep.ui.designsystem.KeepTopBar

/** The guides, in the order a new person meets the topics. The words are the app's own (`guide_*`). */
enum class Guide(val key: String, @StringRes val title: Int, @StringRes val summary: Int, @StringRes val body: Int) {
    FirstVault("first_vault", R.string.guide_first_vault_title, R.string.guide_first_vault_summary, R.string.guide_first_vault_body),
    Sync("sync", R.string.guide_sync_title, R.string.guide_sync_summary, R.string.guide_sync_body),
    Autofill("autofill", R.string.guide_autofill_title, R.string.guide_autofill_summary, R.string.guide_autofill_body),
    Biometric("biometric", R.string.guide_biometric_title, R.string.guide_biometric_summary, R.string.guide_biometric_body),
    Import("import", R.string.guide_import_title, R.string.guide_import_summary, R.string.guide_import_body),
    Plus("plus", R.string.guide_plus_title, R.string.guide_plus_summary, R.string.guide_plus_body),
    ;

    companion object {
        fun fromKey(key: String?): Guide? = entries.firstOrNull { it.key == key }
    }
}

@Composable
fun GuidesScreen(onBack: () -> Unit, onGuide: (Guide) -> Unit) {
    HelpPage(title = stringResource(R.string.guides_title), onBack = onBack) {
        KeepCard {
            Guide.entries.forEachIndexed { index, guide ->
                if (index > 0) KeepHairline()
                KeepSettingRow(
                    title = stringResource(guide.title),
                    subtitle = stringResource(guide.summary),
                    chevron = true,
                    onClick = { onGuide(guide) },
                )
            }
        }
    }
}

@Composable
fun GuideScreen(guide: Guide, onBack: () -> Unit) {
    val colors = KeepDesign.colors
    HelpPage(title = stringResource(R.string.guides_title), onBack = onBack) {
        Text(
            stringResource(guide.title),
            style = KeepDesign.type.headline,
            color = colors.text,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp).semantics { heading() },
        )
        KeepCard {
            Text(
                stringResource(guide.body),
                style = KeepDesign.type.body,
                color = colors.text,
                modifier = Modifier.padding(KeepSpacing.cardPadding),
            )
        }
    }
}

/**
 * Questions and answers: only what the backend published (edited in the admin panel), as last cached on the device.
 * With nothing cached the screen says it is loading, or that it could not load and offers to try again. A link opens
 * a page on the site (a path, against [siteOrigin]) or an `https` page.
 */
@Composable
fun FaqScreen(state: FaqState, siteOrigin: String, onRetry: () -> Unit, onBack: () -> Unit) {
    val colors = KeepDesign.colors
    val uriHandler = LocalUriHandler.current
    val russian = russian()
    HelpPage(title = stringResource(R.string.faq_title), onBack = onBack) {
        when {
            state is FaqState.Loading -> Message(stringResource(R.string.faq_loading))
            state is FaqState.Unavailable -> Message(stringResource(R.string.faq_unavailable)) {
                KeepButton(stringResource(R.string.faq_retry), onRetry, style = KeepButtonStyle.Text, size = KeepButtonSize.Pill)
            }
            state is FaqState.Ready && state.items.isEmpty() -> Message(stringResource(R.string.faq_empty))
            state is FaqState.Ready -> state.items.forEach { item ->
                KeepCard {
                    Column(Modifier.fillMaxWidth().padding(KeepSpacing.cardPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.question.pick(russian), style = KeepDesign.type.rowTitle, color = colors.text, modifier = Modifier.semantics { heading() })
                        Text(item.answer.pick(russian), style = KeepDesign.type.body, color = colors.textSecondary)
                        item.link?.let { link ->
                            // The admin panel's «Кнопка под ответом»: a button like a link's, not a bare text link.
                            KeepButton(
                                link.label.pick(russian),
                                { runCatching { uriHandler.openUri(if (link.isSitePath) siteOrigin + link.url else link.url) } },
                                style = KeepButtonStyle.Tonal,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String, action: @Composable () -> Unit = {}) {
    KeepCard {
        Column(Modifier.fillMaxWidth().padding(KeepSpacing.cardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = KeepDesign.type.body, color = KeepDesign.colors.textSecondary)
            action()
        }
    }
}

@Composable
private fun HelpPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    KeepScreen {
        Column(Modifier.fillMaxSize()) {
            KeepTopBar(title = title, onBack = onBack)
            Column(
                Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = KeepSpacing.gutter, vertical = 8.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { content() }
        }
    }
}
