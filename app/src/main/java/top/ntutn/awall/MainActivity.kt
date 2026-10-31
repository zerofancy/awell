package top.ntutn.awall

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.ntutn.awall.photos.PhotoDirectoryRepository
import top.ntutn.awall.ui.MainScreen
import top.ntutn.awall.ui.theme.AWallTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = PhotoDirectoryRepository.get(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { repository.refresh() }
        }
        setContent {
            AWallTheme {
                val state by repository.ui.collectAsState()
                val wallpaperState by repository.state.collectAsState()
                val storageError by repository.storageError.collectAsState()
                val scope = rememberCoroutineScope()
                var selecting by remember { mutableStateOf(false) }
                var savingSettings by remember { mutableStateOf(false) }
                fun saveSettings(errorMessage: Int = R.string.settings_save_failed, update: suspend () -> Unit) {
                    if (savingSettings || selecting || wallpaperState == null || storageError) return
                    savingSettings = true
                    scope.launch {
                        try { update() }
                        catch (error: CancellationException) { throw error }
                        catch (_: Exception) {
                            Toast.makeText(this@MainActivity, errorMessage, Toast.LENGTH_LONG).show()
                        } finally { savingSettings = false }
                    }
                }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    val data = result.data
                    val uri = data?.data
                    if (result.resultCode == RESULT_OK && uri != null) {
                        selecting = true
                        scope.launch {
                            try {
                                repository.select(uri, data.flags)
                                repository.refresh()
                            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                                Toast.makeText(this@MainActivity, R.string.directory_selection_failed, Toast.LENGTH_LONG).show()
                            } catch (error: CancellationException) { throw error }
                            catch (_: Exception) {
                                Toast.makeText(this@MainActivity, R.string.directory_selection_failed, Toast.LENGTH_LONG).show()
                            } finally { selecting = false }
                        }
                    }
                }
                MainScreen(
                    onOpenWallpaperPreview = ::openWallpaperPreview,
                    directoryState = state,
                    selecting = selecting,
                    wallpaperState = wallpaperState,
                    savingSettings = savingSettings,
                    storageError = storageError,
                    onBackgroundStyleChanged = { style ->
                        saveSettings(R.string.background_save_failed) { repository.setBackgroundStyle(style) }
                    },
                    onUnlockEnabledChanged = { enabled -> saveSettings { repository.setUnlockEnabled(enabled) } },
                    onTimerEnabledChanged = { enabled -> saveSettings { repository.setTimerEnabled(enabled) } },
                    onIntervalChanged = { interval -> saveSettings { repository.setIntervalMillis(interval) } },
                    onRetryLoading = {
                        if (!savingSettings && !selecting) {
                            savingSettings = true
                            scope.launch {
                                try { repository.refresh() }
                                catch (error: CancellationException) { throw error }
                                catch (_: Exception) {
                                    Toast.makeText(this@MainActivity, R.string.settings_load_failed, Toast.LENGTH_LONG).show()
                                }
                                finally { savingSettings = false }
                            }
                        }
                    },
                    onSelectDirectory = {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                        }
                        try { launcher.launch(intent) }
                        catch (_: ActivityNotFoundException) {
                            Toast.makeText(this, R.string.directory_picker_unavailable, Toast.LENGTH_LONG).show()
                        }
                    },
                )
            }
        }
    }

    private fun openWallpaperPreview() {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this@MainActivity, AWallpaperService::class.java))
        }
        try { startActivity(intent) }
        catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.wallpaper_preview_unavailable, Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.wallpaper_preview_restricted, Toast.LENGTH_LONG).show()
        }
    }
}
