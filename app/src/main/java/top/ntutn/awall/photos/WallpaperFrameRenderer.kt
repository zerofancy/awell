package top.ntutn.awall.photos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

/** Owner-thread state. A pending foreground is owned here until committed or cancelled. */
class WallpaperFrameRenderer(
    private val scope: CoroutineScope,
    private val ready: () -> Boolean,
    private val redraw: () -> Unit,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var scene: WallpaperScene? = null
    private var image: Bitmap? = null
    private var background: Bitmap? = null
    private var style = BackgroundStyle.RANDOM_RECTANGLES
    private var drawnStyle = style
    private var frameWidth = 0
    private var frameHeight = 0
    private var job: Job? = null
    private var pendingScene: WallpaperScene? = null
    private var pendingWidth = 0
    private var pendingHeight = 0
    private var version = 0L

    fun matches(value: WallpaperScene, width: Int, height: Int): Boolean =
        (scene === value && (value.photo == null || image != null) && frameWidth == width && frameHeight == height) ||
            (job?.isActive == true && pendingScene === value && pendingWidth == width && pendingHeight == height)

    fun setStyle(value: String) {
        style = BackgroundStyle.normalize(value)
        refreshStyle()
    }

    fun refreshStyle() {
        if (ready() && job?.isActive != true && drawnStyle != style) {
            scene?.let { submit(it, image, frameWidth, frameHeight) }
        }
    }

    fun submit(value: WallpaperScene, candidate: Bitmap?, width: Int, height: Int,
        deadlineMillis: Long = SystemClock.elapsedRealtime() + PHOTO_TIMEOUT_MS) {
        cancel()
        val token = version
        val startedAt = SystemClock.elapsedRealtime()
        pendingScene = value
        pendingWidth = width
        pendingHeight = height
        // Start immediately so cancellation always reaches the ownership cleanup in finally.
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var prepared: Bitmap? = null
            try {
                var chosen: String
                do {
                    prepared?.recycle()
                    prepared = null
                    chosen = style
                    if (chosen == BackgroundStyle.BLURRED_PHOTO && candidate != null && ready()) {
                        var snapshot: Bitmap? = null
                        try {
                            snapshot = BlurredBackground.snapshot(candidate, width, height)
                            val small = snapshot
                            val completed = withTimeoutOrNull((deadlineMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0)) {
                                withContext(Dispatchers.Default) { BlurredBackground.blur(small) }
                                true
                            } == true
                            if (completed) { prepared = snapshot; snapshot = null }
                        } catch (error: CancellationException) { throw error }
                        catch (_: Exception) { Log.w("WallpaperBackground", "Using black background after preparation failure") }
                        catch (_: OutOfMemoryError) { Log.w("WallpaperBackground", "Using black background within pixel budget") }
                        finally { snapshot?.recycle() }
                    }
                } while (chosen != style && ready())
                if (token != version || (!ready() && candidate != null)) return@launch
                val oldImage = image
                val oldBackground = background
                scene = value
                image = candidate
                background = prepared
                prepared = null
                drawnStyle = chosen
                frameWidth = width
                frameHeight = height
                Log.d("WallpaperBackground", "style=$chosen surface=${width}x$height " +
                    "foregroundBytes=${candidate?.allocationByteCount ?: 0} backgroundBytes=${background?.allocationByteCount ?: 0} " +
                    "prepareMs=${SystemClock.elapsedRealtime() - startedAt}")
                redraw()
                if (oldImage !== candidate) oldImage?.recycle()
                oldBackground?.recycle()
            } finally {
                prepared?.recycle()
                if (candidate !== image) candidate?.recycle()
                if (token == version) pendingScene = null
            }
        }
    }

    fun draw(canvas: Canvas) {
        val value = scene ?: run { canvas.drawColor(Color.BLACK); return }
        paint.color = Color.WHITE
        paint.alpha = 255
        if (drawnStyle == BackgroundStyle.RANDOM_RECTANGLES) BackgroundRenderer.draw(canvas, value.background, paint)
        else {
            canvas.drawColor(Color.BLACK)
            background?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat()), paint) }
        }
        image?.let {
            val scale = min(canvas.width.toFloat() / it.width, canvas.height.toFloat() / it.height)
            val left = (canvas.width - it.width * scale) / 2
            val top = (canvas.height - it.height * scale) / 2
            paint.color = Color.WHITE
            canvas.drawBitmap(it, null, RectF(left, top, left + it.width * scale, top + it.height * scale), paint)
        }
    }

    fun cancel() {
        version++
        job?.cancel()
        job = null
        pendingScene = null
    }

    fun close() {
        cancel()
        image?.recycle()
        background?.recycle()
        image = null
        background = null
        scene = null
    }
}
