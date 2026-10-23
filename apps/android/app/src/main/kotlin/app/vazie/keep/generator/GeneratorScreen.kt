package app.vazie.keep.generator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vazie.keep.R
import app.vazie.keep.ui.designsystem.DockTab
import app.vazie.keep.ui.designsystem.KeepDesign
import app.vazie.keep.ui.designsystem.KeepDock
import app.vazie.keep.ui.designsystem.KeepIconButton
import app.vazie.keep.ui.designsystem.KeepScreen
import app.vazie.keep.ui.designsystem.KeepSnackbarHost
import app.vazie.keep.ui.icons.VazieIcons

@Composable
fun GeneratorScreen(viewModel: GeneratorViewModel, onKeep: () -> Unit, onSettings: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.copied.collect { seconds ->
            snackbar.showSnackbar(
                if (seconds == null) resources.getString(R.string.entry_copied_kept) else resources.getQuantityString(R.plurals.entry_copied, seconds, seconds),
            )
        }
    }
    DisposableEffect(viewModel) { onDispose { viewModel.clear() } }
    KeepScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            androidx.compose.foundation.layout.Row(
                Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.generator_screen_title),
                    style = KeepDesign.type.headline,
                    color = KeepDesign.colors.text,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                KeepIconButton(VazieIcons.Settings, stringResource(R.string.settings_title), onSettings)
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GeneratorPanel(
                    state = state,
                    onOptions = viewModel::setOptions,
                    onRegenerate = viewModel::generate,
                    onReveal = viewModel::reveal,
                    onHide = viewModel::hide,
                    primaryLabel = stringResource(R.string.generator_copy),
                    onPrimary = viewModel::copy,
                    primaryIcon = VazieIcons.Copy,
                )
            }
        }
        androidx.compose.foundation.layout.Box(Modifier.align(Alignment.BottomCenter)) {
            KeepDock(selected = DockTab.Generator, onKeep = onKeep, onGenerator = {}, onAdd = null)
        }
        KeepSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
    }
}
