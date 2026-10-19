package top.ntutn.awall

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import top.ntutn.awall.photos.PhotoLoader
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhotoLoaderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val loader = PhotoLoader(context.contentResolver)

    @Test fun preservesTransparencyAndDownsamplesLargeImages() = runBlocking {
        val file = File.createTempFile("wallpaper", ".png", context.cacheDir)
        try {
            val source = Bitmap.createBitmap(2400, 1200, Bitmap.Config.ARGB_8888)
            source.eraseColor(Color.TRANSPARENT)
            file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            source.recycle()
            val bitmap = loader.load(Uri.fromFile(file), 240, 120, 1024 * 1024)
            try {
                assertTrue(bitmap.width < 2400)
                assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
                assertTrue(bitmap.allocationByteCount.toLong() * 3 <= 1024 * 1024)
            } finally { bitmap.recycle() }
        } finally { file.delete() }
    }

    @Test fun honorsExifRotationAndMirror() = runBlocking {
        val file = File.createTempFile("wallpaper", ".jpg", context.cacheDir)
        try {
            val source = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
            for (y in 0 until 40) for (x in 0 until 80) source.setPixel(x, y, if (x < 40) Color.RED else Color.BLUE)
            file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            source.recycle()
            val exif = ExifInterface(file)
            for (orientation in listOf(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_FLIP_HORIZONTAL)) {
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                exif.saveAttributes()
                val bitmap = loader.load(Uri.fromFile(file), 200, 200, 1024 * 1024)
                try {
                    if (orientation == ExifInterface.ORIENTATION_ROTATE_90) {
                        assertEquals(40, bitmap.width)
                        assertEquals(80, bitmap.height)
                        assertTrue(Color.red(bitmap.getPixel(20, 10)) > 200)
                    } else {
                        assertEquals(80, bitmap.width)
                        assertTrue(Color.blue(bitmap.getPixel(10, 20)) > 200)
                    }
                } finally { bitmap.recycle() }
            }
        } finally { file.delete() }
    }

    @Test fun rejectsCorruptPhotos() = runBlocking {
        val file = File.createTempFile("wallpaper", ".jpg", context.cacheDir)
        try {
            file.writeText("not an image")
            try {
                loader.load(Uri.fromFile(file), 100, 100, 1024 * 1024).recycle()
                fail("Corrupt file must be skipped")
            } catch (_: java.io.IOException) { }
        } finally { file.delete() }
    }
}
