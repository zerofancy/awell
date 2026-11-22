package top.ntutn.awall

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.ntutn.updater.UpdateApkFileProvider

@RunWith(AndroidJUnit4::class)
class ApkInstallerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val authority = "${context.packageName}.kmp-updater.install"

    @Test
    @Suppress("DEPRECATION") // The int flag overload is supported on API 24.
    fun manifestDeclaresInstallPermissionAndMergesThePrivateSdkProvider() {
        val manager = context.packageManager
        val info = manager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions.orEmpty().contains(Manifest.permission.REQUEST_INSTALL_PACKAGES))

        val provider = requireNotNull(manager.resolveContentProvider(authority, 0))
        assertEquals(UpdateApkFileProvider::class.java.name, provider.name)
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
    }

    @Test
    fun sdkProviderMapsTheDownloadDirectoryOnly() {
        val apk = File(context.filesDir, "kmp-updater/installer-test.apk")
        val uri = FileProvider.getUriForFile(context, authority, apk)
        assertEquals("content", uri.scheme)
        assertEquals(authority, uri.authority)
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, authority, File(context.filesDir, "settings.xml"))
        }
    }
}
