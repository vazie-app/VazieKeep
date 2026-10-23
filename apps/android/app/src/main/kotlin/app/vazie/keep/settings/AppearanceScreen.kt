package app.vazie.keep.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vazie.keep.R
import app.vazie.keep.ui.designsystem.KeepBanner
import app.vazie.keep.ui.designsystem.KeepBannerTone
import app.vazie.keep.ui.designsystem.KeepDesign
import app.vazie.keep.ui.designsystem.KeepIconChoice
import app.vazie.keep.ui.designsystem.KeepScreen
import app.vazie.keep.ui.designsystem.KeepSectionLabel
import app.vazie.keep.ui.designsystem.KeepSpacing
import app.vazie.keep.ui.designsystem.KeepThemeChoice
import app.vazie.keep.ui.designsystem.KeepTopBar
import app.vazie.keep.ui.designsystem.ThemePreview

/** How Keep looks, laid out as Vazie VPN's Appearance: the theme, the launcher icon, and the plate it stands on —
 * Milk, the icon's own field in its colour, or Ink. */
@Composable
fun AppearanceScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val launcher by viewModel.appIcon.collectAsStateWithLifecycle()
    val plates by viewModel.appIconPlates.collectAsStateWithLifecycle()
    val iconFailed by viewModel.iconFailed.collectAsStateWithLifecycle()
    val colors = KeepDesign.colors
    KeepScreen {
        Column(Modifier.fillMaxSize()) {
            KeepTopBar(title = stringResource(R.string.settings_appearance), onBack = onBack)
            Column(
                Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = KeepSpacing.gutter, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KeepSectionLabel(stringResource(R.string.settings_theme))
                ChoiceFlow(ThemeMode.entries, columns = Int.MAX_VALUE) { mode ->
                    KeepThemeChoice(
                        label = themeLabel(mode),
                        preview = when (mode) {
                            ThemeMode.System -> ThemePreview.System
                            ThemeMode.Light -> ThemePreview.Light
                            ThemeMode.Dark -> ThemePreview.Dark
                        },
                        selected = mode == settings.themeMode,
                        onSelect = { viewModel.setTheme(mode) },
                    )
                }

                KeepSectionLabel(stringResource(R.string.settings_app_icon))
                // Every icon on the plate last chosen under it, as tapping it would set it: a plate belongs to one icon, not to all.
                ChoiceFlow(AppIcon.entries, columns = ICON_COLUMNS) { icon ->
                    KeepIconChoice(
                        label = stringResource(icon.label),
                        plate = plateBrush(plates[icon] ?: IconPlate.Default, icon),
                        foreground = icon.foreground,
                        selected = icon == launcher.icon,
                        onSelect = { viewModel.selectIcon(icon) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(stringResource(R.string.settings_app_icon_hint), style = KeepDesign.type.caption, color = colors.textSecondary)

                KeepSectionLabel(stringResource(R.string.settings_app_icon_plate))
                ChoiceFlow(IconPlate.entries, columns = ICON_COLUMNS) { plate ->
                    KeepIconChoice(
                        label = stringResource(plate.label(launcher.icon)),
                        plate = plateBrush(plate, launcher.icon),
                        foreground = launcher.icon.foreground,
                        selected = plate == launcher.plate,
                        onSelect = { viewModel.selectPlate(plate) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (iconFailed) KeepBanner(stringResource(R.string.app_icon_failed), tone = KeepBannerTone.Error)
                Text(
                    stringResource(R.string.settings_app_icon_plate_hint),
                    style = KeepDesign.type.caption,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }
}

/** A grid of choices, [columns] to a row (the last row padded, so the tiles keep their width), as one selectable group. */
@Composable
private fun <T> ChoiceFlow(options: List<T>, columns: Int, choice: @Composable FlowRowScope.(T) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = columns,
    ) {
        options.forEach { choice(it) }
        if (columns != Int.MAX_VALUE) {
            repeat((columns - options.size % columns) % columns) { Column(Modifier.weight(1f)) {} }
        }
    }
}

/** [plate] under [icon] as the launcher paints it: the same colours, the same 315° gradient. */
@Composable
private fun plateBrush(plate: IconPlate, icon: AppIcon): Brush {
    val stops: List<Color> = plate.colors(icon).map { colorResource(it) }
    return if (stops.size == 1) Brush.linearGradient(listOf(stops[0], stops[0]))
    else Brush.linearGradient(stops, start = Offset(0f, Float.POSITIVE_INFINITY), end = Offset(Float.POSITIVE_INFINITY, 0f))
}

private const val ICON_COLUMNS = 4
