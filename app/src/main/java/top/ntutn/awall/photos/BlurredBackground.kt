package top.ntutn.awall.photos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object BackgroundStyle {
    const val RANDOM_RECTANGLES = "random_rectangles"
    const val BLURRED_PHOTO = "blurred_photo"
    fun normalize(value: String): String = if (value == BLURRED_PHOTO) value else RANDOM_RECTANGLES
}

/** Bitmap and two pixel arrays, plus old/new background caches. Reserved before photo decoding. */
const val BACKGROUND_RESERVE_BYTES = 5L * 256 * 256 * 4

object BlurredBackground {
    /** Called on the owner thread: the worker never reads a foreground that could be recycled. */
    fun snapshot(image: Bitmap, width: Int, height: Int): Bitmap {
        val scale = min(1f, 256f / max(width, height))
        val w = (width * scale).roundToInt().coerceAtLeast(1)
        val h = (height * scale).roundToInt().coerceAtLeast(1)
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(result)
            canvas.drawColor(Color.BLACK)
            val fill = max(w.toFloat() / image.width, h.toFloat() / image.height)
            val left = (w - image.width * fill) / 2
            val top = (h - image.height * fill) / 2
            canvas.drawBitmap(image, null,
                RectF(left, top, left + image.width * fill, top + image.height * fill),
                Paint(Paint.FILTER_BITMAP_FLAG))
            return result
        } catch (error: Throwable) {
            result.recycle()
            throw error
        }
    }

    /** Three separable box passes with clamped borders. Mutates only the owned small snapshot. */
    suspend fun blur(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        val radius = max(1, (min(w, h) * .04f).roundToInt())
        val pixels = IntArray(w * h)
        val scratch = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        repeat(3) {
            pass(pixels, scratch, w, h, radius, horizontal = true)
            pass(scratch, pixels, w, h, radius, horizontal = false)
        }
        // Apply darkness only to the background; pixels are already opaque over black.
        pixels.indices.forEach { index ->
            val color = pixels[index]
            pixels[index] = Color.rgb((Color.red(color) * .78f).roundToInt(),
                (Color.green(color) * .78f).roundToInt(), (Color.blue(color) * .78f).roundToInt())
        }
        coroutineContext.ensureActive()
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private suspend fun pass(input: IntArray, output: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
        val length = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val count = radius * 2 + 1
        for (line in 0 until lines) {
            coroutineContext.ensureActive()
            fun pixel(position: Int): Int {
                val p = position.coerceIn(0, length - 1)
                return input[if (horizontal) line * w + p else p * w + line]
            }
            var red = 0; var green = 0; var blue = 0
            for (position in -radius..radius) {
                val color = pixel(position)
                red += Color.red(color); green += Color.green(color); blue += Color.blue(color)
            }
            for (position in 0 until length) {
                output[if (horizontal) line * w + position else position * w + line] =
                    Color.rgb(red / count, green / count, blue / count)
                val old = pixel(position - radius)
                val next = pixel(position + radius + 1)
                red += Color.red(next) - Color.red(old)
                green += Color.green(next) - Color.green(old)
                blue += Color.blue(next) - Color.blue(old)
            }
        }
    }
}
