package app.vazie.keep.ui.designsystem

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.vazie.keep.ui.icons.VazieIcons

// The choices on Appearance and the Keep+ mark, drawn the way Vazie VPN draws them: a theme as a miniature of the
// screen it makes, a launcher icon as a launcher icon on its plate, the chosen one ringed in the accent with a check.

/** Which palette a theme card previews. */
enum class ThemePreview { Light, Dark, System }

/** A theme shown as the thing it makes. [ThemePreview.System] is the light screen with its right half dark. */
@Composable
fun KeepThemeChoice(label: String, preview: ThemePreview, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val colors = KeepDesign.colors
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .width(ThemeCardWidth)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(ThemeScreenHeight)
                .clip(shape)
                .border(if (selected) SelectedBorder else 1.dp, if (selected) colors.accent else colors.hairline, shape),
        ) {
            when (preview) {
                ThemePreview.Light -> MiniScreen(LightKeepColors)
                ThemePreview.Dark -> MiniScreen(DarkKeepColors)
                ThemePreview.System -> {
                    MiniScreen(LightKeepColors)
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clipToBounds()
                            .drawWithContent { clipRect(left = size.width / 2) { this@drawWithContent.drawContent() } },
                    ) { MiniScreen(DarkKeepColors) }
                }
            }
            if (selected) SelectedMark(Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        Text(
            label,
            style = KeepDesign.type.badge,
            color = if (selected) colors.text else colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** A surface on the background with a title and a line of text, and a filled control under it. A picture, not a target. */
@Composable
private fun MiniScreen(palette: KeepColors) {
    Column(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(palette.surfaceList)
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Bar(palette.accent, 6.dp, 6.dp, corner = 6.dp)
                Bar(palette.text, 34.dp, 6.dp)
            }
            Bar(palette.textSecondary, 48.dp, 4.dp)
        }
        Bar(palette.action, 64.dp, 14.dp, corner = 6.dp)
    }
}

@Composable
private fun Bar(color: Color, width: Dp, height: Dp, corner: Dp = 2.dp) {
    Box(Modifier.width(width).height(height).clip(RoundedCornerShape(corner)).background(color))
}

/** A launcher icon on its [plate], as the launcher will show it. */
@Composable
fun KeepIconChoice(
    label: String,
    plate: Brush,
    @DrawableRes foreground: Int,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KeepDesign.colors
    val shape = RoundedCornerShape(IconTileCorner)
    Column(
        modifier = modifier
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(IconTileSize)
                .clip(shape)
                .background(plate, shape)
                .border(if (selected) SelectedBorder else 1.dp, if (selected) colors.accent else colors.hairline, shape),
            contentAlignment = Alignment.Center,
        ) {
            // A launcher shows the middle two thirds of the 108 dp layer, and the layer is inset by 14%.
            Image(painterResource(foreground), contentDescription = null, modifier = Modifier.requiredSize(IconTileSize * 1.07f))
            if (selected) SelectedMark(Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        Text(
            label,
            style = KeepDesign.type.badge,
            color = if (selected) colors.text else colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** The check on a chosen card. The card carries the selected state; the mark says nothing of its own. */
@Composable
private fun SelectedMark(modifier: Modifier = Modifier) {
    val colors = KeepDesign.colors
    Box(
        modifier
            .size(20.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.accent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(VazieIcons.Check, contentDescription = null, tint = if (colors.isDark) colors.background else colors.surfaceList, modifier = Modifier.size(13.dp))
    }
}

/** The Keep+ mark, framed, as Vazie VPN marks VPN Plus: "Keep+" when [compact]. TalkBack hears "Keep Plus". */
@Composable
fun KeepPlusBadge(modifier: Modifier = Modifier, compact: Boolean = true) {
    val colors = KeepDesign.colors
    Row(
        modifier = modifier
            .heightIn(min = 26.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Brush.horizontalGradient(listOf(colors.accentContainer, colors.surfaceField)))
            .border(1.dp, colors.accent.copy(alpha = 0.35f), RoundedCornerShape(9.dp))
            .clearAndSetSemantics { contentDescription = "Keep Plus" }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = KeepDesign.type.badge.copy(fontWeight = FontWeight.Bold)
        Text("Keep", style = style, color = colors.onAccentContainer)
        Text(if (compact) "+" else " Plus", style = style, color = colors.accent)
    }
}

private val ThemeCardWidth = 96.dp
private val ThemeScreenHeight = 108.dp
private val IconTileSize = 72.dp
private val IconTileCorner = 20.dp
private val SelectedBorder = 2.dp
