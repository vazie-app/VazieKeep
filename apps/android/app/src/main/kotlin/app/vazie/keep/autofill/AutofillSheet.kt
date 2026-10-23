package app.vazie.keep.autofill

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vazie.keep.R
import app.vazie.keep.ui.designsystem.KeepDesign
import app.vazie.keep.ui.designsystem.KeepIconButton
import app.vazie.keep.ui.designsystem.KeepShapes
import app.vazie.keep.ui.designsystem.Monogram
import app.vazie.keep.ui.icons.VazieIcons

@Composable
fun AutofillSheet(
    title: String,
    subtitle: String?,
    avatar: String,
    onCancel: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = KeepDesign.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding()
                .clip(KeepShapes.sheet)
                .background(colors.background)
                .pointerInput(Unit) { detectTapGestures { } }
                .navigationBarsPadding(),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 10.dp, bottom = 6.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(KeepShapes.pill)
                    .background(colors.textTertiary.copy(alpha = 0.6f)),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Monogram(avatar, size = 44.dp, radius = 13.dp, tone = colors.vaultTiles.first(), textSize = 19f)
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = KeepDesign.type.title.copy(fontSize = 21.sp),
                        color = colors.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (!subtitle.isNullOrEmpty()) {
                        Text(subtitle, style = KeepDesign.type.mono, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                KeepIconButton(VazieIcons.Close, stringResource(R.string.action_cancel), onCancel)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}
