package app.vazie.keep.ui.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.vazie.keep.R

private fun geologica(vararg weights: Int) = FontFamily(
    weights.map { w ->
        Font(R.font.geologica, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

private fun martianMono(vararg weights: Int) = FontFamily(
    weights.map { w ->
        Font(
            R.font.martian_mono,
            FontWeight(w),
            variationSettings = FontVariation.Settings(FontVariation.weight(w), FontVariation.width(87.5f)),
        )
    },
)

val GeologicaFamily = geologica(400, 500, 550, 600, 650, 700)
val MartianMonoFamily = martianMono(400, 450, 500, 600)

@Immutable
class KeepType(
    val display: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    val bodyLarge: TextStyle,
    val body: TextStyle,
    val rowTitle: TextStyle,
    val rowSubtitle: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val badge: TextStyle,
    val button: TextStyle,
    val secret: TextStyle,
    val code: TextStyle,
    val mono: TextStyle,
)

internal val DefaultKeepType = KeepType(
    display = TextStyle(fontFamily = GeologicaFamily, fontWeight = FontWeight(650), fontSize = 38.5.sp, lineHeight = 42.sp, letterSpacing = (-0.03).em),
    headline = TextStyle(fontFamily = GeologicaFamily, fontWeight = FontWeight(650), fontSize = 29.sp, lineHeight = 32.5.sp, letterSpacing = (-0.02).em),
    title = TextStyle(fontFamily = GeologicaFamily, fontWeight = FontWeight(600), fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.012).em),
    bodyLarge = TextStyle(fontFamily = GeologicaFamily, fontWeight = FontWeight(550), fontSize = 17.sp, lineHeight = 24.sp),
    body = TextStyle(fontFamily = GeologicaFamily, fontWeight = FontWeight(400), fontSize = 16.sp, lineHeight = 24.sp),
    rowTitle = TextStyle(fontFamily = GeologicaFamily, fontWeight = FontWeight(550), fontSize = 16.sp, lineHeight = 22.sp),
    rowSubtitle = TextStyle(fontFamily = GeologicaFamily, fontWeight = FontWeight(400), fontSize = 13.5.sp, lineHeight = 19.sp),
    label = TextStyle(fontFamily = GeologicaFamily, fontWeight = FontWeight(600), fontSize = 13.sp, lineHeight = 18.sp),
    caption = TextStyle(fontFamily = GeologicaFamily, fontWeight = FontWeight(400), fontSize = 13.sp, lineHeight = 18.sp),
    badge = TextStyle(fontFamily = GeologicaFamily, fontWeight = FontWeight(600), fontSize = 12.sp, lineHeight = 16.sp),
    button = TextStyle(fontFamily = GeologicaFamily, fontWeight = FontWeight(600), fontSize = 16.sp, lineHeight = 22.sp),
    secret = TextStyle(fontFamily = MartianMonoFamily, fontWeight = FontWeight(450), fontSize = 18.5.sp, lineHeight = 28.sp, letterSpacing = 0.02.em),
    code = TextStyle(fontFamily = MartianMonoFamily, fontWeight = FontWeight(450), fontSize = 24.sp, lineHeight = 32.sp),
    mono = TextStyle(fontFamily = MartianMonoFamily, fontWeight = FontWeight(400), fontSize = 12.sp, lineHeight = 17.sp),
)
