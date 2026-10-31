package top.ntutn.awall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import java.util.WeakHashMap
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import top.ntutn.awall.photos.BACKGROUND_RESERVE_BYTES
import top.ntutn.awall.photos.PHOTO_TIMEOUT
import top.ntutn.awall.photos.PhotoDirectoryRepository
import top.ntutn.awall.photos.PhotoLoader
import top.ntutn.awall.photos.PreparationDeadline
import top.ntutn.awall.photos.RequestVersion
import top.ntutn.awall.photos.WallpaperFrameRenderer
import top.ntutn.awall.photos.WallpaperPhotoClient
import top.ntutn.awall.photos.WallpaperPhotoCoordinator
import top.ntutn.awall.photos.WallpaperScene

class AWallpaperService : WallpaperService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: PhotoDirectoryRepository
    private lateinit var coordinator: WallpaperPhotoCoordinator
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_USER_PRESENT -> coordinator.unlocked()
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> coordinator.timeChanged()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = PhotoDirectoryRepository.get(this)
        coordinator = WallpaperPhotoCoordinator(repository, serviceScope)
        val events = IntentFilter(Intent.ACTION_USER_PRESENT).apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(this, unlockReceiver, events,
            ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onCreateEngine(): Engine = AWallEngine()

    override fun onDestroy() {
        unregisterReceiver(unlockReceiver)
        coordinator.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    inner class AWallEngine : Engine(), WallpaperPhotoClient {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val loader = PhotoLoader(contentResolver)
        private val frames = WallpaperFrameRenderer(scope, { ready }, ::drawFrame)
        private val preparationDeadlines = WeakHashMap<Bitmap, PreparationDeadline>()
        private val loads = RequestVersion()
        private var load: Job? = null
        private var owner: WallpaperPhotoCoordinator? = null
        private var desired: WallpaperScene? = null
        private var surfaceReady = false
        private var visible = false
        private var destroyed = false
        private var width = 0
        private var height = 0
        override val ready: Boolean get() = visible && surfaceReady && !destroyed && width > 0 && height > 0

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            owner = if (isPreview) WallpaperPhotoCoordinator(repository, scope, preview = true) else coordinator
            owner?.attach(this)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceReady = true
            owner?.visibilityChanged()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            val changed = this.width != width || this.height != height
            this.width = width
            this.height = height
            surfaceReady = true
            if (changed) {
                cancelLoad()
                frames.cancel()
                reloadCurrent()
            }
            drawFrame()
            owner?.visibilityChanged()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            this.visible = visible
            if (!visible) { cancelLoad(); frames.cancel() } else {
                frames.refreshStyle()
                reloadCurrent()
                drawFrame()
            }
            owner?.visibilityChanged()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            cancelLoad()
            frames.cancel()
            owner?.visibilityChanged()
            super.onSurfaceDestroyed(holder)
        }

        override suspend fun prepare(photo: android.net.Uri): Bitmap =
            prepare(photo, PreparationDeadline.after(PHOTO_TIMEOUT))

        override suspend fun prepare(photo: android.net.Uri, deadline: PreparationDeadline): Bitmap {
            // Even with multiple Engines, total live pixel buffers stay below the process allowance.
            val budget = min(32L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8) / MAX_ENGINE_ALLOWANCE
            return loader.load(photo, width.coerceAtLeast(1), height.coerceAtLeast(1),
                (budget - BACKGROUND_RESERVE_BYTES).coerceAtLeast(1), retainedBuffers = 4).also { preparationDeadlines[it] = deadline }
        }

        override fun sceneChanged(scene: WallpaperScene) {
            if (desired !== scene) { cancelLoad(); frames.cancel() }
            desired = scene
            if (scene.photo == null) commit(scene, null) else reloadCurrent()
        }

        override fun backgroundStyleChanged(style: String) = frames.setStyle(style)

        override fun commit(scene: WallpaperScene, bitmap: Bitmap?) {
            if (destroyed) { bitmap?.recycle(); return }
            cancelLoad()
            desired = scene
            frames.submit(scene, bitmap, width, height, deadline(bitmap))
        }

        private fun reloadCurrent() {
            val scene = desired ?: return
            val photo = scene.photo ?: return
            if (!ready || load?.isActive == true) return
            if (frames.matches(scene, width, height)) return
            val token = loads.next()
            val loadWidth = width
            val loadHeight = height
            load = scope.launch {
                var candidate: Bitmap? = null
                try {
                    candidate = withTimeout(PHOTO_TIMEOUT) {
                        val tree = scene.tree ?: return@withTimeout null
                        repository.withDirectory(tree) { prepare(photo) }
                    }
                    if (candidate != null && loads.isCurrent(token) && desired === scene && ready) {
                        val prepared = candidate
                        candidate = null
                        frames.submit(scene, prepared, loadWidth, loadHeight, deadline(prepared))
                    }
                } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                    // Keep the old frame. A later visibility event can retry this selection.
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { /* Coordinator handles directory failures on the next request. */ }
                finally {
                    candidate?.recycle()
                    if (loads.isCurrent(token)) load = null
                }
            }
        }

        private fun deadline(image: Bitmap?): PreparationDeadline = preparationDeadlines.remove(image)
            ?: PreparationDeadline.after(PHOTO_TIMEOUT)

        private fun cancelLoad() {
            loads.next()
            load?.cancel()
            load = null
        }

        private fun drawFrame() {
            if (!ready || !surfaceHolder.surface.isValid) return
            val canvas = try { surfaceHolder.lockCanvas() } catch (_: RuntimeException) { null } ?: return
            try {
                frames.draw(canvas)
            } finally {
                try { surfaceHolder.unlockCanvasAndPost(canvas) } catch (_: RuntimeException) { /* Surface was removed. */ }
            }
        }

        override fun onDestroy() {
            destroyed = true
            owner?.detach(this)
            if (isPreview) owner?.close()
            scope.cancel()
            frames.close()
            super.onDestroy()
        }
    }

    companion object {
        private const val MAX_ENGINE_ALLOWANCE = 4
    }
}
