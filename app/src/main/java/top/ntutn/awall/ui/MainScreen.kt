package top.ntutn.awall.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.Button
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import top.ntutn.awall.R
import top.ntutn.awall.ui.theme.AWallTheme

@Composable
fun MainScreen(onOpenWallpaperPreview: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize().systemBarsPadding(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }
    ) { contentPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            contentAlignment = Alignment.Center
        ) {
            Button(onClick = onOpenWallpaperPreview) {
                Text(stringResource(R.string.wallpaper_preview_button))
            }
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MainScreenPreview() {
    AWallTheme {
        MainScreen(onOpenWallpaperPreview = {})
    }
}
