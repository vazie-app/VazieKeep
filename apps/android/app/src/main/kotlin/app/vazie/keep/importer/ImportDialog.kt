package app.vazie.keep.importer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vazie.keep.R
import app.vazie.keep.core.ImportSource
import app.vazie.keep.core.ImportSummary
import app.vazie.keep.ui.designsystem.KeepButtonStyle
import app.vazie.keep.ui.designsystem.KeepDesign
import app.vazie.keep.ui.designsystem.KeepDialog
import app.vazie.keep.ui.designsystem.KeepDialogAction

@Composable
fun ImportDialog(viewModel: ImportViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = KeepDesign.colors
    when (val s = state) {
        ImportUiState.Idle -> Unit
        ImportUiState.Reading, is ImportUiState.Importing -> KeepDialog(
            onDismissRequest = {},
            title = stringResource(if (s is ImportUiState.Reading) R.string.import_reading else R.string.import_importing),
            content = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = colors.accent, strokeWidth = 2.dp)
                    Text(stringResource(R.string.import_wait), style = KeepDesign.type.body, color = colors.textSecondary)
                }
            },
            actions = {},
        )
        is ImportUiState.Preview -> KeepDialog(
            onDismissRequest = viewModel::dismiss,
            title = pluralStringResource(R.plurals.import_preview_title, s.summary.entries, s.summary.entries),
            content = { Summary(s.summary) },
            actions = {
                KeepDialogAction(stringResource(R.string.action_cancel), viewModel::dismiss)
                KeepDialogAction(stringResource(R.string.import_confirm), viewModel::confirm, KeepButtonStyle.Action)
            },
        )
        is ImportUiState.Done -> KeepDialog(
            onDismissRequest = viewModel::dismiss,
            title = pluralStringResource(R.plurals.import_done_title, s.outcome.entries, s.outcome.entries),
            message = stringResource(R.string.import_done_message),
            actions = { KeepDialogAction(stringResource(R.string.action_done), viewModel::dismiss, KeepButtonStyle.Action) },
        )
        is ImportUiState.Failed -> KeepDialog(
            onDismissRequest = viewModel::dismiss,
            title = stringResource(R.string.import_failed_title),
            message = stringResource(
                when (s.problem) {
                    ImportProblem.Unreadable -> R.string.import_problem_unreadable
                    ImportProblem.TooLarge -> R.string.import_problem_too_large
                    ImportProblem.Empty -> R.string.import_problem_empty
                    ImportProblem.Unrecognized -> R.string.import_problem_unrecognized
                    ImportProblem.Encrypted -> R.string.import_problem_encrypted
                    ImportProblem.Malformed -> R.string.import_problem_malformed
                    ImportProblem.Failed -> R.string.import_problem_failed
                },
            ),
            actions = { KeepDialogAction(stringResource(R.string.action_done), viewModel::dismiss) },
        )
    }
}

@Composable
private fun Summary(summary: ImportSummary) {
    val colors = KeepDesign.colors
    val source = stringResource(
        when (summary.source) {
            ImportSource.Csv -> R.string.import_source_csv
            ImportSource.OnePasswordCsv, ImportSource.OnePassword1pux -> R.string.import_source_1password
            ImportSource.BitwardenJson, ImportSource.BitwardenCsv -> R.string.import_source_bitwarden
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.import_from, source), style = KeepDesign.type.body, color = colors.text)
        if (summary.folders > 0) {
            Text(pluralStringResource(R.plurals.import_folders, summary.folders, summary.folders), style = KeepDesign.type.body, color = colors.textSecondary)
        }
        if (summary.withTotp > 0) {
            Text(pluralStringResource(R.plurals.import_with_totp, summary.withTotp, summary.withTotp), style = KeepDesign.type.body, color = colors.textSecondary)
        }
        if (summary.skipped > 0) {
            Text(pluralStringResource(R.plurals.import_skipped, summary.skipped, summary.skipped), style = KeepDesign.type.body, color = colors.textSecondary)
        }
        Text(stringResource(R.string.import_preview_note), style = KeepDesign.type.caption, color = colors.textSecondary)
    }
}
