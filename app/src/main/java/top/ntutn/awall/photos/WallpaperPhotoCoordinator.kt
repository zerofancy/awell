package top.ntutn.awall.photos

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.FileNotFoundException

/** Metadata is shared by official Engines; each Engine owns its size-specific Bitmap. */
data class WallpaperScene(
    val tree: Uri?,
    val photo: Uri?,
    val background: List<BackgroundRectangle> = BackgroundRenderer.create(),
)

interface WallpaperPhotoClient {
    val ready: Boolean
    suspend fun prepare(photo: Uri): Bitmap
    fun commit(scene: WallpaperScene, bitmap: Bitmap?)
    fun sceneChanged(scene: WallpaperScene)
}

/** All state transitions run on Main, alongside Engine lifecycle and Canvas submission. */
class WallpaperPhotoCoordinator(
    private val repository: WallpaperPhotoSource,
    private val scope: CoroutineScope,
    private val preview: Boolean = false,
    private val timeoutMillis: Long = PHOTO_TIMEOUT_MS,
) {
    private val clients = linkedSetOf<WallpaperPhotoClient>()
    private val requests = RequestVersion()
    private var task: Job? = null
    private var pending = true
    private var restore = !preview
    private var scene = WallpaperScene(repository.directory.value?.uri, null)
    private var configuration = repository.directory.value
    private val configurationJob = scope.launch {
        repository.directory.collect { directory ->
            if (configuration != directory) {
                configuration = directory
                invalidate()
                restore = false
                scene = WallpaperScene(directory?.uri, null)
                clients.forEach { it.commit(scene, null) }
                pending = true
                runPending()
            }
        }
    }

    fun attach(client: WallpaperPhotoClient) {
        clients.add(client)
        client.sceneChanged(scene)
    }

    fun detach(client: WallpaperPhotoClient) {
        clients.remove(client)
        visibilityChanged()
    }

    fun visibilityChanged() {
        if (clients.none { it.ready } && task != null) {
            invalidate()
            pending = true
        }
        runPending()
    }

    fun unlocked() {
        if (preview) return
        invalidate()
        pending = true
        restore = false
        runPending()
    }

    private fun invalidate() {
        requests.next()
        task?.cancel()
        task = null
    }

    private fun runPending() {
        if (!pending || task != null) return
        val primary = clients.firstOrNull { it.ready } ?: return
        pending = false
        val token = requests.next()
        val tree = repository.directory.value?.uri
        val restorePhoto = if (restore) repository.lastPhoto() else null
        restore = false
        task = scope.launch {
            val prepared = linkedMapOf<WallpaperPhotoClient, Bitmap>()
            var currentKnownMissing = false
            try {
                withTimeout(timeoutMillis) {
                    if (tree == null) {
                        publish(token, WallpaperScene(null, null), prepared)
                        return@withTimeout
                    }
                    repository.withDirectory(tree) {
                        repository.report(tree, DirectoryStatus.SCANNING)
                        val files = repository.scan(tree)
                        val current = scene.photo
                        currentKnownMissing = current != null && current !in files.uris && !files.partial
                        val preferred = restorePhoto?.takeIf { it in files.uris }
                        val candidates = if (preferred != null) listOf(preferred) + photoCandidates(files.uris.filterNot { it == preferred }, null)
                            else photoCandidates(files.uris, current)
                        var selected: Uri? = null
                        for (photo in candidates) {
                            try {
                                prepared[primary] = primary.prepare(photo)
                                selected = photo
                                break
                            } catch (error: CancellationException) { throw error }
                            catch (_: Exception) { /* A broken picture must not reject the directory. */ }
                        }
                        repository.report(tree, when {
                            files.uris.isEmpty() -> DirectoryStatus.EMPTY
                            selected == null -> DirectoryStatus.UNDECODABLE
                            else -> DirectoryStatus.READY
                        }, files.uris.size, files.partial)
                        if (selected == null && current != null && !currentKnownMissing) {
                            // Validate the old picture too: it may have become corrupt or unreadable.
                            try {
                                primary.prepare(current).recycle()
                                return@withDirectory
                            } catch (error: CancellationException) { throw error }
                            catch (_: Exception) { currentKnownMissing = true }
                        }
                        val next = WallpaperScene(tree, selected)
                        if (selected != null) {
                            clients.filter { it !== primary && it.ready }.forEach { client ->
                                try { prepared[client] = client.prepare(selected) }
                                catch (error: CancellationException) { throw error }
                                catch (_: Exception) { /* This Engine retries the shared selection on demand. */ }
                            }
                        }
                        publish(token, next, prepared)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                repository.report(tree, DirectoryStatus.RETRY)
                if (currentKnownMissing) publish(token, WallpaperScene(tree, null), linkedMapOf())
            } catch (error: CancellationException) {
                throw error
            } catch (_: SecurityException) {
                repository.report(tree, DirectoryStatus.INACCESSIBLE)
                publish(token, WallpaperScene(tree, null), linkedMapOf())
            } catch (_: FileNotFoundException) {
                repository.report(tree, DirectoryStatus.INACCESSIBLE)
                publish(token, WallpaperScene(tree, null), linkedMapOf())
            } catch (_: Exception) {
                repository.report(tree, DirectoryStatus.RETRY)
            } finally {
                prepared.values.forEach(Bitmap::recycle)
                if (requests.isCurrent(token)) task = null
                Log.d("WallpaperPhotos", "request=$token completed current=${requests.isCurrent(token)}")
            }
        }
    }

    private fun publish(token: Long, next: WallpaperScene, prepared: MutableMap<WallpaperPhotoClient, Bitmap>) {
        if (!requests.isCurrent(token) || repository.directory.value != configuration ||
            repository.directory.value?.uri != next.tree) return
        scene = next
        clients.forEach { client ->
            val bitmap = prepared.remove(client)
            if (next.photo == null || bitmap != null) client.commit(next, bitmap)
            else client.sceneChanged(next)
        }
        if (!preview) repository.saveLastPhoto(next.tree, next.photo)
    }

    fun close() {
        invalidate()
        configurationJob.cancel()
        clients.clear()
    }
}
