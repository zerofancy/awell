package top.ntutn.awall

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import top.ntutn.awall.photos.DirectoryStatus
import top.ntutn.awall.photos.PhotoDirectoryRepository
import top.ntutn.awall.photos.PhotoLoader
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PhotoDirectoryRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val authority = "top.ntutn.awell.test.photos"
    private val root = DocumentsContract.buildTreeDocumentUri(authority, "root")
    private val empty = DocumentsContract.buildTreeDocumentUri(authority, "empty")

    @Test fun persistsGrantRecursivelyScansAndLoadsNestedPhoto() = isolated { repository, preferences ->
        grant(root)
        repository.select(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val result = repository.withDirectory(root) { repository.scan(root) }
        assertEquals(3, result.uris.size)
        assertFalse(result.partial)
        val nested = result.uris.single { DocumentsContract.getDocumentId(it).contains("deep") }
        val bitmap = PhotoLoader(context.contentResolver).load(nested, 100, 100, 1024 * 1024)
        assertEquals(100, bitmap.width)
        bitmap.recycle()
        val restored = PhotoDirectoryRepository(context, preferences)
        assertEquals(root, restored.directory.value!!.uri)
        assertTrue(restored.hasReadGrant(root))
        restored.refresh()
        assertEquals(3, restored.ui.value.count)
        assertEquals(DirectoryStatus.READY, restored.ui.value.status)
    }

    @Test fun cancelledOrFailedSelectionPreservesPreviousDirectoryAndGrant() = isolated { repository, _ ->
        grant(root)
        repository.select(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            repository.select(empty, 0)
            fail("Missing read flag must fail")
        } catch (_: IllegalArgumentException) { }
        assertEquals(root, repository.directory.value!!.uri)
        assertTrue(repository.hasReadGrant(root))
        repository.select(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(repository.hasReadGrant(root))
    }

    @Test fun obsoleteGrantIsReleasedOnlyAfterReaderExits() = isolated { repository, _ ->
        grant(root)
        grant(empty)
        repository.select(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val entered = CompletableDeferred<Unit>()
        val exit = CompletableDeferred<Unit>()
        val reader = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch {
            repository.withDirectory(root) { entered.complete(Unit); exit.await() }
        }
        entered.await()
        repository.select(empty, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(repository.hasReadGrant(root))
        exit.complete(Unit)
        reader.join()
        assertFalse(repository.hasReadGrant(root))
        assertTrue(repository.hasReadGrant(empty))
        repository.refresh()
        assertEquals(DirectoryStatus.EMPTY, repository.ui.value.status)
    }

    @Test fun revokedPermissionIsReportedAfterRestoringConfiguration() = isolated { repository, preferences ->
        grant(root)
        repository.select(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.contentResolver.releasePersistableUriPermission(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val restored = PhotoDirectoryRepository(context, preferences)
        restored.refresh()
        assertEquals(DirectoryStatus.INACCESSIBLE, restored.ui.value.status)
    }

    private suspend fun grant(tree: Uri) {
        context.startActivity(Intent().apply {
            setClassName("top.ntutn.awell.test", "top.ntutn.awall.FixtureGrantActivity")
            data = tree
            putExtra("recipient", context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        kotlinx.coroutines.withTimeout(3000) {
            while (context.checkUriPermission(tree, android.os.Process.myPid(), android.os.Process.myUid(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                kotlinx.coroutines.delay(10)
            }
        }
    }

    private fun isolated(body: suspend (PhotoDirectoryRepository, android.content.SharedPreferences) -> Unit) = runBlocking {
        withContext(Dispatchers.Main) {
            val preferences = context.getSharedPreferences("photo_test_${UUID.randomUUID()}", Context.MODE_PRIVATE)
            try { body(PhotoDirectoryRepository(context, preferences), preferences) }
            finally {
                listOf(root, empty).forEach {
                    try { context.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    catch (_: SecurityException) { }
                }
                preferences.edit().clear().apply()
            }
        }
    }
}
