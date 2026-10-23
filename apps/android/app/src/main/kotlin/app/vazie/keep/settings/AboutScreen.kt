package app.vazie.keep.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vazie.keep.BuildConfig
import app.vazie.keep.R
import app.vazie.keep.links.AppLink
import app.vazie.keep.links.LinkSection
import app.vazie.keep.links.inSection
import app.vazie.keep.ui.designsystem.KeepButton
import app.vazie.keep.ui.designsystem.KeepButtonStyle
import app.vazie.keep.ui.designsystem.KeepCard
import app.vazie.keep.ui.designsystem.KeepDesign
import app.vazie.keep.ui.designsystem.KeepHairline
import app.vazie.keep.ui.designsystem.KeepScreen
import app.vazie.keep.ui.designsystem.KeepSectionLabel
import app.vazie.keep.ui.designsystem.KeepSettingRow
import app.vazie.keep.ui.designsystem.KeepSpacing
import app.vazie.keep.ui.designsystem.KeepTopBar
import app.vazie.keep.ui.icons.VazieIcons

@Composable
fun AboutScreen(coreVersion: String, links: List<AppLink>, onBack: () -> Unit) {
    val colors = KeepDesign.colors
    val russian = russian()
    KeepScreen {
        Column(Modifier.fillMaxSize()) {
            KeepTopBar(title = stringResource(R.string.about_title), onBack = onBack)
            Column(
                Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = KeepSpacing.gutter),
            ) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(96.dp).clip(CircleShape).background(colors.accentContainer)) {
                        Image(
                            painterResource(R.drawable.vazovsky_portrait),
                            contentDescription = stringResource(R.string.about_portrait),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.about_developer_name), style = KeepDesign.type.title, color = colors.text, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.about_developer_role), style = KeepDesign.type.rowSubtitle, color = colors.textSecondary)
                    Text(
                        stringResource(R.string.about_developer_body),
                        style = KeepDesign.type.rowSubtitle,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp, start = 12.dp, end = 12.dp),
                    )
                }
                LinkGroup(R.string.about_author_title, links.inSection(LinkSection.AUTHOR), russian)
                LinkGroup(R.string.about_project_title, links.inSection(LinkSection.PROJECT), russian)
                LinkGroup(R.string.about_security_title, links.inSection(LinkSection.SECURITY), russian)
                LinkGroup(R.string.about_help_title, links.inSection(LinkSection.HELP), russian)
                links.inSection(LinkSection.SUPPORT).takeIf { it.isNotEmpty() }?.let { support ->
                    KeepSectionLabel(stringResource(R.string.about_support_title))
                    support.forEachIndexed { index, link -> SupportCard(link, russian, Modifier.padding(top = if (index == 0) 0.dp else 14.dp)) }
                }
                LinkGroup(R.string.about_feedback_title, links.inSection(LinkSection.FEEDBACK), russian)

                KeepSectionLabel(stringResource(R.string.about_app_title))
                KeepCard {
                    KeepSettingRow(stringResource(R.string.welcome_offline_title), subtitle = stringResource(R.string.welcome_offline_body))
                    KeepHairline()
                    KeepSettingRow(stringResource(R.string.welcome_account_title), subtitle = stringResource(R.string.welcome_account_body))
                    KeepHairline()
                    KeepSettingRow(stringResource(R.string.welcome_kdbx_title), subtitle = stringResource(R.string.about_app_body))
                }

                KeepSectionLabel(stringResource(R.string.about_facts_title))
                KeepCard {
                    Fact(R.string.about_version, BuildConfig.VERSION_NAME)
                    KeepHairline()
                    Fact(R.string.about_core_version, coreVersion)
                    KeepHairline()
                    Fact(R.string.about_format, stringResource(R.string.about_format_value))
                    KeepHairline()
                    Fact(R.string.about_license, stringResource(R.string.about_license_value))
                }

                KeepSectionLabel(stringResource(R.string.about_core_title))
                Text(
                    stringResource(R.string.about_core_body),
                    style = KeepDesign.type.rowSubtitle,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
                )
                KeepCard {
                    KeepSettingRow(stringResource(R.string.about_core_cap_files), subtitle = stringResource(R.string.about_core_cap_files_body))
                    KeepHairline()
                    KeepSettingRow(stringResource(R.string.about_core_cap_crypto), subtitle = stringResource(R.string.about_core_cap_crypto_body))
                    KeepHairline()
                    KeepSettingRow(stringResource(R.string.about_core_cap_match), subtitle = stringResource(R.string.about_core_cap_match_body))
                    KeepHairline()
                    KeepSettingRow(stringResource(R.string.about_core_cap_generator), subtitle = stringResource(R.string.about_core_cap_generator_body))
                }

                KeepSectionLabel(stringResource(R.string.about_core_why_title))
                KeepCard { Paragraph(R.string.about_core_why_body) }

                KeepSectionLabel(stringResource(R.string.about_core_boundary_title))
                KeepCard { Paragraph(R.string.about_core_boundary_body) }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun russian(): Boolean = LocalConfiguration.current.locales[0].language == "ru"

/** One card of links under its label; nothing at all when the backend put no link there. */
@Composable
private fun LinkGroup(title: Int, links: List<AppLink>, russian: Boolean) {
    if (links.isEmpty()) return
    KeepSectionLabel(stringResource(title))
    LinkCard(links, russian)
}

@Composable
private fun LinkCard(links: List<AppLink>, russian: Boolean, modifier: Modifier = Modifier) {
    if (links.isEmpty()) return
    val uriHandler = LocalUriHandler.current
    KeepCard(modifier) {
        links.forEachIndexed { index, link ->
            if (index > 0) KeepHairline()
            LinkRow(link, russian)
        }
    }
}

/** A link as a row; with a button text from the admin panel ([AppLink.action]) it also gets a button under it, which
 * opens the same address. Any link may have one. */
@Composable
internal fun LinkRow(link: AppLink, russian: Boolean, chevron: Boolean = false) {
    val uriHandler = LocalUriHandler.current
    val open = { runCatching { uriHandler.openUri(link.url) }; Unit }
    KeepSettingRow(
        title = link.title.pick(russian),
        subtitle = link.subtitle?.pick(russian),
        chevron = chevron,
        onClick = open,
    )
    link.action?.let { action ->
        KeepButton(
            text = action.pick(russian),
            onClick = open,
            style = KeepButtonStyle.Tonal,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
        )
    }
}

@Composable
private fun SupportCard(link: AppLink, russian: Boolean, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    KeepCard(modifier) {
        Column(Modifier.padding(KeepSpacing.cardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(link.title.pick(russian), style = KeepDesign.type.rowTitle, color = KeepDesign.colors.text)
            link.subtitle?.let { Text(it.pick(russian), style = KeepDesign.type.rowSubtitle, color = KeepDesign.colors.textSecondary) }
            KeepButton(
                text = (link.action ?: link.title).pick(russian),
                onClick = { runCatching { uriHandler.openUri(link.url) } },
                style = KeepButtonStyle.Tonal,
            )
        }
    }
}

@Composable
private fun Paragraph(body: Int) {
    Text(
        stringResource(body),
        style = KeepDesign.type.rowSubtitle,
        color = KeepDesign.colors.textSecondary,
        modifier = Modifier.padding(KeepSpacing.cardPadding),
    )
}

@Composable
private fun Fact(label: Int, value: String) {
    val colors = KeepDesign.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), style = KeepDesign.type.rowTitle, color = colors.text)
        Text(value, style = KeepDesign.type.rowSubtitle, color = colors.textSecondary)
    }
}
