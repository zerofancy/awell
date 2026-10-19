package top.ntutn.awall.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import top.ntutn.awall.R
import top.ntutn.awall.photos.DirectoryStatus
import top.ntutn.awall.photos.DirectoryUiState
import top.ntutn.awall.ui.theme.AWallTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenWallpaperPreview: () -> Unit,
    modifier: Modifier = Modifier,
    directoryState: DirectoryUiState = DirectoryUiState(),
    selecting: Boolean = false,
    onSelectDirectory: () -> Unit = {},
) {
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
            OutlinedButton(onClick = onSelectDirectory, enabled = !selecting) {
                Text(stringResource(if (directoryState.directory == null) R.string.select_directory else R.string.change_directory))
            }
            Text(stringResource(R.string.photo_wallpaper_hint), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onOpenWallpaperPreview) { Text(stringResource(R.string.wallpaper_preview_button)) }
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MainScreenPreview() {
    AWallTheme { MainScreen(onOpenWallpaperPreview = {}) }
}
