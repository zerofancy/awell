package top.ntutn.awall.photos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val NIGHT_DIM_OVERLAY = 0x4D000000 // 30% black

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
    private var colorBackground: PhotoColorBackground? = null
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
        deadline: PreparationDeadline = PreparationDeadline.after(PHOTO_TIMEOUT)) {
        cancel()
        val token = version
        val startedAt = SystemClock.elapsedRealtime()
        pendingScene = value
        pendingWidth = width
        pendingHeight = height
        // Start immediately so cancellation always reaches the ownership cleanup in finally.
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var prepared: Bitmap? = null
            var preparedColor: PhotoColorBackground? = null
            try {
                var chosen: String
                do {
                    prepared?.recycle()
                    prepared = null
                    preparedColor?.close()
                    preparedColor = null
                    chosen = style
                    if (chosen != BackgroundStyle.RANDOM_RECTANGLES && candidate != null && ready()) {
                        var snapshot: Bitmap? = null
                        try {
                            val photoColor = BackgroundStyle.isPhotoColor(chosen)
                            snapshot = if (photoColor) PhotoColorBackground.snapshot(candidate)
                                else BlurredBackground.snapshot(candidate, width, height)
                            val small = snapshot
                            val completed = withTimeoutOrNull(deadline.remaining()) {
                                withContext(Dispatchers.Default) {
                                    if (photoColor) {
                                        // Assign inside the worker so cancellation during return still reaches cleanup.
                                        preparedColor = PhotoColorBackground.prepare(small, width, height, chosen)
                                    } else BlurredBackground.blur(small)
                                }
                                true
                            } == true
                            if (completed && !photoColor) { prepared = snapshot; snapshot = null }
                            if (!completed) { preparedColor?.close(); preparedColor = null }
                        } catch (error: CancellationException) { throw error }
                        catch (_: Exception) {
                            preparedColor?.close(); preparedColor = null
                            Log.w("WallpaperBackground", "Using black background after preparation failure")
                        } catch (_: OutOfMemoryError) {
                            preparedColor?.close(); preparedColor = null
                            Log.w("WallpaperBackground", "Using black background within pixel budget")
                        } finally { snapshot?.recycle() }
                    }
                } while (chosen != style && ready())
                if (token != version || (!ready() && candidate != null)) return@launch
                val oldImage = image
                val oldBackground = background
                val oldColorBackground = colorBackground
                scene = value
                image = candidate
                background = prepared
                prepared = null
                colorBackground = preparedColor
                preparedColor = null
                drawnStyle = chosen
                frameWidth = width
                frameHeight = height
                Log.d("WallpaperBackground", "style=$chosen surface=${width}x$height " +
                    "foregroundBytes=${candidate?.allocationByteCount ?: 0} backgroundBytes=${background?.allocationByteCount ?: 0} " +
                    "prepareMs=${SystemClock.elapsedRealtime() - startedAt}")
                redraw()
                if (oldImage !== candidate) oldImage?.recycle()
                oldBackground?.recycle()
                oldColorBackground?.close()
            } finally {
                prepared?.recycle()
                preparedColor?.close()
                if (candidate !== image) candidate?.recycle()
                if (token == version) pendingScene = null
            }
        }
    }

    fun draw(canvas: Canvas, nightDimmed: Boolean = false) {
        val value = scene ?: run { canvas.drawColor(Color.BLACK); return }
        paint.color = Color.WHITE
        paint.alpha = 255
        if (drawnStyle == BackgroundStyle.RANDOM_RECTANGLES) BackgroundRenderer.draw(canvas, value.background, paint)
        else {
            canvas.drawColor(Color.BLACK)
            colorBackground?.draw(canvas)
                ?: background?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat()), paint) }
        }
        image?.let {
            val scale = min(canvas.width.toFloat() / it.width, canvas.height.toFloat() / it.height)
            val left = (canvas.width - it.width * scale) / 2
            val top = (canvas.height - it.height * scale) / 2
            paint.color = Color.WHITE
            canvas.drawBitmap(it, null, RectF(left, top, left + it.width * scale, top + it.height * scale), paint)
        }
        if (nightDimmed) canvas.drawColor(NIGHT_DIM_OVERLAY)
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
        colorBackground?.close()
        image = null
        background = null
        colorBackground = null
        scene = null
    }
}
