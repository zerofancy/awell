package top.ntutn.awall.photos

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.ensureActive

/** Cached paints/geometry, not a full-screen bitmap. Never retains or reads the foreground. */
internal class PhotoColorBackground private constructor(
    private val width: Int,
    private val height: Int,
    private val base: Paint,
    private val triangles: List<Pair<Path, Paint>> = emptyList(),
    private val texture: Bitmap? = null,
) {
    private val texturePaint = texture?.let {
        Paint().apply { shader = BitmapShader(it, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    }

    fun draw(canvas: Canvas) {
        val save = canvas.save()
        try {
            canvas.scale(canvas.width.toFloat() / width, canvas.height.toFloat() / height)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), base)
            triangles.forEach { (path, paint) -> canvas.drawPath(path, paint) }
        } finally { canvas.restoreToCount(save) }
        // Native-pixel repeating grain stays fine even on tall/high-density wallpaper surfaces.
        texturePaint?.let { canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), it) }
    }

    fun close() { texture?.recycle() }

    companion object {
        /** Owner thread makes a bounded copy of the ENTIRE photo, preserving alpha for extraction. */
        fun snapshot(image: Bitmap): Bitmap {
            val result = createBitmap(64, 64)
            try {
                Canvas(result).drawBitmap(image, null, RectF(0f, 0f, 64f, 64f), Paint(Paint.FILTER_BITMAP_FLAG))
                return result
            } catch (error: Throwable) {
                result.recycle()
                throw error
            }
        }

        /** Worker owns the sample; cancellation and bitmap recycling are managed by the caller. */
        suspend fun prepare(sample: Bitmap, width: Int, height: Int, style: String): PhotoColorBackground {
            require(width > 0 && height > 0)
            require(BackgroundStyle.isPhotoColor(style))
            coroutineContext.ensureActive()
            val pixels = IntArray(sample.width * sample.height)
            sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
            val palette = PhotoPalette.extract(pixels).map(PhotoPalette::muted)
            coroutineContext.ensureActive()
            return if (style == BackgroundStyle.DARK_TEXTURE) darkTexture(width, height, palette)
            else lowPoly(width, height, palette)
        }

        private suspend fun darkTexture(width: Int, height: Int, palette: List<Int>): PhotoColorBackground {
            val top = PhotoPalette.mix(palette[0], Color.rgb(12, 15, 17), .82f)
            val bottom = PhotoPalette.mix(palette[2], Color.rgb(13, 16, 17), .90f)
            val base = Paint().apply {
                shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), top, bottom, Shader.TileMode.CLAMP)
            }
            var texture: Bitmap? = null
            try {
                val random = Random(17)
                val pixels = IntArray(64 * 64)
                for (y in 0 until 64) {
                    coroutineContext.ensureActive()
                    for (x in 0 until 64) {
                        // At most ~3 levels of luminance over the dark base, no high-contrast speckle.
                        pixels[y * 64 + x] = Color.argb(random.nextInt(7), 128, 128, 128)
                    }
                }
                texture = Bitmap.createBitmap(pixels, 64, 64, Bitmap.Config.ARGB_8888)
                coroutineContext.ensureActive()
                return PhotoColorBackground(width, height, base, texture = texture).also { texture = null }
            } finally { texture?.recycle() }
        }

        private suspend fun lowPoly(width: Int, height: Int, palette: List<Int>): PhotoColorBackground {
            // Fixed seed + aspect-aware grid: stable across redraws, process restores and Engines.
            val random = Random(123)
            val columns = if (width >= height) 6 else (6f * width / height).roundToInt().coerceIn(2, 6)
            val rows = if (height >= width) 6 else (6f * height / width).roundToInt().coerceIn(2, 6)
            val cellWidth = width.toFloat() / columns
            val cellHeight = height.toFloat() / rows
            val points = List(rows + 1) { y ->
                List(columns + 1) { x ->
                    val px = x * cellWidth + if (x in 1 until columns) (random.nextFloat() - .5f) * cellWidth * .55f else 0f
                    val py = y * cellHeight + if (y in 1 until rows) (random.nextFloat() - .5f) * cellHeight * .55f else 0f
                    px to py
                }
            }
            val triangles = buildList {
                for (y in 0 until rows) {
                    coroutineContext.ensureActive()
                    for (x in 0 until columns) {
                        val a = points[y][x]; val b = points[y][x + 1]
                        val c = points[y + 1][x]; val d = points[y + 1][x + 1]
                        val vertices = if (random.nextBoolean()) listOf(listOf(a, b, c), listOf(b, d, c))
                            else listOf(listOf(a, b, d), listOf(a, d, c))
                        vertices.forEach { triangle ->
                            val path = Path().apply {
                                moveTo(triangle[0].first, triangle[0].second)
                                lineTo(triangle[1].first, triangle[1].second)
                                lineTo(triangle[2].first, triangle[2].second)
                                close()
                            }
                            val color = PhotoPalette.mix(palette[random.nextInt(palette.size)], palette[0], .35f)
                            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                this.color = color
                                // Sub-pixel overlap prevents hairline gaps without outlining the triangles.
                                style = Paint.Style.FILL_AND_STROKE
                                strokeWidth = 1f
                            }
                            add(path to paint)
                        }
                    }
                }
            }
            return PhotoColorBackground(width, height, Paint().apply { color = palette[0] }, triangles)
        }
    }
}
