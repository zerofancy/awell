package top.ntutn.awall.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import top.ntutn.awall.BuildConfig
import top.ntutn.updater.TargetDetection
import top.ntutn.updater.UpdateVersion
import top.ntutn.updater.UpdaterConfig
import top.ntutn.updater.compose.UpdateController
import top.ntutn.updater.createAndroidBrowserOpener
import top.ntutn.updater.createUpdateChecker
import top.ntutn.updater.detectUpdateTarget

internal class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    val enabled = BuildConfig.UPDATE_BASE_URL.isNotEmpty()
    private val target = if (enabled) detectUpdateTarget() as? TargetDetection.Supported else null
    private val checker = target?.let {
        createUpdateChecker(
            UpdaterConfig(
                baseUrl = BuildConfig.UPDATE_BASE_URL,
                software = "awell",
                currentVersion = UpdateVersion(BuildConfig.VERSION_CODE.toLong(), BuildConfig.VERSION_NAME),
                target = it.target,
                packageType = "apk",
                allowInsecureHttp = BuildConfig.DEBUG,
            )
        )
    }
    val controller = checker?.let {
        UpdateController(it, createAndroidBrowserOpener(application), viewModelScope)
    }

    override fun onCleared() {
        controller?.dispose()
        checker?.close()
        super.onCleared()
    }
}
