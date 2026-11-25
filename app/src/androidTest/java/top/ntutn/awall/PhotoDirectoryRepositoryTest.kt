package top.ntutn.awall

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.core.DataStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import top.ntutn.awall.photos.*

@RunWith(AndroidJUnit4::class)
class PhotoDirectoryRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
    private val authority = "$testPackage.photos"
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
        val previous = repository.directory.value
        try {
            repository.select(empty, 0)
            fail("Missing read flag must fail")
        } catch (_: IllegalArgumentException) { }
        assertEquals(previous, repository.directory.value)
        assertTrue(repository.hasReadGrant(root))
    }

    @Test fun reselectingUnchangedDirectoryRefreshesItsRevisionAndScan() = isolated { fixture ->
        val repository = fixture.repository
        grant(root)
        repository.select(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        repository.refresh()
        assertEquals(DirectoryStatus.READY, repository.ui.value.status)
        val previous = requireNotNull(repository.state.value)
        assertNull(previous.currentPhotoUri)

        repository.select(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val expected = previous.copy(directoryRevision = previous.directoryRevision + 1)
        assertEquals(expected, repository.awaitState { it == expected })
        val selected = requireNotNull(repository.directory.value)
        assertEquals(root, selected.uri)
        assertEquals(expected.directoryRevision, selected.revision)
        assertEquals(DirectoryStatus.SCANNING, repository.ui.value.status)
        assertTrue(repository.hasReadGrant(root))
        repository.refresh()
        assertEquals(DirectoryStatus.READY, repository.ui.value.status)
        assertEquals(3, repository.ui.value.count)
        assertEquals(selected, fixture.reopen().directory.value)
    }

    @Test fun obsoleteGrantIsReleasedOnlyAfterReaderExits() = isolated { fixture ->
        val repository = fixture.repository
        grant(root, empty)
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

    @Test fun committedReselectionKeepsItsGrantWhileTheSubscriptionRecovers() = isolated(load = false) {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val persisted = MutableStateFlow(WallpaperState())
        var failRootRead = false
        val store = object : DataStore<WallpaperState> {
            override val data: Flow<WallpaperState> = flow {
                persisted.collect { saved ->
                    if (failRootRead && saved.directoryUri == root.toString()) throw IOException("Read failed")
                    emit(saved)
                }
            }

            override suspend fun updateData(transform: suspend (WallpaperState) -> WallpaperState): WallpaperState =
                transform(persisted.value).also { persisted.value = it }
        }
        try {
            val repository = PhotoDirectoryRepository(context, store, scope = owner)
            grant(root, empty)
            repository.select(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            coroutineScope {
                val entered = CompletableDeferred<Unit>()
                val exit = CompletableDeferred<Unit>()
                val reader = launch { repository.withDirectory(root) { entered.complete(Unit); exit.await() } }
                try {
                    entered.await()
                    repository.select(empty, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    failRootRead = true
                    try {
                        repository.select(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        fail("The observation should fail after the selection is saved")
                    } catch (_: IOException) { }
                    assertEquals(root.toString(), persisted.value.directoryUri)
                    assertTrue(repository.storageError.value)
                } finally { exit.complete(Unit) }
                reader.join()
            }
            assertTrue(repository.hasReadGrant(root))
            failRootRead = false
            repository.retryLoading()
            assertEquals(root, repository.directory.value?.uri)
            assertTrue(repository.hasReadGrant(root))
            assertFalse(repository.hasReadGrant(empty))
        } finally { owner.coroutineContext[Job]!!.cancelAndJoin() }
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
        assertEquals(10_000L, repository.awaitState { it.timerEnabled }.timerBaseAtMillis)
        repository.setUnlockEnabled(false)
        repository.setInterval(15.minutes)
        fixture.now = 20_000
        repository.setTimerEnabled(true)
        val active = repository.awaitState { !it.unlockEnabled && it.interval == 15.minutes }
        assertEquals(10_000L, active.timerBaseAtMillis)
        repository.updateState { it.copy(currentPhotoUri = "content://photos/recent", timerBaseAtMillis = fixture.now) }
        val restored = fixture.reopen()
        assertEquals("content://photos/recent", restored.state.value!!.currentPhotoUri)
        assertEquals(20_000L, restored.state.value!!.timerBaseAtMillis)
        assertEquals(15.minutes, restored.state.value!!.interval)
        restored.setTimerEnabled(false)
        val stopped = restored.awaitState { !it.timerEnabled }
        assertNull(stopped.timerBaseAtMillis)
        assertFalse(stopped.unlockEnabled)
    }

    @Test fun migratesOnlyLegacyDirectoryAndPhotoThenUsesNewRecord() = isolated(load = false) { fixture ->
        fixture.preferences.edit().putString("tree_uri", root.toString()).putString("tree_name", "Old photos")
            .putString("last_photo", "content://photos/recent").commit()
        val repository = fixture.open()
        val migrated = repository.state.value!!
        assertEquals(root.toString(), migrated.directoryUri)
        assertEquals("Old photos", migrated.directoryName)
        assertEquals("content://photos/recent", migrated.currentPhotoUri)
        assertEquals(0L, migrated.directoryRevision)
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

    @Test fun observesWritesMadeDirectlyThroughDataStore() = isolated { fixture ->
        val committed = fixture.dataStore.updateData { it.copy(nightDimmingEnabled = true) }
        assertEquals(committed, fixture.repository.awaitState { it.nightDimmingEnabled })
    }

    @Test fun concurrentSettingAndSceneWritesKeepAllChanges() = isolated { fixture ->
        val repository = fixture.repository
        coroutineScope {
            launch { repository.setNightDimmingEnabled(true) }
            launch { repository.setUnlockEnabled(false) }
            launch {
                repository.updateState { it.copy(currentPhotoUri = "content://photos/committed",
                    timerEnabled = true, timerBaseAtMillis = 123_000) }
            }
        }
        val saved = repository.awaitState {
            it.nightDimmingEnabled && !it.unlockEnabled && it.currentPhotoUri == "content://photos/committed"
        }
        assertTrue(saved.timerEnabled)
        assertEquals(123_000L, saved.timerBaseAtMillis)
        assertEquals(saved, fixture.reopen().state.value)
    }

    @Test fun failedWriteDoesNotPublishPhotoOrTimerAndFailedLoadStaysUninitialized() = runBlocking {
        withContext(Dispatchers.Main) {
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            var failLoad = true
            var failWrite = true
            val previous = WallpaperState(timerEnabled = true, timerBaseAtMillis = 100)
            val persisted = MutableStateFlow(previous)
            val failingStore = object : DataStore<WallpaperState> {
                override val data: Flow<WallpaperState> = flow {
                    if (failLoad) throw IOException("Unavailable")
                    emitAll(persisted)
                }
                override suspend fun updateData(transform: suspend (WallpaperState) -> WallpaperState): WallpaperState {
                    if (failWrite) throw IOException("Cannot persist")
                    return transform(persisted.value).also { persisted.value = it }
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
                failWrite = false
                assertEquals(previous, repository.updateState { it })
                withTimeout(3.seconds) { repository.storageError.first { !it } }
                assertEquals(previous, repository.state.value)
                val recovered = repository.updateState { it.copy(currentPhotoUri = "new", timerBaseAtMillis = 200) }
                assertEquals(recovered, repository.awaitState { it.currentPhotoUri == "new" })
            } finally { owner.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }

    @Test fun backgroundStyleSurvivesReopenWithoutChangingPhotoOrTimer() = isolated { fixture ->
        val repository = fixture.repository
        val old = repository.updateState { it.copy(currentPhotoUri = "content://photos/a", timerEnabled = true,
            timerBaseAtMillis = 42, unlockEnabled = false) }
        for (style in listOf(BackgroundStyle.BLURRED_PHOTO, BackgroundStyle.DARK_TEXTURE, BackgroundStyle.LOW_POLY)) {
            fixture.repository.setBackgroundStyle(style)
            assertEquals(old.copy(backgroundStyle = style), fixture.reopen().state.value)
        }
    }

    private suspend fun PhotoDirectoryRepository.awaitState(predicate: (WallpaperState) -> Boolean): WallpaperState =
        withTimeout(3.seconds) { state.filterNotNull().first(predicate) }

    private suspend fun grant(vararg trees: Uri) {
        context.startActivity(Intent().apply {
            setClassName(testPackage, "top.ntutn.awall.FixtureGrantActivity")
            data = trees.first()
            putExtra("uris", trees.map(Uri::toString).toTypedArray())
            putExtra("recipient", context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        kotlinx.coroutines.withTimeout(3.seconds) {
            while (trees.any { tree -> context.checkUriPermission(tree, android.os.Process.myPid(), android.os.Process.myUid(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                kotlinx.coroutines.delay(10.milliseconds)
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
        lateinit var dataStore: DataStore<WallpaperState>
            private set
        private var storeScope: CoroutineScope? = null
        private var repositoryScope: CoroutineScope? = null

        suspend fun open(): PhotoDirectoryRepository {
            val storage = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { storeScope = it }
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { repositoryScope = it }
            dataStore = createWallpaperDataStore(context, file, storage, "photo_test_$id")
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
