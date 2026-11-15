package top.ntutn.awall.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.ntutn.awall.R
import top.ntutn.awall.update.apkInstallIntent
import top.ntutn.awall.update.canInstallUpdates
import top.ntutn.updater.compose.CompletedDownloadAction
import top.ntutn.updater.compose.UpdateController

/** Register outside the foreground-only UpdateHost, so the settings result survives recreation. */
@Composable
internal fun apkInstallAction(controller: UpdateController): CompletedDownloadAction {
    val context = LocalContext.current
    val label = stringResource(R.string.update_install)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // The Settings activity's resultCode is not authoritative; query the actual permission.
        if (canInstallUpdates(context)) {
            controller.performCompletedAction(CompletedDownloadAction(label) { file ->
                launchApkInstaller(context, file.localPath)
            })
        } else {
            Toast.makeText(context, R.string.update_install_permission_denied, Toast.LENGTH_LONG).show()
        }
    }
    return CompletedDownloadAction(label) { file ->
        if (!canInstallUpdates(context)) {
            permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = "package:${context.packageName}".toUri()
            })
            // Only authorization was dispatched. The result callback attempts installation afresh.
            true
        } else {
            launchApkInstaller(context, file.localPath)
        }
    }
}

private suspend fun launchApkInstaller(context: Context, localPath: String): Boolean {
    val intent = withContext(Dispatchers.IO) { apkInstallIntent(context, localPath) }
    context.startActivity(intent)
    // This means the system UI was opened, not that installation succeeded.
    return true
}
