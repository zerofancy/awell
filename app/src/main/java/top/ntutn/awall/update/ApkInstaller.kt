package top.ntutn.awall.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

internal fun canInstallUpdates(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

/** Call from IO: only completed APKs under the SDK's private directory may be shared. */
internal fun apkInstallIntent(context: Context, localPath: String): Intent {
    val file = requireUpdateApk(File(context.filesDir, "kmp-updater"), localPath)
    @Suppress("DEPRECATION")
    val archive = context.packageManager.getPackageArchiveInfo(file.path, 0)
    require(archive?.packageName == context.packageName) { "APK does not belong to this application" }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.update-files", file)
    return Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        clipData = ClipData.newRawUri("APK update", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
