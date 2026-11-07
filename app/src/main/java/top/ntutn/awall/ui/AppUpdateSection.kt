package top.ntutn.awall.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import top.ntutn.awall.BuildConfig
import top.ntutn.awall.R
import top.ntutn.awall.update.AppUpdateViewModel
import top.ntutn.updater.compose.UpdateHost
import top.ntutn.updater.compose.UpdateUiState

@Composable
internal fun AppUpdateSection(updates: AppUpdateViewModel, foreground: Boolean) {
    Text(
        stringResource(R.string.app_version, BuildConfig.VERSION_NAME),
        style = MaterialTheme.typography.bodySmall,
    )
    if (!updates.enabled) return
    val controller = updates.controller
    if (controller == null) {
        Text(stringResource(R.string.update_unsupported), style = MaterialTheme.typography.bodySmall)
        return
    }
    val state by controller.state.collectAsState()
    OutlinedButton(
        onClick = { controller.check() },
        enabled = foreground && state !is UpdateUiState.Checking,
    ) {
        Text(stringResource(R.string.check_for_update))
    }
    if (foreground) UpdateHost(controller)
}
