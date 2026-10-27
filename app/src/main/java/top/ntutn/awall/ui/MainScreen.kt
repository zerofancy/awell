package top.ntutn.awall.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import top.ntutn.awall.R
import top.ntutn.awall.photos.DEFAULT_INTERVAL_MILLIS
import top.ntutn.awall.photos.DirectoryStatus
import top.ntutn.awall.photos.DirectoryUiState
import top.ntutn.awall.photos.WallpaperState
import top.ntutn.awall.ui.theme.AWallTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenWallpaperPreview: () -> Unit,
    modifier: Modifier = Modifier,
    directoryState: DirectoryUiState = DirectoryUiState(),
    selecting: Boolean = false,
    onSelectDirectory: () -> Unit = {},
    wallpaperState: WallpaperState? = null,
    savingSettings: Boolean = false,
    storageError: Boolean = false,
    onUnlockEnabledChanged: (Boolean) -> Unit = {},
    onTimerEnabledChanged: (Boolean) -> Unit = {},
    onIntervalChanged: (Long) -> Unit = {},
    onRetryLoading: () -> Unit = {},
) {
    val canEditSettings = wallpaperState != null && !storageError && !savingSettings && !selecting
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { contentPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding)
                .verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.photo_directory_title), style = MaterialTheme.typography.titleMedium)
            Text(directoryState.directory?.name ?: stringResource(R.string.directory_not_selected))
            val message = when (directoryState.status) {
                DirectoryStatus.UNSELECTED -> R.string.directory_hint
                DirectoryStatus.SCANNING -> R.string.directory_scanning
                DirectoryStatus.READY -> R.string.directory_ready
                DirectoryStatus.EMPTY, DirectoryStatus.UNDECODABLE -> R.string.directory_empty
                DirectoryStatus.INACCESSIBLE -> R.string.directory_inaccessible
                DirectoryStatus.RETRY -> R.string.directory_retry
            }
            Text(stringResource(message, directoryState.count))
            if (directoryState.partial) Text(stringResource(R.string.directory_partial))
            OutlinedButton(onClick = onSelectDirectory, enabled = canEditSettings) {
                Text(stringResource(if (directoryState.directory == null) R.string.select_directory else R.string.change_directory))
            }
            Text(stringResource(R.string.automatic_rotation_title), style = MaterialTheme.typography.titleMedium)
            when {
                storageError -> {
                    Text(stringResource(R.string.settings_load_failed))
                    OutlinedButton(onClick = onRetryLoading, enabled = !savingSettings && !selecting) {
                        Text(stringResource(R.string.retry_settings))
                    }
                }
                wallpaperState == null -> Text(stringResource(R.string.settings_loading))
            }
            RotationSwitch(
                label = stringResource(R.string.rotate_on_unlock),
                checked = wallpaperState?.unlockEnabled ?: true,
                enabled = canEditSettings,
                tag = "unlock_setting",
                onCheckedChange = onUnlockEnabledChanged,
            )
            RotationSwitch(
                label = stringResource(R.string.rotate_on_timer),
                checked = wallpaperState?.timerEnabled ?: false,
                enabled = canEditSettings,
                tag = "timer_setting",
                onCheckedChange = onTimerEnabledChanged,
            )
            IntervalSetting(
                intervalMillis = wallpaperState?.intervalMillis ?: DEFAULT_INTERVAL_MILLIS,
                enabled = canEditSettings && wallpaperState.timerEnabled,
                onIntervalChanged = onIntervalChanged,
            )
            Text(stringResource(R.string.automatic_rotation_hint), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.photo_wallpaper_hint), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onOpenWallpaperPreview) { Text(stringResource(R.string.wallpaper_preview_button)) }
        }
    }
}

@Composable
private fun RotationSwitch(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(tag)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun IntervalSetting(intervalMillis: Long, enabled: Boolean, onIntervalChanged: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = intervalOptions.firstOrNull { it.first == intervalMillis }
        ?: (DEFAULT_INTERVAL_MILLIS to R.string.interval_1_hour)
    Box {
        OutlinedButton(
            modifier = Modifier.testTag("interval_setting"),
            enabled = enabled,
            onClick = { expanded = true },
        ) {
            Text(stringResource(R.string.rotation_interval, stringResource(selected.second)))
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            intervalOptions.forEach { (millis, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        expanded = false
                        onIntervalChanged(millis)
                    },
                )
            }
        }
    }
}

private val intervalOptions = listOf(
    15 * 60_000L to R.string.interval_15_minutes,
    30 * 60_000L to R.string.interval_30_minutes,
    DEFAULT_INTERVAL_MILLIS to R.string.interval_1_hour,
    6 * DEFAULT_INTERVAL_MILLIS to R.string.interval_6_hours,
    24 * DEFAULT_INTERVAL_MILLIS to R.string.interval_24_hours,
)

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MainScreenPreview() {
    AWallTheme { MainScreen(onOpenWallpaperPreview = {}, wallpaperState = WallpaperState()) }
}
