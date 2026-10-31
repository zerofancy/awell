package top.ntutn.awall

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import top.ntutn.awall.photos.*
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class WallpaperPhotoCoordinatorTest {
    @Test fun unlockUpdatesBackgroundAndPhotoTogetherButVisibilityDoesNot() = scenario { source, coordinator, client ->
        coordinator.attach(client)
        coordinator.visibilityChanged()
        await { client.commits.size == 1 }
        val first = client.commits.single()
        repeat(3) { coordinator.visibilityChanged() }
        delay(60)
        assertEquals(1, client.commits.size)
        coordinator.unlocked()
        await { client.commits.size == 2 }
        val second = client.commits.last()
        assertNotEquals(first.photo, second.photo)
        assertNotEquals(first.background, second.background)
        assertEquals(second.photo, source.last)
    }

    @Test fun hiddenUnlocksCoalesceUntilVisible() = scenario { source, coordinator, client ->
        client.ready = false
        coordinator.attach(client)
        repeat(3) { coordinator.unlocked() }
        delay(40)
        assertEquals(0, source.scans)
        client.ready = true
        coordinator.visibilityChanged()
        await { client.commits.size == 1 }
        assertEquals(1, source.scans)
    }

    @Test fun officialEnginesShareOneScene() = scenario { _, coordinator, client ->
        val other = FakeClient()
        try {
            coordinator.attach(client)
            coordinator.attach(other)
            coordinator.visibilityChanged()
            await { client.commits.isNotEmpty() && other.commits.isNotEmpty() }
            assertSame(client.commits.last(), other.commits.last())
            assertNotSame(client.bitmap, other.bitmap)
        } finally { coordinator.detach(other); other.close() }
    }

    @Test fun previewDoesNotConsumeUnlockOrWriteHistory() = scenario(preview = true) { source, coordinator, client ->
        coordinator.attach(client)
        coordinator.visibilityChanged()
        await { client.commits.size == 1 }
        coordinator.unlocked()
        delay(50)
        assertEquals(1, client.commits.size)
        assertEquals(0, source.saves)
    }

    @Test fun staleDecodeCannotOverwriteNewDirectory() = scenario { source, coordinator, client ->
        val oldTree = source.directory.value!!.uri
        client.prepareHook = { photo ->
            if (photo.toString().contains("old")) withContext(NonCancellable) { delay(150) }
        }
        coordinator.attach(client)
        coordinator.visibilityChanged()
        await { client.preparing }
        val newTree = Uri.parse("content://photos/new")
        source.photos = listOf(Uri.parse("content://photos/new/photo"))
        source.state.value = source.state.value!!.copy(directoryUri = newTree.toString(), currentPhotoUri = null)
        source.directory.value = PhotoDirectory(newTree, "new")
        await { client.commits.any { it.photo != null && it.tree == newTree } }
        delay(200)
        assertFalse(client.commits.any { it.photo != null && it.tree == oldTree })
        assertEquals(newTree, client.commits.last().tree)
    }

    @Test fun timedOutScanKeepsWholeOldFrame() = scenario(timeoutMillis = 80) { source, coordinator, client ->
        coordinator.attach(client)
        coordinator.visibilityChanged()
        await { client.commits.size == 1 }
        val first = client.commits.last()
        source.scanDelay = 300
        coordinator.unlocked()
        await { source.status == DirectoryStatus.RETRY }
        assertEquals(listOf(first), client.commits)
    }

    @Test fun brokenAlternativesKeepOldPhotoAndBackground() = scenario { source, coordinator, client ->
        coordinator.attach(client)
        coordinator.visibilityChanged()
        await { client.commits.size == 1 }
        val first = client.commits.last()
        client.prepareHook = { if (it != first.photo) throw IOException("corrupt") }
        coordinator.unlocked()
        await { source.status == DirectoryStatus.UNDECODABLE }
        delay(30)
        assertEquals(listOf(first), client.commits)
    }

    @Test fun restartRestoresRecentPhoto() = scenario { source, coordinator, client ->
        source.last = source.photos.last()
        coordinator.attach(client)
        coordinator.visibilityChanged()
        await { client.commits.size == 1 }
        assertEquals(source.photos.last(), client.commits.single().photo)
    }

    @Test fun restartBeforeDeadlineRestoresPhotoWithoutMovingBase() = scenario { source, coordinator, client ->
        source.last = source.photos.last()
        source.state.value = source.state.value!!.copy(timerEnabled = true, timerBaseAtMillis = 5_000L)
        coordinator.attach(client)
        await { client.commits.isNotEmpty() }
        assertEquals(source.last, client.commits.single().photo)
        assertEquals(5_000L, source.state.value!!.timerBaseAtMillis)
        assertEquals(0, source.saves)
    }

    @Test fun overdueRestartSkipsOldPhotoAndOnlyCatchesUpOnce() = scenario { source, coordinator, client ->
        val old = source.photos.last()
        source.last = old
        source.state.value = source.state.value!!.copy(timerEnabled = true, timerBaseAtMillis = 0L, intervalMillis = 1_000L)
        coordinator.attach(client)
        await { client.commits.isNotEmpty() }
        assertNotEquals(old, client.commits.single().photo)
        assertEquals(10_000L, source.state.value!!.timerBaseAtMillis)
        repeat(5) { coordinator.visibilityChanged() }
        delay(50)
        assertEquals(1, source.saves)
        assertEquals(1, client.commits.size)
    }

    @Test fun hiddenOverdueStartupDoesNotScanOrAdvanceTime() = scenario { source, coordinator, client ->
        source.last = source.photos.first()
        source.state.value = source.state.value!!.copy(timerEnabled = true, timerBaseAtMillis = 0L, intervalMillis = 1_000L)
        client.ready = false
        coordinator.attach(client)
        repeat(4) { coordinator.visibilityChanged() }
        delay(50)
        assertEquals(0, source.scans)
        assertEquals(0, source.saves)
        client.ready = true
        coordinator.visibilityChanged()
        await { client.commits.isNotEmpty() }
        assertEquals(1, source.saves)
        assertEquals(10_000L, source.state.value!!.timerBaseAtMillis)
    }

    @Test fun restoringEmptyBackgroundKeepsTheOriginalBase() = scenario { source, coordinator, client ->
        source.photos = emptyList()
        source.state.value = source.state.value!!.copy(timerEnabled = true, timerBaseAtMillis = 5_000L)
        coordinator.attach(client)
        await { client.commits.isNotEmpty() }
        repeat(3) { coordinator.visibilityChanged() }
        delay(40)
        assertNull(client.commits.single().photo)
        assertEquals(5_000L, source.state.value!!.timerBaseAtMillis)
        assertEquals(0, source.saves)
    }

    @Test fun disabledUnlockDoesNotChangeTheSceneOrTimerBase() = scenario { source, coordinator, client ->
        source.state.value = source.state.value!!.copy(unlockEnabled = false, timerEnabled = true, timerBaseAtMillis = 5_000L)
        coordinator.attach(client)
        await { client.commits.isNotEmpty() }
        val base = source.state.value!!.timerBaseAtMillis
        repeat(3) { coordinator.unlocked() }
        delay(50)
        assertEquals(1, client.commits.size)
        assertEquals(base, source.state.value!!.timerBaseAtMillis)
    }

    @Test fun configurationChangeKeepsCurrentBackgroundAndBase() = scenario { source, coordinator, client ->
        coordinator.attach(client)
        await { client.commits.isNotEmpty() }
        val first = client.commits.first()
        source.state.value = source.state.value!!.copy(timerEnabled = true, timerBaseAtMillis = 10_000L)
        await { source.scans == 2 }
        delay(40)
        assertSame(first, client.commits.last())
        assertEquals(10_000L, source.state.value!!.timerBaseAtMillis)
    }

    @Test fun failedSaveDoesNotPublishOrAdvanceDeadline() = scenario { source, coordinator, client ->
        coordinator.attach(client)
        await { client.commits.isNotEmpty() }
        val first = client.commits.single()
        source.state.value = source.state.value!!.copy(timerEnabled = true, timerBaseAtMillis = 10_000L)
        await { source.scans == 2 }
        delay(40)
        val before = source.state.value
        val commits = client.commits.size
        source.failWrites = true
        coordinator.unlocked()
        await { source.status == DirectoryStatus.RETRY }
        assertEquals(before, source.state.value)
        assertEquals(commits, client.commits.size)
        assertSame(first, client.commits.last())
    }

    @Test fun previewDoesNotConsumeAnOverdueTimer() = scenario(preview = true) { source, coordinator, client ->
        source.state.value = source.state.value!!.copy(timerEnabled = true, timerBaseAtMillis = 0L, intervalMillis = 1_000L)
        coordinator.attach(client)
        await { client.commits.isNotEmpty() }
        assertEquals(0L, source.state.value!!.timerBaseAtMillis)
        assertEquals(0, source.saves)
    }

    @Test fun missingBaseIsInitializedWithoutRotatingAnExistingPhoto() = scenario { source, coordinator, client ->
        source.last = source.photos.last()
        source.state.value = source.state.value!!.copy(timerEnabled = true)
        coordinator.attach(client)
        await { client.commits.isNotEmpty() }
        assertEquals(source.photos.last(), client.commits.single().photo)
        assertEquals(10_000L, source.state.value!!.timerBaseAtMillis)
        assertEquals(1, source.saves)
    }

    @Test fun timerWakeupRechecksTheClockAndReschedulesAfterSuccess() = runBlocking {
        withContext(Dispatchers.Main) {
            val source = FakeSource()
            source.last = source.photos.first()
            source.state.value = source.state.value!!.copy(timerEnabled = true, timerBaseAtMillis = 0L, intervalMillis = 1_000L)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val waits = kotlinx.coroutines.channels.Channel<Long>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val wake = kotlinx.coroutines.channels.Channel<Unit>()
            var now = 500L
            val coordinator = WallpaperPhotoCoordinator(source, scope, nowMillis = { now }, waitForTimer = {
                waits.send(it)
                wake.receive()
            })
            val client = FakeClient()
            try {
                coordinator.attach(client)
                await { client.commits.isNotEmpty() }
                assertEquals(500L, withTimeout(3_000) { waits.receive() })
                val old = source.last
                now = 1_000L
                wake.send(Unit)
                await { source.state.value!!.timerBaseAtMillis == 1_000L }
                assertNotEquals(old, source.last)
                assertEquals(1_000L, withTimeout(3_000) { waits.receive() })
            } finally {
                coordinator.close()
                scope.cancel()
                client.close()
            }
        }
    }

    @Test fun hidingDuringDurableCommitDoesNotCauseAnotherRotation() = scenario { source, coordinator, client ->
        source.last = source.photos.last()
        source.state.value = source.state.value!!.copy(timerEnabled = true, timerBaseAtMillis = 0L, intervalMillis = 1_000L)
        source.writeDelay = 100
        coordinator.attach(client)
        await { source.writing }
        client.ready = false
        coordinator.visibilityChanged()
        await { source.state.value!!.timerBaseAtMillis == 10_000L }
        delay(20)
        val photo = source.last
        client.ready = true
        coordinator.visibilityChanged()
        delay(70)
        assertEquals(photo, source.last)
        assertEquals(1, source.saves)
    }

    @Test fun unlockBeforeFirstLoadIsAppliedAfterTheEnabledSettingArrives() = scenario { source, coordinator, client ->
        val old = source.photos.last()
        val loaded = source.state.value!!.copy(currentPhotoUri = old.toString())
        source.state.value = null
        coordinator.attach(client)
        coordinator.unlocked()
        delay(30)
        assertEquals(0, source.scans)
        source.state.value = loaded
        await { client.commits.isNotEmpty() }
        assertNotEquals(old, client.commits.single().photo)
    }

    @Test fun unlockBeforeFirstLoadIsDiscardedWhenTheSettingIsDisabled() = scenario { source, coordinator, client ->
        val old = source.photos.last()
        val loaded = source.state.value!!.copy(currentPhotoUri = old.toString(), unlockEnabled = false)
        source.state.value = null
        coordinator.attach(client)
        coordinator.unlocked()
        source.state.value = loaded
        await { client.commits.isNotEmpty() }
        assertEquals(old, client.commits.single().photo)
        assertEquals(0, source.saves)
    }

    @Test fun expiredRetryStartsARealAttemptInsteadOfAOneMillisecondLoop() = runBlocking {
        withContext(Dispatchers.Main) {
            val source = FakeSource()
            source.last = source.photos.first()
            source.photos = emptyList()
            source.partial = true // A partial scan cannot prove that the old photo was deleted.
            source.state.value = source.state.value!!.copy(timerEnabled = true, timerBaseAtMillis = 0L)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val waits = kotlinx.coroutines.channels.Channel<Long>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val wake = kotlinx.coroutines.channels.Channel<Unit>()
            var now = 0L
            val coordinator = WallpaperPhotoCoordinator(source, scope, nowMillis = { now }, waitForTimer = {
                waits.send(it)
                wake.receive()
            })
            val client = FakeClient()
            try {
                coordinator.attach(client)
                await { client.commits.isNotEmpty() }
                assertEquals(60_000L, withTimeout(3_000) { waits.receive() })
                now = 60_000L
                wake.send(Unit)
                assertEquals(60_000L, withTimeout(3_000) { waits.receive() })
                assertEquals(2, source.scans)
                assertEquals(0, source.saves)
            } finally {
                coordinator.close()
                scope.cancel()
                client.close()
            }
        }
    }

    @Test fun disabledTimerDoesNotAutomaticallyRetryAFailedUnlock() = runBlocking {
        withContext(Dispatchers.Main) {
            val source = FakeSource()
            source.last = source.photos.first()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            var scheduled = 0
            val coordinator = WallpaperPhotoCoordinator(source, scope, nowMillis = { 10_000L }, waitForTimer = {
                scheduled++
                awaitCancellation()
            })
            val client = FakeClient()
            try {
                coordinator.attach(client)
                await { client.commits.isNotEmpty() }
                source.failWrites = true
                coordinator.unlocked()
                await { source.status == DirectoryStatus.RETRY }
                coordinator.visibilityChanged()
                delay(30)
                assertEquals(0, scheduled)
                assertEquals(1, client.commits.size)
            } finally {
                coordinator.close()
                scope.cancel()
                client.close()
            }
        }
    }

    @Test fun styleOnlyChangeDoesNotScanOrChangeSceneOrBaseline() = scenario { source, coordinator, client ->
        source.last = source.photos.first()
        source.state.value = source.state.value!!.copy(unlockEnabled = false, timerEnabled = true, timerBaseAtMillis = 5_000)
        coordinator.attach(client)
        await { client.commits.isNotEmpty() }
        val old = client.commits.single()
        val scans = source.scans
        val state = source.state.value!!
        source.state.value = state.copy(backgroundStyle = BackgroundStyle.BLURRED_PHOTO)
        await { client.style == BackgroundStyle.BLURRED_PHOTO }
        delay(40)
        assertEquals(scans, source.scans)
        assertEquals(listOf(old), client.commits)
        assertEquals(state.copy(backgroundStyle = BackgroundStyle.BLURRED_PHOTO), source.state.value)
        assertEquals(0, source.saves)
    }

    @Test fun styleChangeDuringUnlockDoesNotSwallowPreparedRotation() = scenario { source, coordinator, client ->
        coordinator.attach(client)
        await { client.commits.isNotEmpty() }
        client.prepareHook = { delay(100) }
        val old = source.last
        coordinator.unlocked()
        delay(30)
        source.state.value = source.state.value!!.copy(backgroundStyle = BackgroundStyle.BLURRED_PHOTO)
        await { client.commits.size == 2 }
        assertNotEquals(old, source.last)
        assertEquals(BackgroundStyle.BLURRED_PHOTO, client.style)
        assertEquals(BackgroundStyle.BLURRED_PHOTO, source.state.value!!.backgroundStyle)
    }

    private fun scenario(
        preview: Boolean = false,
        timeoutMillis: Long = 2000,
        body: suspend (FakeSource, WallpaperPhotoCoordinator, FakeClient) -> Unit,
    ) = runBlocking {
        withContext(Dispatchers.Main) {
            val source = FakeSource()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val coordinator = WallpaperPhotoCoordinator(source, scope, preview, timeoutMillis, nowMillis = { 10_000L })
            val client = FakeClient()
            try { body(source, coordinator, client) }
            finally {
                coordinator.close()
                scope.cancel()
                client.close()
            }
        }
    }

    private suspend fun await(predicate: () -> Boolean) = withTimeout(3000) {
        while (!predicate()) delay(10)
    }

    private class FakeClient : WallpaperPhotoClient {
        override var ready = true
        var style = BackgroundStyle.RANDOM_RECTANGLES
        override fun backgroundStyleChanged(style: String) { this.style = style }
        var preparing = false
        var bitmap: Bitmap? = null
        var prepareHook: suspend (Uri) -> Unit = {}
        val commits = mutableListOf<WallpaperScene>()
        override suspend fun prepare(photo: Uri): Bitmap {
            preparing = true
            prepareHook(photo)
            return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        }
        override fun commit(scene: WallpaperScene, bitmap: Bitmap?) {
            this.bitmap?.recycle()
            this.bitmap = bitmap
            commits.add(scene)
        }
        override fun sceneChanged(scene: WallpaperScene) { }
        fun close() { bitmap?.recycle() }
    }

    private class FakeSource : WallpaperPhotoSource {
        override val directory = MutableStateFlow<PhotoDirectory?>(PhotoDirectory(Uri.parse("content://photos/old"), "old"))
        override val state = MutableStateFlow<WallpaperState?>(WallpaperState(directoryUri = directory.value!!.uri.toString()))
        var photos = listOf(Uri.parse("content://photos/old/a"), Uri.parse("content://photos/old/b"))
        var scans = 0
        var saves = 0
        var last: Uri?
            get() = state.value?.currentPhotoUri?.let(Uri::parse)
            set(value) { state.value = state.value!!.copy(currentPhotoUri = value?.toString()) }
        var status = DirectoryStatus.UNSELECTED
        var scanDelay = 0L
        var partial = false
        var failWrites = false
        var writeDelay = 0L
        var writing = false
        override suspend fun scan(tree: Uri): DirectoryPhotos {
            scans++
            delay(scanDelay)
            return DirectoryPhotos(photos, partial)
        }
        override suspend fun <T> withDirectory(tree: Uri, block: suspend () -> T): T = block()
        override fun report(tree: Uri?, status: DirectoryStatus, count: Int, partial: Boolean) { this.status = status }
        override suspend fun updateState(transform: (WallpaperState) -> WallpaperState): WallpaperState {
            if (failWrites) throw IOException("write failure")
            val previous = state.value!!
            val next = transform(previous)
            writing = true
            delay(writeDelay)
            if (next != previous) saves++
            state.value = next
            writing = false
            return next
        }
    }
}
