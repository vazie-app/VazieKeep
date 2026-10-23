package app.vazie.keep.desktop.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun StatusScreen(model: StatusModel) {
    val state by produceState<StatusUiState?>(initialValue = null, model) {
        value = withContext(Dispatchers.Default) { model.load() }
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Vazie Keep", style = MaterialTheme.typography.headlineLarge)
            val core = when (val c = state?.core) {
                null -> "connecting…"
                is CoreState.Connected -> "connected (${c.version})"
                CoreState.Unavailable -> "unavailable"
            }
            Text("Core: $core", fontFamily = FontFamily.Monospace)
            Text("Platform: ${state?.platform.orEmpty()}", fontFamily = FontFamily.Monospace)
        }
    }
}
