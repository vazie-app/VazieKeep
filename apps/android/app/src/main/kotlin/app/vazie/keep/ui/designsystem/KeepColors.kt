package app.vazie.keep.ui.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
class KeepColors(
    val isDark: Boolean,
    val background: Color,
    val surfaceList: Color,
    val surfaceField: Color,
    val surfaceRaised: Color,
    val surfaceToast: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val action: Color,
    val onAction: Color,
    val caution: Color,
    val danger: Color,
    /** Behind [danger] text on a filled destructive button. */
    val dangerContainer: Color,
    val hairline: Color,
    val scrim: Color,
    val floatingShadow: Color,
    val monograms: List<MonogramTone>,
    val vaultTiles: List<MonogramTone>,
)

@Immutable
class MonogramTone(val container: Color, val content: Color)

internal val DarkKeepColors = KeepColors(
    isDark = true,
    background = Color(0xFF121014),
    surfaceList = Color(0xFF1A161D),
    surfaceField = Color(0xFF231E27),
    surfaceRaised = Color(0xFF2D2732),
    surfaceToast = Color(0xFF393140),
    text = Color(0xFFF2ECE4),
    textSecondary = Color(0xFFB9AFBB),
    textTertiary = Color(0xFF958B98),
    accent = Color(0xFFC9B6F2),
    accentContainer = Color(0xFF3A2F4F),
    onAccentContainer = Color(0xFFE8DEFF),
    action = Color(0xFFF1E3C8),
    onAction = Color(0xFF2A2018),
    caution = Color(0xFFE9A77E),
    danger = Color(0xFFF2A9A1),
    dangerContainer = Color(0xFF4A2320),
    hairline = Color(0x12FFFFFF),
    scrim = Color(0x99000000),
    floatingShadow = Color(0x73000000),
    monograms = listOf(
        MonogramTone(Color(0xFF3B271F), Color(0xFFEFB896)),
        MonogramTone(Color(0xFF3A2131), Color(0xFFEDB5CF)),
        MonogramTone(Color(0xFF302748), Color(0xFFD2C0F7)),
        MonogramTone(Color(0xFF213229), Color(0xFFA9D3BA)),
        MonogramTone(Color(0xFF342C1E), Color(0xFFE6CE9B)),
        MonogramTone(Color(0xFF1E2A3A), Color(0xFFAEC7EA)),
    ),
    vaultTiles = listOf(
        MonogramTone(Color(0xFF644894), Color(0xFFC7B0F5)),
        MonogramTone(Color(0xFF50715F), Color(0xFFB5DBC5)),
        MonogramTone(Color(0xFFAB6647), Color(0xFFF6C6A8)),
        MonogramTone(Color(0xFF384870), Color(0xFFAFC2EE)),
        MonogramTone(Color(0xFFBDA67F), Color(0xFFFFF3DA)),
    ),
)

internal val LightKeepColors = KeepColors(
    isDark = false,
    background = Color(0xFFFAF8F4),
    surfaceList = Color(0xFFFFFEFC),
    surfaceField = Color(0xFFF1EEE9),
    surfaceRaised = Color(0xFFE8E4DE),
    surfaceToast = Color(0xFFDCD7D0),
    text = Color(0xFF211A25),
    textSecondary = Color(0xFF5B5160),
    textTertiary = Color(0xFF6B6170),
    accent = Color(0xFF5D43A8),
    accentContainer = Color(0xFFE6DDFA),
    onAccentContainer = Color(0xFF2B1A5C),
    action = Color(0xFF2A2030),
    onAction = Color(0xFFF6EEDD),
    caution = Color(0xFFA6532E),
    danger = Color(0xFFA23B32),
    dangerContainer = Color(0xFFF6DAD6),
    hairline = Color(0x14221B26),
    scrim = Color(0x66000000),
    floatingShadow = Color(0x29342446),
    monograms = listOf(
        MonogramTone(Color(0xFFF6DFD2), Color(0xFF86421F)),
        MonogramTone(Color(0xFFF6DCE7), Color(0xFF872D57)),
        MonogramTone(Color(0xFFE6DDFA), Color(0xFF4B3590)),
        MonogramTone(Color(0xFFDAEADF), Color(0xFF2E5A40)),
        MonogramTone(Color(0xFFF1E5C9), Color(0xFF6A511C)),
        MonogramTone(Color(0xFFDCE6F5), Color(0xFF2A4C7A)),
    ),
    vaultTiles = DarkKeepColors.vaultTiles,
)
