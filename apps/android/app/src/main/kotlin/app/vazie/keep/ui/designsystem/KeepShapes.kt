package app.vazie.keep.ui.designsystem

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp

@Immutable
object KeepShapes {
    val badge = RoundedCornerShape(8.dp)
    val tile = RoundedCornerShape(12.dp)
    val innerButton = RoundedCornerShape(16.dp)
    val chip = RoundedCornerShape(14.dp)
    val key = RoundedCornerShape(18.dp)
    val field = RoundedCornerShape(20.dp)
    val button = RoundedCornerShape(22.dp)
    val card = RoundedCornerShape(24.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val hero = RoundedCornerShape(28.dp)
    val pill = CircleShape
}

object KeepSpacing {
    val gutter = 16.dp
    val cardPadding = 16.dp
    val rowMinHeight = 64.dp
    val touchTarget = 48.dp
    val visualTarget = 44.dp
}
