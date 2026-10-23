package app.vazie.keep.ui.designsystem

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.vazie.keep.R
import app.vazie.keep.ui.icons.VazieIcons

@Composable
fun KeepScreen(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(KeepDesign.colors.background), content = content)
}

@Composable
fun KeepTopBar(
    modifier: Modifier = Modifier,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(KeepDesign.colors.background)
            .statusBarsPadding()
            .heightIn(min = 56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                KeepIconButton(VazieIcons.Back, stringResource(R.string.action_back), onBack, visualSize = 48, tint = KeepDesign.colors.text)
            }
            leading?.invoke(this)
        }
        if (title != null) {
            Text(
                title,
                style = KeepDesign.type.button.copy(fontSize = 19.sp, letterSpacing = (-0.005).em),
                color = KeepDesign.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() },
            )
        } else {
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

enum class DockTab { Keep, Generator }

@Composable
fun KeepDock(selected: DockTab, onKeep: () -> Unit, onGenerator: () -> Unit, onAdd: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = KeepDesign.colors
    Box(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .shadow(16.dp, RoundedCornerShape(32.dp), ambientColor = colors.floatingShadow, spotColor = colors.floatingShadow)
                    .clip(RoundedCornerShape(32.dp))
                    .background(colors.surfaceRaised)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DockItem(stringResource(R.string.dock_keep), VazieIcons.Archive, selected == DockTab.Keep, onKeep, Modifier.weight(1f))
                DockItem(stringResource(R.string.dock_generator), VazieIcons.Dice, selected == DockTab.Generator, onGenerator, Modifier.weight(1f))
            }
            if (onAdd != null) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .shadow(16.dp, RoundedCornerShape(24.dp), ambientColor = colors.floatingShadow, spotColor = colors.floatingShadow)
                        .clip(RoundedCornerShape(24.dp))
                        .background(colors.action)
                        .clickable(role = Role.Button, onClick = onAdd),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(VazieIcons.Add, contentDescription = stringResource(R.string.action_add), tint = colors.onAction, modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}

@Composable
private fun DockItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = KeepDesign.colors
    Row(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(if (selected) colors.accentContainer else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) colors.onAccentContainer else colors.textSecondary, modifier = Modifier.size(20.dp))
        // Like a system navigation bar, the dock stops growing before its labels stop fitting.
        val density = LocalDensity.current
        val size = with(density) { (15.dp * density.fontScale.coerceAtMost(1.3f)).toSp() }
        Text(label, style = KeepDesign.type.button.copy(fontSize = size, lineHeight = size * 1.4f), color = if (selected) colors.onAccentContainer else colors.textSecondary, maxLines = 1, softWrap = false)
    }
}

@Composable
fun BottomFade(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(96.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, KeepDesign.colors.background))),
    )
}

@Composable
fun KeepSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    val colors = KeepDesign.colors
    SnackbarHost(state, modifier = modifier.windowInsetsPadding(WindowInsets.navigationBars)) { data ->
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .shadow(12.dp, KeepShapes.field, ambientColor = colors.floatingShadow, spotColor = colors.floatingShadow)
                .clip(KeepShapes.field)
                .background(colors.surfaceToast)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(VazieIcons.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
            Text(data.visuals.message, style = KeepDesign.type.rowSubtitle.copy(fontSize = 14.5.sp), color = colors.text)
        }
    }
}

@Composable
fun KeepSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** Before the title, in place of [icon]: a mark such as the Keep+ badge. */
    leading: (@Composable () -> Unit)? = null,
    /** At most this many lines of [subtitle], the rest ellipsized; by default it wraps, so a large font loses nothing. */
    subtitleMaxLines: Int = Int.MAX_VALUE,
    /** A way to something that cannot be undone: the title and the icon tile take the danger colours. */
    destructive: Boolean = false,
) {
    val colors = KeepDesign.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (leading != null) leading()
        if (icon != null) {
            Box(
                Modifier.size(40.dp).clip(KeepShapes.tile).background(if (destructive) colors.dangerContainer else colors.accentContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = if (destructive) colors.danger else colors.onAccentContainer, modifier = Modifier.size(20.dp))
            }
        }
        // With a large system font the value goes under the title: side by side they break words apart.
        val stacked = value != null && isLargeFont()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = KeepDesign.type.rowTitle, color = if (destructive) colors.danger else colors.text)
            if (subtitle != null) {
                Text(subtitle, style = KeepDesign.type.rowSubtitle, color = colors.textSecondary, maxLines = subtitleMaxLines, overflow = TextOverflow.Ellipsis)
            }
            if (stacked && value != null) Text(value, style = KeepDesign.type.rowSubtitle, color = colors.textSecondary)
        }
        if (value != null && !stacked) Text(value, style = KeepDesign.type.rowSubtitle, color = colors.textSecondary)
        if (trailing != null) trailing()
        if (chevron) Icon(VazieIcons.ChevronRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(20.dp))
    }
}

// The system font size is turned up far enough that rows need their roomier layout.
@Composable
fun isLargeFont(): Boolean = LocalDensity.current.fontScale >= 1.3f
