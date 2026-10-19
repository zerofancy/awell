package top.ntutn.awall.photos

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

class PhotoLoader(private val resolver: ContentResolver) {
    /** Per-Engine allowance includes the displayed image, candidate and orientation buffer. */
    suspend fun load(uri: Uri, width: Int, height: Int, budget: Long): Bitmap =
        PhotoIo.run(discard = Bitmap::recycle) { signal ->
            fun descriptor() = resolver.openFileDescriptor(uri, "r", signal)
                ?: throw IOException("Photo unavailable")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            descriptor().use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Unsupported photo")
            val orientation = try {
                descriptor().use { file ->
                    ExifInterface(file.fileDescriptor).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL)
                }
            } catch (_: IOException) { ExifInterface.ORIENTATION_NORMAL }
            val rotated = orientation in setOf(ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSPOSE,
                ExifInterface.ORIENTATION_TRANSVERSE)
            val displayWidth = if (rotated) bounds.outHeight else bounds.outWidth
            val displayHeight = if (rotated) bounds.outWidth else bounds.outHeight
            val fit = min(width.toDouble() / displayWidth, height.toDouble() / displayHeight).coerceAtMost(1.0)
            var sample = 1
            val targetWidth = max(1, (bounds.outWidth * fit).toInt())
            val targetHeight = max(1, (bounds.outHeight * fit).toInt())
            while (bounds.outWidth / (sample * 2) >= targetWidth &&
                bounds.outHeight / (sample * 2) >= targetHeight) sample *= 2
            // Three buffers: old picture, decoded candidate and transformed candidate.
            while (max(1, bounds.outWidth / sample).toLong() *
                max(1, bounds.outHeight / sample) * 4 * 3 > budget) sample *= 2
            signal.throwIfCanceled()
            var bitmap: Bitmap? = null
            try {
                bitmap = descriptor().use {
                    BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null,
                        BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
                } ?: throw IOException("Cannot decode photo")
                signal.throwIfCanceled()
                val matrix = orientationMatrix(orientation)
                if (!matrix.isIdentity) {
                    val corrected = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    if (corrected !== bitmap) bitmap.recycle()
                    bitmap = corrected
                }
                signal.throwIfCanceled()
                bitmap.also { bitmap = null }
            } catch (error: OutOfMemoryError) {
                throw IOException("Photo exceeds memory budget", error)
            } finally {
                bitmap?.recycle()
            }
        }

    private fun orientationMatrix(orientation: Int) = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
}
