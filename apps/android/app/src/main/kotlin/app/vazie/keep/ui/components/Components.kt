package app.vazie.keep.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.FilterQuality
import app.vazie.keep.siteicons.LocalEntryImages
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import app.vazie.keep.ui.icons.ItemIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vazie.keep.R
import app.vazie.keep.core.EntrySummary
import app.vazie.keep.core.EntryText
import app.vazie.keep.ui.designsystem.KeepBadge
import app.vazie.keep.ui.designsystem.KeepBadgeTone
import app.vazie.keep.ui.designsystem.KeepBanner
import app.vazie.keep.ui.designsystem.KeepBannerTone
import app.vazie.keep.ui.designsystem.KeepButton
import app.vazie.keep.ui.designsystem.KeepButtonSize
import app.vazie.keep.ui.designsystem.KeepButtonStyle
import app.vazie.keep.ui.designsystem.KeepDesign
import app.vazie.keep.ui.designsystem.KeepHairline
import app.vazie.keep.ui.designsystem.KeepIconButton
import app.vazie.keep.ui.designsystem.KeepShapes
import app.vazie.keep.ui.designsystem.Monogram
import app.vazie.keep.ui.designsystem.monogramTone
import app.vazie.keep.ui.icons.VazieIcons

@Composable
fun VaultMark(modifier: Modifier = Modifier, size: Int = 106, letter: String = "V") {
    val tone = KeepDesign.colors.vaultTiles.first()
    Monogram(letter, modifier = modifier, size = size.dp, radius = (size * 0.3f).dp, tone = tone, textSize = size * 0.42f)
}

@Composable
fun VaultCard(name: String, subtitle: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val colors = KeepDesign.colors
    val base = modifier
        .fillMaxWidth()
        .clip(KeepShapes.card)
        .background(colors.surfaceList)
    Row(
        modifier = (if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base.semantics(mergeDescendants = true) {})
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Monogram(name, size = 44.dp, radius = 13.dp, tone = colors.vaultTiles.first())
        Column(Modifier.weight(1f)) {
            Text(name, style = KeepDesign.type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = KeepDesign.type.rowSubtitle, color = colors.textSecondary)
        }
        if (onClick != null) Icon(VazieIcons.ChevronRight, contentDescription = null, tint = colors.textTertiary)
    }
}

class GroupActions(val onRename: () -> Unit, val onDelete: () -> Unit, val onIcon: () -> Unit = {})

// An entry shows its site's image when the file keeps one, else the icon chosen for it, else the first letter of its title.
@Composable
fun EntryGlyph(title: String, icon: Int, size: Dp = 42.dp, radius: Dp = 13.dp, textSize: Float = 18f, image: String? = null) {
    val images = LocalEntryImages.current
    val picture by produceState(image?.let { images?.cached(it) }, image, images) {
        value = image?.let { images?.image(it) }
    }
    picture?.let {
        Box(Modifier.size(size).clip(RoundedCornerShape(radius)).background(KeepDesign.colors.surfaceRaised), contentAlignment = Alignment.Center) {
            Image(it, contentDescription = null, modifier = Modifier.size(size * 0.62f).clip(RoundedCornerShape(radius * 0.4f)), filterQuality = FilterQuality.High)
        }
        return
    }
    val chosen = ItemIcon.forEntry(icon)
    if (chosen == null) {
        Monogram(title, size = size, radius = radius, textSize = textSize)
        return
    }
    val tone = monogramTone(KeepDesign.colors, title)
    Box(Modifier.size(size).clip(RoundedCornerShape(radius)).background(tone.container), contentAlignment = Alignment.Center) {
        Icon(chosen.image, contentDescription = null, tint = tone.content, modifier = Modifier.size(size * 0.52f))
    }
}

