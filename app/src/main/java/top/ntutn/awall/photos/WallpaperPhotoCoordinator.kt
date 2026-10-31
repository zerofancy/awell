package top.ntutn.awall.photos

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.FileNotFoundException
import kotlin.time.Duration.Companion.milliseconds

/** Metadata is shared by official Engines; each Engine owns its size-specific Bitmap. */
data class WallpaperScene(
    val tree: Uri?,
    val photo: Uri?,
    val background: List<BackgroundRectangle> = BackgroundRenderer.create(),
)

interface WallpaperPhotoClient {
    val ready: Boolean
    fun backgroundStyleChanged(style: String) {}
    suspend fun prepare(photo: Uri): Bitmap
    suspend fun prepare(photo: Uri, deadlineMillis: Long): Bitmap = prepare(photo)
    fun commit(scene: WallpaperScene, bitmap: Bitmap?)
    fun sceneChanged(scene: WallpaperScene)
}

/** Main owns lifecycle and scene transitions; only the guarded state transform runs on DataStore. */
class WallpaperPhotoCoordinator(
    private val repository: WallpaperPhotoSource,
    private val scope: CoroutineScope,
    private val preview: Boolean = false,
    private val timeoutMillis: Long = PHOTO_TIMEOUT_MS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val retryDelayMillis: Long = 60_000L,
    private val waitForTimer: suspend (Long) -> Unit = { delay(it) },
) {
    private val clients = linkedSetOf<WallpaperPhotoClient>()
    private val requests = RequestVersion()
    private var task: Job? = null
    private var timer: Job? = null
    private var restore = true
    private var directoryPending = false
    private var unlockPending = false
    private var rotating = false
    private var committing = false
    private var blocked = false
    private var retryAtMillis: Long? = null
    private var closed = false
    private var scene = WallpaperScene(null, null)
    private var configuration: WallpaperState? = null
    private var directory: PhotoDirectory? = null
    private val configurationJob = scope.launch {
        combine(repository.state, repository.directory) { state, source -> state to source }
            .collect { (state, source) ->
                if (state == null || state.directoryUri != source?.uri?.toString()) return@collect
                val previous = configuration
                val changedDirectory = previous != null && directory != source
                val changedSettings = previous != null && !sameSettings(previous, state)
                if (previous?.backgroundStyle != state.backgroundStyle) {
                    clients.forEach { it.backgroundStyleChanged(state.backgroundStyle) }
                }
                configuration = state
                directory = source
                if (!state.unlockEnabled) unlockPending = false
                if (changedDirectory || changedSettings) {
                    invalidate()
                    retryAtMillis = null
                    blocked = false
                    restore = true
                    if (changedDirectory) {
                        directoryPending = true
                        scene = WallpaperScene(source?.uri, null)
                        clients.forEach { it.commit(scene, null) }
                    }
                }
                // Our own URI/base writes are state changes, not new rotation requests.
                reconcile()
            }
    }

    fun attach(client: WallpaperPhotoClient) {
        clients.add(client)
        client.backgroundStyleChanged(configuration?.backgroundStyle ?: BackgroundStyle.RANDOM_RECTANGLES)
        client.sceneChanged(scene)
        reconcile()
    }

    fun detach(client: WallpaperPhotoClient) {
        clients.remove(client)
        visibilityChanged()
    }

    fun visibilityChanged() {
        if (clients.none { it.ready }) {
            timer?.cancel()
            timer = null
            // A prepared scene already being saved may finish its short durable commit.
            if (task != null && !committing) {
                invalidate()
                restore = true
            }
        } else {
            // A lifecycle entry may revalidate previously inaccessible storage.
            blocked = false
        }
        reconcile()
    }

    fun unlocked() {
        if (preview || repository.state.value?.unlockEnabled == false) return
        if (task != null && rotating) return // The in-flight change also satisfies this unlock.
        unlockPending = true
        retryAtMillis = null
        blocked = false
        if (task != null) invalidate() // Upgrade an in-flight restore to a change.
        reconcile()
    }

    /** System time changes must recompute the remaining interval, rather than trust an old delay. */
    fun timeChanged() {
        timer?.cancel()
        timer = null
        retryAtMillis = null
        reconcile()
    }

    private fun invalidate() {
        requests.next()
        task?.cancel()
        task = null
        timer?.cancel()
        timer = null
    }

    private fun reconcile() {
        if (closed || task != null || clients.none { it.ready }) return
        val state = repository.state.value ?: return
        val source = repository.directory.value
        if (state.directoryUri != source?.uri?.toString()) return
        if (blocked) return
        val now = nowMillis()
        val retryRemaining = retryAtMillis?.minus(now) ?: 0L
        if (retryRemaining > 0) {
            if (!preview && state.timerEnabled) schedule(retryRemaining)
            return
        }
        if (retryAtMillis != null) {
            retryAtMillis = null
            restore = true
        }
        val needsBase = !preview && state.timerEnabled &&
            (state.timerBaseAtMillis == null || now < state.timerBaseAtMillis)
        val due = !preview && timerRemainingMillis(state, now) == 0L
        val rotate = !preview && (directoryPending || (unlockPending && state.unlockEnabled) || due)
        if (!restore && !rotate && !needsBase) {
            scheduleNext(state)
            return
        }
        timer?.cancel()
        timer = null
        val token = requests.next()
        rotating = rotate
        task = scope.launch {
            try {
                var snapshot = state
                if (needsBase) {
                    snapshot = withContext(NonCancellable) {
                        repository.updateState { latest ->
                            if (requests.isCurrent(token) && latest == state) latest.copy(timerBaseAtMillis = now)
                            else latest
                        }
                    }
                }
                if (requests.isCurrent(token)) prepareScene(token, snapshot, source, rotate)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (requests.isCurrent(token)) {
                    repository.report(source?.uri, DirectoryStatus.RETRY)
                    retryAtMillis = nowMillis() + retryDelayMillis
                }
            } finally {
                if (requests.isCurrent(token)) {
                    task = null
                    committing = false
                    rotating = false
                    scheduleNext(repository.state.value ?: state)
                }
            }
        }
    }

    private suspend fun prepareScene(token: Long, snapshot: WallpaperState, source: PhotoDirectory?, rotate: Boolean) {
        val primary = clients.firstOrNull { it.ready } ?: return
        val tree = source?.uri
        val current = snapshot.currentPhotoUri?.let(Uri::parse)
        val prepared = linkedMapOf<WallpaperPhotoClient, Bitmap>()
        var currentKnownMissing = false
        try {
            var next: WallpaperScene? = null
            var changed = rotate
            val deadlineMillis = SystemClock.elapsedRealtime() + timeoutMillis
            withTimeout(timeoutMillis.milliseconds) {
                if (tree == null) {
                    next = WallpaperScene(null, null)
                    return@withTimeout
                }
                repository.withDirectory(tree) {
                    repository.report(tree, DirectoryStatus.SCANNING)
                    val files = repository.scan(tree)
                    currentKnownMissing = current != null && current !in files.uris && !files.partial
                    val preferred = if (!rotate) current?.takeIf { it in files.uris } else null
                    val candidates = if (preferred != null) {
                        listOf(preferred) + photoCandidates(files.uris.filterNot { it == preferred }, null)
                    } else photoCandidates(files.uris, current)
                    var selected: Uri? = null
                    for (photo in candidates) {
                        try {
                            prepared[primary] = primary.prepare(photo, deadlineMillis)
                            selected = photo
                            break
                        } catch (error: CancellationException) { throw error }
                        catch (_: Exception) { /* Try the remaining candidates, not just the first broken photo. */ }
                    }
                    repository.report(tree, when {
                        files.uris.isEmpty() -> DirectoryStatus.EMPTY
                        selected == null -> DirectoryStatus.UNDECODABLE
                        else -> DirectoryStatus.READY
                    }, files.uris.size, files.partial)
                    if (selected == null && current != null && !currentKnownMissing) {
                        try {
                            val old = primary.prepare(current, deadlineMillis)
                            if (scene.photo == null) {
                                prepared[primary] = old
                                next = WallpaperScene(tree, current)
                                changed = false
                            } else old.recycle()
                            retryAtMillis = nowMillis() + retryDelayMillis
                            return@withDirectory
                        } catch (error: CancellationException) { throw error }
                        catch (_: Exception) { currentKnownMissing = true }
                    }
                    if (selected == null && files.uris.isNotEmpty()) {
                        // Corrupt candidates are a failure, not a successful empty-directory rotation.
                        retryAtMillis = nowMillis() + retryDelayMillis
                        if (currentKnownMissing) next = WallpaperScene(tree, null)
                        changed = false
                        return@withDirectory
                    }
                    next = WallpaperScene(tree, selected)
                    changed = rotate || selected != current
                }
                val selected = next?.photo
                if (selected != null) {
                    clients.filter { it !== primary && it.ready }.forEach { client ->
                        try { prepared[client] = client.prepare(selected, deadlineMillis) }
                        catch (error: CancellationException) { throw error }
                        catch (_: Exception) { /* The secondary Engine reloads the shared choice later. */ }
                    }
                }
            }
            val result = next ?: return
            val candidate = if (!changed && scene.tree == result.tree && scene.photo == result.photo) scene else result
            publish(token, snapshot, source, candidate, prepared, changed)
        } catch (_: TimeoutCancellationException) {
            if (requests.isCurrent(token)) {
                repository.report(tree, DirectoryStatus.RETRY)
                retryAtMillis = nowMillis() + retryDelayMillis
                if (currentKnownMissing) publish(token, snapshot, source, WallpaperScene(tree, null), linkedMapOf(), false)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: SecurityException) {
            inaccessible(token, snapshot, source)
        } catch (_: FileNotFoundException) {
            inaccessible(token, snapshot, source)
        } catch (_: Exception) {
            if (requests.isCurrent(token)) {
                repository.report(tree, DirectoryStatus.RETRY)
                retryAtMillis = nowMillis() + retryDelayMillis
            }
        } finally {
            prepared.values.forEach(Bitmap::recycle)
            Log.d("WallpaperPhotos", "request=$token completed current=${requests.isCurrent(token)}")
        }
    }

    private suspend fun inaccessible(token: Long, snapshot: WallpaperState, source: PhotoDirectory?) {
        if (!requests.isCurrent(token)) return
        blocked = true
        repository.report(source?.uri, DirectoryStatus.INACCESSIBLE)
        publish(token, snapshot, source, WallpaperScene(source?.uri, null), linkedMapOf(), false)
    }

    private suspend fun publish(
        token: Long,
        snapshot: WallpaperState,
        source: PhotoDirectory?,
        next: WallpaperScene,
        prepared: MutableMap<WallpaperPhotoClient, Bitmap>,
        changed: Boolean,
    ) {
        if (!requests.isCurrent(token) || repository.directory.value != source || closed) return
        if (!preview && (changed || next.photo?.toString() != snapshot.currentPhotoUri)) {
            committing = true
            var accepted = false
            withContext(NonCancellable) {
                repository.updateState { latest ->
                    if (requests.isCurrent(token) && latest.copy(backgroundStyle = snapshot.backgroundStyle) == snapshot && repository.directory.value == source) {
                        accepted = true
                        latest.copy(
                            currentPhotoUri = next.photo?.toString(),
                            timerBaseAtMillis = if (changed && latest.timerEnabled) nowMillis() else latest.timerBaseAtMillis,
                        )
                    } else latest
                }
            }
            if (requests.isCurrent(token)) committing = false
            if (!accepted) return
            // The commit is durable even if lifecycle/configuration changed while it was writing.
            unlockPending = false
            if (repository.directory.value == source) directoryPending = false
        }
        if (!requests.isCurrent(token) || repository.directory.value != source || closed) return
        scene = next
        restore = false
        if (changed) {
            unlockPending = false
            directoryPending = false
            retryAtMillis = null
        }
        clients.forEach { client ->
            val bitmap = prepared.remove(client)
            if (next.photo == null || bitmap != null) client.commit(next, bitmap)
            else client.sceneChanged(next)
        }
    }

    private fun scheduleNext(state: WallpaperState) {
        if (closed || preview || blocked || clients.none { it.ready } || task != null) return
        val retry = retryAtMillis
        if (retry != null) {
            if (state.timerEnabled) schedule((retry - nowMillis()).coerceAtLeast(1L))
            return
        }
        timerRemainingMillis(state, nowMillis())?.let(::schedule)
    }

    private fun schedule(millis: Long) {
        if (timer?.isActive == true) return
        timer = scope.launch {
            waitForTimer(millis.coerceAtLeast(1L))
            timer = null
            reconcile()
        }
    }

    fun close() {
        closed = true
        invalidate()
        configurationJob.cancel()
        clients.clear()
    }
}

/** null disables scheduling; this calculation is also used after process recreation. */
internal fun timerRemainingMillis(state: WallpaperState, nowMillis: Long): Long? {
    if (!state.timerEnabled) return null
    val base = state.timerBaseAtMillis ?: return state.intervalMillis
    val elapsed = if (nowMillis < base) 0L else nowMillis - base
    return (state.intervalMillis - elapsed).coerceAtLeast(0L)
}

private fun sameSettings(first: WallpaperState, second: WallpaperState): Boolean =
    first.unlockEnabled == second.unlockEnabled && first.timerEnabled == second.timerEnabled &&
        first.intervalMillis == second.intervalMillis
