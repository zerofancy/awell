package top.ntutn.awall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import top.ntutn.awall.photos.BackgroundRenderer
import top.ntutn.awall.photos.PHOTO_TIMEOUT_MS
import top.ntutn.awall.photos.PhotoDirectoryRepository
import top.ntutn.awall.photos.PhotoLoader
import top.ntutn.awall.photos.RequestVersion
import top.ntutn.awall.photos.WallpaperPhotoClient
import top.ntutn.awall.photos.WallpaperPhotoCoordinator
import top.ntutn.awall.photos.WallpaperScene
import kotlin.math.min

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
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val loads = RequestVersion()
        private var load: Job? = null
        private var owner: WallpaperPhotoCoordinator? = null
        private var desired: WallpaperScene? = null
        private var displayed: WallpaperScene? = null
        private var bitmap: Bitmap? = null
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
                reloadCurrent()
            }
            drawFrame()
            owner?.visibilityChanged()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            this.visible = visible
            if (!visible) cancelLoad() else {
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
            owner?.visibilityChanged()
            super.onSurfaceDestroyed(holder)
        }

        override suspend fun prepare(photo: android.net.Uri): Bitmap {
            // Even with multiple Engines, total live pixel buffers stay below the process allowance.
            val budget = min(32L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8) / MAX_ENGINE_ALLOWANCE
            return loader.load(photo, width.coerceAtLeast(1), height.coerceAtLeast(1), budget)
        }

        override fun sceneChanged(scene: WallpaperScene) {
            desired = scene
            if (scene.photo == null) commit(scene, null) else reloadCurrent()
        }

        override fun commit(scene: WallpaperScene, bitmap: Bitmap?) {
            if (destroyed) { bitmap?.recycle(); return }
            cancelLoad()
            desired = scene
            displayed = scene
            decodedWidth = width
            decodedHeight = height
            val old = this.bitmap
            this.bitmap = bitmap
            drawFrame()
            if (old !== bitmap) old?.recycle()
        }

        private var decodedWidth = 0
        private var decodedHeight = 0

        private fun reloadCurrent() {
            val scene = desired ?: return
            val photo = scene.photo ?: return
            if (!ready || load?.isActive == true) return
            if (scene === displayed && bitmap != null && decodedWidth == width && decodedHeight == height) return
            val token = loads.next()
            val loadWidth = width
            val loadHeight = height
            load = scope.launch {
                var candidate: Bitmap? = null
                try {
                    candidate = withTimeout(PHOTO_TIMEOUT_MS) {
                        val tree = scene.tree ?: return@withTimeout null
                        repository.withDirectory(tree) { prepare(photo) }
                    }
                    if (candidate != null && loads.isCurrent(token) && desired === scene && ready) {
                        val old = bitmap
                        bitmap = candidate
                        candidate = null
                        displayed = scene
                        decodedWidth = loadWidth
                        decodedHeight = loadHeight
                        drawFrame()
                        old?.recycle()
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

        private fun cancelLoad() {
            loads.next()
            load?.cancel()
            load = null
        }

        private fun drawFrame() {
            if (!ready || !surfaceHolder.surface.isValid) return
            val scene = displayed ?: return
            val canvas = try { surfaceHolder.lockCanvas() } catch (_: RuntimeException) { null } ?: return
            try {
                BackgroundRenderer.draw(canvas, scene.background, paint)
                bitmap?.let { image ->
                    val scale = min(canvas.width.toFloat() / image.width, canvas.height.toFloat() / image.height)
                    val left = (canvas.width - image.width * scale) / 2
                    val top = (canvas.height - image.height * scale) / 2
                    paint.color = android.graphics.Color.WHITE
                    canvas.drawBitmap(image, null, RectF(left, top, left + image.width * scale,
                        top + image.height * scale), paint)
                }
            } finally {
                try { surfaceHolder.unlockCanvasAndPost(canvas) } catch (_: RuntimeException) { /* Surface was removed. */ }
            }
        }

        override fun onDestroy() {
            destroyed = true
            owner?.detach(this)
            if (isPreview) owner?.close()
            scope.cancel()
            bitmap?.recycle()
            bitmap = null
            super.onDestroy()
        }
    }

    companion object {
        private const val MAX_ENGINE_ALLOWANCE = 4
    }
}
