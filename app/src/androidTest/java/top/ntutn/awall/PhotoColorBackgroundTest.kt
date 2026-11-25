package top.ntutn.awall

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import top.ntutn.awall.photos.BackgroundStyle
import top.ntutn.awall.photos.PhotoColorBackground

@RunWith(AndroidJUnit4::class)
class PhotoColorBackgroundTest {
    @Test fun snapshotIncludesTheWholePhotoAndDoesNotRetainTheSource() {
        val source = Bitmap.createBitmap(400, 20, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until source.height) for (x in 0 until source.width) {
                source.setPixel(x, y, if (x < 200) Color.RED else Color.BLUE)
            }
            val sample = PhotoColorBackground.snapshot(source)
            try {
                assertEquals(64, sample.width)
                assertEquals(64, sample.height)
                assertEquals(Color.RED, sample.getPixel(0, 32))
                assertEquals(Color.BLUE, sample.getPixel(63, 32))
                source.eraseColor(Color.GREEN)
                assertEquals(Color.RED, sample.getPixel(0, 32))
            } finally { sample.recycle() }
        } finally { source.recycle() }
    }

    @Test fun transparentPhotosHaveAnOpaqueNeutralBackgroundForBothStyles() = runBlocking {
        val sample = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val output = Bitmap.createBitmap(180, 360, Bitmap.Config.ARGB_8888)
        try {
            for (style in listOf(BackgroundStyle.DARK_TEXTURE, BackgroundStyle.LOW_POLY)) {
                val background = PhotoColorBackground.prepare(sample, 180, 360, style)
                try {
                    background.draw(Canvas(output))
                    for (y in 0 until output.height) for (x in 0 until output.width) {
                        assertEquals(255, Color.alpha(output.getPixel(x, y)))
                    }
                } finally { background.close() }
            }
        } finally { sample.recycle(); output.recycle() }
    }

    @Test fun textureIsFineLowContrastAndDeterministicAtFullSurfaceResolution() = runBlocking {
        val sample = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val output = Bitmap.createBitmap(720, 1440, Bitmap.Config.ARGB_8888)
        try {
            val first = PhotoColorBackground.prepare(sample, 720, 1440, BackgroundStyle.DARK_TEXTURE)
            val restored = PhotoColorBackground.prepare(sample, 720, 1440, BackgroundStyle.DARK_TEXTURE)
            try {
                first.draw(Canvas(output))
                val pixels = IntArray(720 * 1440)
                output.getPixels(pixels, 0, 720, 0, 0, 720, 1440)
                assertTrue(pixels.all { maxOf(Color.red(it), Color.green(it), Color.blue(it)) < 65 })
                val line = pixels.sliceArray(720 * 100 until 720 * 100 + 64)
                assertTrue(line.distinct().size > 1)
                for (i in 1 until line.size) {
                    assertTrue(kotlin.math.abs(Color.red(line[i]) - Color.red(line[i - 1])) <= 5)
                }
                restored.draw(Canvas(output))
                val actual = IntArray(pixels.size)
                output.getPixels(actual, 0, 720, 0, 0, 720, 1440)
                assertArrayEquals(pixels, actual)
            } finally { first.close(); restored.close() }
        } finally { sample.recycle(); output.recycle() }
    }

    @Test fun polygonMeshIsStableOpaqueAndHasTonalVariationInBothOrientations() = runBlocking {
        val sample = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        try {
            for ((w, h) in listOf(180 to 360, 360 to 180)) {
                val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val first = PhotoColorBackground.prepare(sample, w, h, BackgroundStyle.LOW_POLY)
                val restored = PhotoColorBackground.prepare(sample, w, h, BackgroundStyle.LOW_POLY)
                try {
                    first.draw(Canvas(output))
                    val pixels = IntArray(w * h)
                    output.getPixels(pixels, 0, w, 0, 0, w, h)
                    assertTrue(pixels.all { Color.alpha(it) == 255 })
                    assertTrue(pixels.distinct().size > 1)
                    assertTrue(pixels.all { Color.red(it) > 100 }) // No black seams or holes.
                    restored.draw(Canvas(output))
                    val actual = IntArray(pixels.size)
                    output.getPixels(actual, 0, w, 0, 0, w, h)
                    assertArrayEquals(pixels, actual)
                } finally { first.close(); restored.close(); output.recycle() }
            }
        } finally { sample.recycle() }
    }
}
