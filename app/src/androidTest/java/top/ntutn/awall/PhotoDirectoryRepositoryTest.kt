package top.ntutn.awall

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.core.DataStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import top.ntutn.awall.photos.*
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PhotoDirectoryRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val authority = "top.ntutn.awell.test.photos"
    private val root = DocumentsContract.buildTreeDocumentUri(authority, "root")
    private val empty = DocumentsContract.buildTreeDocumentUri(authority, "empty")

    @Test fun persistsGrantRecursivelyScansAndLoadsNestedPhoto() = isolated { fixture ->
        val repository = fixture.repository
        grant(root)
        repository.select(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val result = repository.withDirectory(root) { repository.scan(root) }
        assertEquals(3, result.uris.size)
        assertFalse(result.partial)
        val nested = result.uris.single { DocumentsContract.getDocumentId(it).contains("deep") }
        val bitmap = PhotoLoader(context.contentResolver).load(nested, 100, 100, 1024 * 1024)
        assertEquals(100, bitmap.width)
        bitmap.recycle()
        val restored = fixture.reopen()
        assertEquals(root, restored.directory.value!!.uri)
        assertTrue(restored.hasReadGrant(root))
        restored.refresh()
        assertEquals(3, restored.ui.value.count)
        assertEquals(DirectoryStatus.READY, restored.ui.value.status)
    }

    @Test fun cancelledOrFailedSelectionPreservesPreviousDirectoryAndGrant() = isolated { fixture ->
        val repository = fixture.repository
        grant(root)
        repository.select(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            repository.select(empty, 0)
            fail("Missing read flag must fail")
        } catch (_: IllegalArgumentException) { }
        assertEquals(root, repository.directory.value!!.uri)
        assertTrue(repository.hasReadGrant(root))
        val oldRevision = repository.directory.value!!.revision
        repository.select(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(repository.directory.value!!.revision > oldRevision)
        assertTrue(repository.hasReadGrant(root))
    }

    @Test fun obsoleteGrantIsReleasedOnlyAfterReaderExits() = isolated { fixture ->
        val repository = fixture.repository
        grant(root)
        grant(empty)
        repository.select(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val entered = CompletableDeferred<Unit>()
        val exit = CompletableDeferred<Unit>()
        val reader = CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch {
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

    @Test fun revokedPermissionIsReportedAfterRestoringConfiguration() = isolated { fixture ->
        grant(root)
        fixture.repository.select(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.contentResolver.releasePersistableUriPermission(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val restored = fixture.reopen()
        restored.refresh()
        assertEquals(DirectoryStatus.INACCESSIBLE, restored.ui.value.status)
    }

    @Test fun settingsKeepIndependentSwitchesAndOneTimerBaseline() = isolated { fixture ->
        val repository = fixture.repository
        fixture.now = 10_000
        repository.setTimerEnabled(true)
        assertEquals(10_000L, repository.state.value!!.timerBaseAtMillis)
        repository.setUnlockEnabled(false)
        repository.setIntervalMillis(15 * 60 * 1_000L)
        fixture.now = 20_000
        repository.setTimerEnabled(true)
        assertEquals(10_000L, repository.state.value!!.timerBaseAtMillis)
        assertFalse(repository.state.value!!.unlockEnabled)
        repository.updateState { it.copy(currentPhotoUri = "content://photos/recent", timerBaseAtMillis = fixture.now) }
        val restored = fixture.reopen()
        assertEquals("content://photos/recent", restored.state.value!!.currentPhotoUri)
        assertEquals(20_000L, restored.state.value!!.timerBaseAtMillis)
        assertEquals(15 * 60 * 1_000L, restored.state.value!!.intervalMillis)
        restored.setTimerEnabled(false)
        assertNull(restored.state.value!!.timerBaseAtMillis)
        assertFalse(restored.state.value!!.unlockEnabled)
    }

    @Test fun migratesOnlyLegacyDirectoryAndPhotoThenUsesNewRecord() = isolated(load = false) { fixture ->
        fixture.preferences.edit().putString("tree_uri", root.toString()).putString("tree_name", "Old photos")
            .putString("last_photo", "content://photos/recent").commit()
        val repository = fixture.open()
        val migrated = repository.state.value!!
        assertEquals(root.toString(), migrated.directoryUri)
        assertEquals("Old photos", migrated.directoryName)
        assertEquals("content://photos/recent", migrated.currentPhotoUri)
        assertTrue(migrated.unlockEnabled)
        assertFalse(migrated.timerEnabled)
        assertNull(migrated.timerBaseAtMillis)
        assertFalse(fixture.preferences.contains("tree_uri"))
        repository.setUnlockEnabled(false)
        fixture.preferences.edit().putString("tree_uri", empty.toString())
            .putString("last_photo", "content://photos/stale").commit()
        val restored = fixture.reopen()
        assertFalse(restored.state.value!!.unlockEnabled)
        assertEquals(root.toString(), restored.state.value!!.directoryUri)
        assertEquals(migrated.currentPhotoUri, restored.state.value!!.currentPhotoUri)
    }

    @Test fun concurrentSettingAndSceneWritesKeepBothChanges() = isolated { fixture ->
        val repository = fixture.repository
        kotlinx.coroutines.coroutineScope {
            launch { repository.setUnlockEnabled(false) }
            launch {
                repository.updateState { it.copy(currentPhotoUri = "content://photos/committed",
                    timerEnabled = true, timerBaseAtMillis = 123_000) }
            }
        }
        val saved = fixture.reopen().state.value!!
        assertFalse(saved.unlockEnabled)
        assertEquals("content://photos/committed", saved.currentPhotoUri)
        assertEquals(123_000L, saved.timerBaseAtMillis)
    }

    @Test fun failedWriteDoesNotPublishPhotoOrTimerAndFailedLoadStaysUninitialized() = runBlocking {
        withContext(Dispatchers.Main) {
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            var failLoad = true
            val previous = WallpaperState(timerEnabled = true, timerBaseAtMillis = 100)
            val failingStore = object : DataStore<WallpaperState> {
                override val data: Flow<WallpaperState> = flow {
                    if (failLoad) throw IOException("Unavailable") else emit(previous)
                }
                override suspend fun updateData(transform: suspend (WallpaperState) -> WallpaperState): WallpaperState {
                    throw IOException("Cannot persist")
                }
            }
            try {
                val repository = PhotoDirectoryRepository(context, failingStore, scope = owner)
                repository.refresh()
                assertNull(repository.state.value)
                assertTrue(repository.storageError.value)
                failLoad = false
                repository.retryLoading()
                assertEquals(previous, repository.state.value)
                try {
                    repository.updateState { it.copy(currentPhotoUri = "new", timerBaseAtMillis = 200) }
                    fail("Write must fail")
                } catch (_: IOException) { }
                assertEquals(previous, repository.state.value)
                assertTrue(repository.storageError.value)
            } finally { owner.coroutineContext[Job]!!.cancelAndJoin() }
        }
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

    private fun isolated(load: Boolean = true, body: suspend (Fixture) -> Unit) = runBlocking {
        withContext(Dispatchers.Main) {
            val fixture = Fixture()
            try {
                if (load) fixture.open()
                body(fixture)
            } finally {
                fixture.close()
                listOf(root, empty).forEach {
                    try { context.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    catch (_: SecurityException) { }
                }
                fixture.file.delete()
                fixture.preferences.edit().clear().commit()
            }
        }
    }

    private inner class Fixture {
        private val id = UUID.randomUUID().toString()
        val file = File(context.cacheDir, "wallpaper_$id.json")
        val preferences = context.getSharedPreferences("photo_test_$id", Context.MODE_PRIVATE)
        var now = 1_000L
        lateinit var repository: PhotoDirectoryRepository
        private var storeScope: CoroutineScope? = null
        private var repositoryScope: CoroutineScope? = null

        suspend fun open(): PhotoDirectoryRepository {
            val storage = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { storeScope = it }
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { repositoryScope = it }
            val dataStore = createWallpaperDataStore(context, file, storage, "photo_test_$id")
            return PhotoDirectoryRepository(context, dataStore, { now }, owner).also {
                repository = it
                it.retryLoading()
            }
        }

        suspend fun reopen(): PhotoDirectoryRepository {
            close()
            return open()
        }

        suspend fun close() {
            repositoryScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            storeScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            repositoryScope = null
            storeScope = null
        }
    }
}
