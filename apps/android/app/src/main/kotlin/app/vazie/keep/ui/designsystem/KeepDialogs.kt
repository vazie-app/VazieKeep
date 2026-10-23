package app.vazie.keep.ui.designsystem

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun KeepDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
    actions: @Composable () -> Unit,
) {
    val colors = KeepDesign.colors
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        containerColor = colors.surfaceRaised,
        shape = KeepShapes.hero,
        title = { Text(title, style = KeepDesign.type.title, color = colors.text, modifier = Modifier.semantics { heading() }) },
        text = if (message != null || content != null) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (message != null) Text(message, style = KeepDesign.type.body, color = colors.textSecondary)
                    content?.invoke(this)
                }
            }
        } else {
            null
        },
        confirmButton = { Column(Modifier.padding(top = 4.dp), horizontalAlignment = androidx.compose.ui.Alignment.End) { actions() } },
    )
}

@Composable
fun KeepDialogAction(text: String, onClick: () -> Unit, style: KeepButtonStyle = KeepButtonStyle.Text, enabled: Boolean = true) {
    KeepButton(text, onClick, style = style, size = KeepButtonSize.Pill, enabled = enabled, fullWidth = false)
}
