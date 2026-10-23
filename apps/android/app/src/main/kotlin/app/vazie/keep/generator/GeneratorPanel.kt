package app.vazie.keep.generator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vazie.keep.R
import app.vazie.keep.core.PasswordOptions
import app.vazie.keep.editor.GeneratorState
import app.vazie.keep.ui.components.PasswordStrength
import app.vazie.keep.ui.designsystem.KeepButton
import app.vazie.keep.ui.designsystem.KeepButtonStyle
import app.vazie.keep.ui.designsystem.KeepCard
import app.vazie.keep.ui.designsystem.KeepDesign
import app.vazie.keep.ui.designsystem.KeepIconButton
import app.vazie.keep.ui.designsystem.KeepIconTone
import app.vazie.keep.ui.designsystem.KeepShapes
import app.vazie.keep.ui.designsystem.KeepStrengthMeter
import app.vazie.keep.ui.designsystem.KeepSwitch
import app.vazie.keep.ui.designsystem.secretText
import app.vazie.keep.ui.icons.VazieIcons

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GeneratorPanel(
    state: GeneratorState,
    onOptions: (PasswordOptions) -> Unit,
    onRegenerate: () -> Unit,
    onReveal: () -> Unit,
    onHide: () -> Unit,
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    primaryIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val colors = KeepDesign.colors
    val options = state.options
    val sets = listOf(options.lowercase, options.uppercase, options.digits, options.symbols).count { it }
    val hiddenDescription = stringResource(R.string.generator_hidden)
    val shownDescription = stringResource(R.string.generator_shown)
    val strength = state.revealed?.let { PasswordStrength.estimate(it) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        KeepCard {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 64.dp)
                            .clearAndSetSemantics {
                                contentDescription = if (state.revealed != null) shownDescription else hiddenDescription
                                liveRegion = LiveRegionMode.Polite
                            },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (state.revealed != null) {
                            Text(
                                secretText(state.revealed, colors),
                                style = KeepDesign.type.secret.copy(fontSize = 19.sp, lineHeight = 28.sp),
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            )
                        } else if (state.generated) {
                            app.vazie.keep.ui.designsystem.HiddenDots(count = 16)
                        }
                    }
                    KeepIconButton(
                        if (state.revealed != null) VazieIcons.EyeOff else VazieIcons.Eye,
                        stringResource(if (state.revealed != null) R.string.generator_hide else R.string.generator_show),
                        if (state.revealed != null) onHide else onReveal,
                        enabled = state.generated,
                    )
                }
                if (strength != null) {
                    KeepStrengthMeter(strength.level, stringResource(strength.label), bits = pluralStringResource(R.plurals.strength_bits, strength.bits, strength.bits))
                } else {
                    KeepStrengthMeter(-1, "")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    KeepIconButton(
                        VazieIcons.Dice,
                        stringResource(R.string.generator_regenerate),
                        onRegenerate,
                        tone = KeepIconTone.Raised,
                        visualSize = 64,
                        shape = KeepShapes.card,
                    )
                    KeepButton(primaryLabel, onPrimary, modifier = Modifier.weight(1f), enabled = state.generated, icon = primaryIcon)
                }
            }
        }
        KeepCard {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val lengthLabel = stringResource(R.string.generator_length, options.length)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.generator_length_label), style = KeepDesign.type.bodyLarge, color = colors.text, modifier = Modifier.weight(1f))
                    KeepIconButton(
                        VazieIcons.Minus, stringResource(R.string.generator_decrease),
                        { onOptions(options.copy(length = (options.length - 1).coerceAtLeast(PasswordOptions.MIN_LENGTH))) },
                        tone = KeepIconTone.Raised, shape = KeepShapes.innerButton, visualSize = 44,
                    )
                    Column(Modifier.padding(horizontal = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${options.length}", style = KeepDesign.type.headline.copy(fontSize = 34.sp, lineHeight = 36.sp), color = colors.text, textAlign = TextAlign.Center)
                        Text(stringResource(R.string.generator_characters), style = KeepDesign.type.badge, color = colors.textSecondary)
                    }
                    KeepIconButton(
                        VazieIcons.Add, stringResource(R.string.generator_increase),
                        { onOptions(options.copy(length = (options.length + 1).coerceAtMost(PasswordOptions.MAX_LENGTH))) },
                        tone = KeepIconTone.Raised, shape = KeepShapes.innerButton, visualSize = 44,
                    )
                }
                Slider(
                    value = options.length.toFloat(),
                    onValueChange = { onOptions(options.copy(length = it.toInt().coerceIn(PasswordOptions.MIN_LENGTH, PasswordOptions.MAX_LENGTH))) },
                    valueRange = PasswordOptions.MIN_LENGTH.toFloat()..PasswordOptions.MAX_LENGTH.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = colors.accent,
                        activeTrackColor = colors.accent,
                        inactiveTrackColor = colors.surfaceRaised,
                    ),
                    thumb = {
                        Box(
                            Modifier
                                .size(34.dp)
                                .shadow(4.dp, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .background(colors.accent),
                        )
                    },
                    track = { sliderState ->
                        SliderDefaults.Track(
                            sliderState = sliderState,
                            modifier = Modifier.height(12.dp),
                            thumbTrackGapSize = 0.dp,
                            trackInsideCornerSize = 6.dp,
                            drawStopIndicator = null,
                            colors = SliderDefaults.colors(activeTrackColor = colors.accent, inactiveTrackColor = colors.surfaceRaised),
                        )
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { stateDescription = lengthLabel },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Keycap("ABC", stringResource(R.string.generator_upper_label), stringResource(R.string.generator_uppercase), options.uppercase, !options.uppercase || sets > 1, Modifier.weight(1f)) {
                onOptions(options.copy(uppercase = it))
            }
            Keycap("abc", stringResource(R.string.generator_lower_label), stringResource(R.string.generator_lowercase), options.lowercase, !options.lowercase || sets > 1, Modifier.weight(1f)) {
                onOptions(options.copy(lowercase = it))
            }
            Keycap("123", stringResource(R.string.generator_digits_label), stringResource(R.string.generator_digits), options.digits, !options.digits || sets > 1, Modifier.weight(1f)) {
                onOptions(options.copy(digits = it))
            }
            Keycap("#$&", stringResource(R.string.generator_symbols_label), stringResource(R.string.generator_symbols), options.symbols, !options.symbols || sets > 1, Modifier.weight(1f)) {
                onOptions(options.copy(symbols = it))
            }
        }
        KeepCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clickable(role = Role.Switch) { onOptions(options.copy(excludeAmbiguous = !options.excludeAmbiguous)) }
                    .semantics(mergeDescendants = true) { toggleableState = if (options.excludeAmbiguous) ToggleableState.On else ToggleableState.Off }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(stringResource(R.string.generator_ambiguous), style = KeepDesign.type.rowTitle, color = colors.text)
                    Text(stringResource(R.string.generator_ambiguous_hint), style = KeepDesign.type.mono, color = colors.textSecondary)
                }
                KeepSwitch(options.excludeAmbiguous)
            }
        }
    }
}

@Composable
private fun Keycap(glyph: String, label: String, description: String, on: Boolean, enabled: Boolean, modifier: Modifier, onChange: (Boolean) -> Unit) {
    val colors = KeepDesign.colors
    val edge = colors.surfaceToast
    Box(
        modifier = modifier
            .heightIn(min = 64.dp)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!on) }
            .semantics(mergeDescendants = true) {
                contentDescription = description
                toggleableState = if (on) ToggleableState.On else ToggleableState.Off
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .drawBehind {
                    if (!on) {
                        drawRoundRect(edge, topLeft = Offset(0f, 3.dp.toPx()), size = Size(size.width, size.height), cornerRadius = CornerRadius(18.dp.toPx()))
                    }
                }
                .padding(bottom = if (on) 0.dp else 3.dp)
                .clip(KeepShapes.key)
                .background(if (on) colors.accentContainer else colors.surfaceRaised),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(glyph, style = KeepDesign.type.mono.copy(fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight(600)), color = if (on) colors.onAccentContainer else colors.text)
            Text(label, style = KeepDesign.type.badge, color = if (on) colors.onAccentContainer else colors.textSecondary)
        }
    }
}
