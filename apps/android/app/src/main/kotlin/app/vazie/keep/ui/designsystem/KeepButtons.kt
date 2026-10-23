package app.vazie.keep.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class KeepButtonStyle { Action, Tonal, Raised, Text, Caution, Destructive }

enum class KeepButtonSize(val height: Int, val radius: Int, val padding: Int) {
    Large(64, 22, 22),
    Medium(52, 20, 20),
    Pill(40, 20, 18),
}

@Composable
fun KeepButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: KeepButtonStyle = KeepButtonStyle.Action,
    size: KeepButtonSize = KeepButtonSize.Large,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    fullWidth: Boolean = size != KeepButtonSize.Pill && (style == KeepButtonStyle.Action || style == KeepButtonStyle.Tonal || style == KeepButtonStyle.Destructive),
) {
    val colors = KeepDesign.colors
    val container: Color
    val content: Color
    when (style) {
        KeepButtonStyle.Action -> { container = colors.action; content = colors.onAction }
        KeepButtonStyle.Tonal -> { container = colors.surfaceField; content = colors.text }
        KeepButtonStyle.Raised -> { container = colors.surfaceRaised; content = colors.text }
        KeepButtonStyle.Text -> { container = Color.Transparent; content = colors.accent }
        KeepButtonStyle.Caution -> { container = Color.Transparent; content = colors.caution }
        KeepButtonStyle.Destructive -> { container = colors.dangerContainer; content = colors.danger }
    }
    val shape = RoundedCornerShape(size.radius.dp)
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.5f),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = size.height.dp)
                .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
                .clip(shape)
                .background(container)
                .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick)
                .padding(horizontal = size.padding.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = content, modifier = Modifier.size(22.dp))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (size == KeepButtonSize.Large) 12.dp else 10.dp)) {
                    if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(if (size == KeepButtonSize.Pill) 18.dp else 22.dp))
                    Text(
                        text,
                        style = if (size == KeepButtonSize.Pill) KeepDesign.type.button.copy(fontSize = 15.sp) else KeepDesign.type.button,
                        color = content,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
    }
}

enum class KeepIconTone { Plain, Raised, Action, Accent }

@Composable
fun KeepIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: KeepIconTone = KeepIconTone.Plain,
    enabled: Boolean = true,
    visualSize: Int = 44,
    shape: Shape = CircleShape,
    tint: Color? = null,
) {
    val colors = KeepDesign.colors
    val container = when (tone) {
        KeepIconTone.Plain -> Color.Transparent
        KeepIconTone.Raised -> colors.surfaceRaised
        KeepIconTone.Action -> colors.action
        KeepIconTone.Accent -> colors.accentContainer
    }
    val content = tint ?: when (tone) {
        KeepIconTone.Plain -> colors.textSecondary
        KeepIconTone.Raised -> colors.text
        KeepIconTone.Action -> colors.onAction
        KeepIconTone.Accent -> colors.onAccentContainer
    }
    Box(
        modifier = modifier
            .size(48.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(visualSize.dp).clip(shape).background(container), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
        }
    }
}
