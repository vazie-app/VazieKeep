package app.vazie.keep.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.vazie.keep.core.RustKeepCore
import app.vazie.keep.desktop.platform.DesktopPlatform
import app.vazie.keep.desktop.status.StatusModel
import app.vazie.keep.desktop.status.StatusScreen
import app.vazie.keep.desktop.ui.KeepTheme

fun main() = application {
    val model = StatusModel(RustKeepCore, DesktopPlatform.current())
    Window(
        onCloseRequest = ::exitApplication,
        title = "Vazie Keep",
        state = rememberWindowState(width = 480.dp, height = 320.dp),
    ) {
        KeepTheme {
            StatusScreen(model)
        }
    }
}
