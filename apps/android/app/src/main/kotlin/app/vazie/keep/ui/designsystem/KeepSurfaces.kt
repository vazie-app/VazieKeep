package app.vazie.keep.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vazie.keep.ui.icons.VazieIcons

@Composable
fun KeepCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(KeepShapes.card)
            .background(KeepDesign.colors.surfaceList),
        content = content,
    )
}

@Composable
fun KeepHairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(KeepDesign.colors.hairline))
}

fun monogramTone(colors: KeepColors, key: String): MonogramTone =
    colors.monograms[Math.floorMod(key.lowercase().hashCode(), colors.monograms.size)]

@Composable
fun Monogram(
    title: String,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    radius: Dp = 13.dp,
    tone: MonogramTone = monogramTone(KeepDesign.colors, title),
    textSize: Float = 18f,
) {
    val letter = title.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"
    Box(
        modifier = modifier.size(size).clip(RoundedCornerShape(radius)).background(tone.container),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter,
            style = KeepDesign.type.title.copy(fontSize = (textSize).sp, lineHeight = (textSize * 1.2f).sp),
            color = tone.content,
        )
    }
}

@Composable
fun KeepChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, count: String? = null, icon: ImageVector? = null) {
    val colors = KeepDesign.colors
    Box(modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .height(40.dp)
                .clip(KeepShapes.chip)
                .background(if (selected) colors.accentContainer else colors.surfaceField)
                .clickable(role = Role.Tab, onClick = onClick)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = if (selected) colors.onAccentContainer else colors.textSecondary, modifier = Modifier.size(16.dp))
            Text(label, style = KeepDesign.type.button.copy(fontSize = (14f).sp), color = if (selected) colors.onAccentContainer else colors.text)
            if (count != null) Text(count, style = KeepDesign.type.caption, color = if (selected) colors.onAccentContainer else colors.textTertiary)
        }
    }
}

enum class KeepBadgeTone { Neutral, Accent, Caution }

@Composable
fun KeepBadge(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, tone: KeepBadgeTone = KeepBadgeTone.Neutral) {
    val colors = KeepDesign.colors
    val container = when (tone) {
        KeepBadgeTone.Neutral -> colors.surfaceRaised
        KeepBadgeTone.Accent -> colors.accentContainer
        KeepBadgeTone.Caution -> colors.surfaceRaised
    }
    val content = when (tone) {
        KeepBadgeTone.Neutral -> colors.textSecondary
        KeepBadgeTone.Accent -> colors.onAccentContainer
        KeepBadgeTone.Caution -> colors.caution
    }
    Row(
        modifier = modifier.heightIn(min = 24.dp).clip(KeepShapes.badge).background(container).padding(horizontal = 9.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(13.dp))
        Text(text, style = KeepDesign.type.badge, color = content)
    }
}

enum class KeepBannerTone { Info, Error, Caution }

@Composable
fun KeepBanner(
    message: String,
    modifier: Modifier = Modifier,
    tone: KeepBannerTone = KeepBannerTone.Info,
    icon: ImageVector = if (tone == KeepBannerTone.Info) VazieIcons.Info else VazieIcons.Warning,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = KeepDesign.colors
    val accent = when (tone) {
        KeepBannerTone.Info -> colors.accent
        KeepBannerTone.Error -> colors.danger
        KeepBannerTone.Caution -> colors.caution
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(KeepShapes.field)
            .background(colors.surfaceField)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp).padding(top = 2.dp))
            Text(message, style = KeepDesign.type.rowSubtitle.copy(lineHeight = (20f).sp), color = if (tone == KeepBannerTone.Info) colors.text else accent, modifier = Modifier.weight(1f))
        }
        if (actions != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), content = actions)
    }
}

@Composable
fun KeepSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = KeepDesign.type.label,
        color = KeepDesign.colors.textSecondary,
        modifier = modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
fun KeepSwitch(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = KeepDesign.colors
    Switch(
        checked = checked,
        onCheckedChange = null,
        enabled = enabled,
        modifier = modifier,
        thumbContent = if (checked) {
            { Icon(VazieIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
        } else {
            null
        },
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.background,
            checkedTrackColor = colors.accent,
            checkedIconColor = colors.accent,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = colors.textTertiary,
            uncheckedTrackColor = colors.surfaceRaised,
            uncheckedBorderColor = Color.Transparent,
            disabledCheckedThumbColor = colors.background,
            disabledCheckedTrackColor = colors.accent.copy(alpha = 0.4f),
            disabledUncheckedThumbColor = colors.textTertiary.copy(alpha = 0.6f),
            disabledUncheckedTrackColor = colors.surfaceRaised.copy(alpha = 0.6f),
            disabledUncheckedBorderColor = Color.Transparent,
            disabledCheckedBorderColor = Color.Transparent,
        ),
    )
}

@Composable
fun KeepStrengthMeter(level: Int, caption: String, modifier: Modifier = Modifier, bits: String? = null) {
    val colors = KeepDesign.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (index in 0 until 5) {
                val filled = index <= level
                Box(
                    Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(if (!filled) colors.surfaceToast else if (level == 0) colors.caution else colors.accent),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            Text(caption, style = KeepDesign.type.label, color = colors.text)
            if (bits != null) Text("· $bits", style = KeepDesign.type.caption, color = colors.textSecondary)
        }
    }
}

fun secretText(value: String, colors: KeepColors): AnnotatedString = buildAnnotatedString {
    value.forEach { ch ->
        val color = when {
            ch.isDigit() -> colors.accent
            !ch.isLetterOrDigit() && !ch.isWhitespace() -> colors.caution
            else -> colors.text
        }
        withStyle(SpanStyle(color = color)) { append(ch) }
    }
}

@Composable
fun HiddenDots(modifier: Modifier = Modifier, color: Color = KeepDesign.colors.text, count: Int = 12) {
    Row(modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(count) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        }
    }
}
