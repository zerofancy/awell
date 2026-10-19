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

    private fun scenario(
        preview: Boolean = false,
        timeoutMillis: Long = 2000,
        body: suspend (FakeSource, WallpaperPhotoCoordinator, FakeClient) -> Unit,
    ) = runBlocking {
        withContext(Dispatchers.Main) {
            val source = FakeSource()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val coordinator = WallpaperPhotoCoordinator(source, scope, preview, timeoutMillis)
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
        var photos = listOf(Uri.parse("content://photos/old/a"), Uri.parse("content://photos/old/b"))
        var scans = 0
        var saves = 0
        var last: Uri? = null
        var status = DirectoryStatus.UNSELECTED
        var scanDelay = 0L
        override suspend fun scan(tree: Uri): DirectoryPhotos {
            scans++
            delay(scanDelay)
            return DirectoryPhotos(photos, false)
        }
        override suspend fun <T> withDirectory(tree: Uri, block: suspend () -> T): T = block()
        override fun report(tree: Uri?, status: DirectoryStatus, count: Int, partial: Boolean) { this.status = status }
        override fun lastPhoto(): Uri? = last
        override fun saveLastPhoto(tree: Uri?, photo: Uri?) { last = photo; saves++ }
    }
}