@Composable
fun GroupRow(name: String, onClick: () -> Unit, actions: GroupActions? = null, divider: Boolean = false, icon: Int = ItemIcon.Folder.id) {
    var open by remember { mutableStateOf(false) }
    val colors = KeepDesign.colors
    ListRow(
        onClick = onClick,
        divider = divider,
        leading = {
            Box(Modifier.size(42.dp).clip(KeepShapes.tile).background(colors.monograms[4].container), contentAlignment = Alignment.Center) {
                Icon(ItemIcon.forGroup(icon).image, contentDescription = null, tint = colors.monograms[4].content, modifier = Modifier.size(22.dp))
            }
        },
        trailing = {
            if (actions != null) {
                Box {
                    KeepIconButton(VazieIcons.More, stringResource(R.string.group_actions, name), { open = true })
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { open = false; actions.onRename() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_change_icon)) }, onClick = { open = false; actions.onIcon() })
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete), color = colors.caution) },
                            onClick = { open = false; actions.onDelete() },
                        )
                    }
                }
            } else {
                Icon(VazieIcons.ChevronRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.padding(end = 10.dp))
            }
        },
    ) {
        Text(name, style = KeepDesign.type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun EntryRow(entry: EntrySummary, onClick: () -> Unit, divider: Boolean = false, onCopy: (() -> Unit)? = null) {
    val colors = KeepDesign.colors
    val hidden = stringResource(R.string.value_protected)
    val title = entry.title.displayOr(hidden).ifEmpty { stringResource(R.string.entry_untitled) }
    val subtitle = listOf(entry.username.displayOr(hidden), entry.url.displayOr(hidden)).filter { it.isNotEmpty() }.joinToString(" · ")
    val passwordDescription = stringResource(R.string.entry_has_password)
    ListRow(
        onClick = onClick,
        divider = divider,
        leading = { EntryGlyph(title, entry.icon, image = entry.customIcon) },
        trailing = {
            if (onCopy != null) {
                KeepIconButton(VazieIcons.Copy, stringResource(R.string.entry_copy_password, title), onCopy)
            }
        },
    ) {
        val favoriteDescription = stringResource(R.string.entry_favorite_state)
        val expiredLabel = stringResource(R.string.entry_expired_badge)
        val expired = remember(entry.expiresAt) { entry.isExpired(System.currentTimeMillis() / 1000) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                title,
                style = KeepDesign.type.rowTitle,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).semantics {
                    contentDescription = listOfNotNull(
                        title,
                        passwordDescription.takeIf { entry.hasPassword },
                        favoriteDescription.takeIf { entry.favorite },
                        expiredLabel.takeIf { expired },
                    ).joinToString(", ")
                },
            )
            if (entry.favorite) Icon(VazieIcons.StarFilled, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
            if (expired) KeepBadge(expiredLabel, tone = KeepBadgeTone.Caution)
        }
        if (subtitle.isNotEmpty()) {
            Text(subtitle, style = KeepDesign.type.rowSubtitle, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ListRow(
    onClick: () -> Unit,
    divider: Boolean,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick, role = Role.Button)
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        leading()
        Column(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 64.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            if (divider) KeepHairlineBleed()
            Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) { content() }
        }
        trailing()
    }
}

@Composable
private fun KeepHairlineBleed() = KeepHairline()

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val colors = KeepDesign.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(72.dp).clip(KeepShapes.hero).background(colors.accentContainer), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = colors.onAccentContainer, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = KeepDesign.type.title, color = colors.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(message, style = KeepDesign.type.body.copy(fontSize = KeepDesign.type.rowSubtitle.fontSize), color = colors.textSecondary, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            action()
        }
    }
}

@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    KeepBanner(message = message, modifier = modifier, tone = KeepBannerTone.Error)
}

fun EntryText.displayOr(hidden: String): String = when (this) {
    is EntryText.Plain -> value
    EntryText.Hidden -> hidden
}

@Composable
fun MoveDialog(targets: List<app.vazie.keep.vault.GroupChoice>, onSelect: (app.vazie.keep.core.GroupId) -> Unit, onDismiss: () -> Unit) {
    val colors = KeepDesign.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceRaised,
        shape = KeepShapes.hero,
        title = { Text(stringResource(R.string.move_title), style = KeepDesign.type.title, color = colors.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                targets.forEach { group ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(KeepShapes.chip)
                            .clickable(role = Role.Button) { onSelect(group.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(VazieIcons.Folder, contentDescription = null, tint = colors.caution)
                        Text(group.path, style = KeepDesign.type.body, color = colors.text, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        },
        confirmButton = { KeepButton(stringResource(R.string.action_cancel), onDismiss, style = KeepButtonStyle.Text, size = KeepButtonSize.Pill) },
    )
}

@Composable
fun DeleteEntryDialog(title: String, toBin: Boolean, onDelete: (permanent: Boolean) -> Unit, onDismiss: () -> Unit) {
    val colors = KeepDesign.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceRaised,
        shape = KeepShapes.hero,
        title = { Text(stringResource(R.string.delete_entry_title, title), style = KeepDesign.type.title, color = colors.text) },
        text = {
            Text(
                stringResource(if (toBin) R.string.delete_entry_bin_message else R.string.delete_entry_permanent_message),
                style = KeepDesign.type.body,
                color = colors.textSecondary,
            )
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (toBin) KeepButton(stringResource(R.string.delete_to_bin), { onDelete(false) }, size = KeepButtonSize.Pill)
                KeepButton(stringResource(R.string.delete_permanently), { onDelete(true) }, style = KeepButtonStyle.Caution, size = KeepButtonSize.Pill)
                KeepButton(stringResource(R.string.action_cancel), onDismiss, style = KeepButtonStyle.Text, size = KeepButtonSize.Pill)
            }
        },
    )
}

fun formatDateTime(unixSeconds: Long): String =
    java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
        .format(java.time.Instant.ofEpochSecond(unixSeconds).atZone(java.time.ZoneId.systemDefault()))

fun formatDate(unixSeconds: Long): String =
    java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG)
        .format(java.time.Instant.ofEpochSecond(unixSeconds).atZone(java.time.ZoneId.systemDefault()))
