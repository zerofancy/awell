package top.ntutn.awall

import android.content.Intent
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
import top.ntutn.awall.update.apkInstallIntent

@RunWith(AndroidJUnit4::class)
class ApkInstallerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun sharesOwnApkThroughReadOnlyContentUri() {
        withCopiedApk(context.applicationInfo.sourceDir) { apk ->
            val intent = apkInstallIntent(context, apk.path)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("application/vnd.android.package-archive", intent.type)
            assertEquals("content", intent.data?.scheme)
            assertEquals("${context.packageName}.update-files", intent.data?.authority)
            assertEquals(intent.data, intent.clipData?.getItemAt(0)?.uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            context.contentResolver.openInputStream(requireNotNull(intent.data)).use { stream ->
                assertEquals('P'.code, stream?.read())
                assertEquals('K'.code, stream?.read())
            }
        }
    }

    @Test
    fun rejectsAnotherApplicationsApk() {
        withCopiedApk(instrumentation.context.applicationInfo.sourceDir) { apk ->
            assertThrows(IllegalArgumentException::class.java) { apkInstallIntent(context, apk.path) }
        }
    }

    @Test
    fun providerIsPrivateAndDoesNotExposeOtherFiles() {
        @Suppress("DEPRECATION")
        val provider = context.packageManager.resolveContentProvider("${context.packageName}.update-files", 0)
        assertFalse(requireNotNull(provider).exported)
        assertTrue(provider.grantUriPermissions)
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, "${context.packageName}.update-files", File(context.filesDir, "settings.xml"))
        }
    }

    private fun withCopiedApk(source: String, test: (File) -> Unit) {
        val root = File(context.filesDir, "kmp-updater").apply { mkdirs() }.canonicalFile
        val apk = File.createTempFile("installer-test-", ".apk", root)
        try {
            File(source).copyTo(apk, overwrite = true)
            test(apk)
        } finally {
            check(apk.canonicalFile.parentFile == root)
            check(apk.delete())
        }
    }
}
