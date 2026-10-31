package top.ntutn.awall

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import top.ntutn.awall.photos.*

@RunWith(AndroidJUnit4::class)
class WallpaperFrameRendererTest {
    @Test fun blurKeepsOpaqueClampedEdgesAndHandlesTransparentPhotos() = runBlocking {
        val source = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.RED)
            val background = BlurredBackground.snapshot(source, 1260, 2800)
            try {
                assertEquals(256, background.height)
                assertEquals(115, background.width)
                BlurredBackground.blur(background)
                for ((x, y) in listOf(0 to 0, 57 to 128, 114 to 255)) {
                    val pixel = background.getPixel(x, y)
                    assertEquals(255, Color.alpha(pixel))
                    assertEquals(199, Color.red(pixel))
                    assertEquals(0, Color.green(pixel))
                }
            } finally { background.recycle() }
            source.eraseColor(Color.TRANSPARENT)
            val transparent = BlurredBackground.snapshot(source, 20, 40)
            try {
                BlurredBackground.blur(transparent)
                assertEquals(Color.BLACK, transparent.getPixel(0, 0))
                assertEquals(Color.BLACK, transparent.getPixel(10, 20))
            } finally { transparent.recycle() }
        } finally { source.recycle() }
    }

    @Test fun switchingStylePreservesSharpCompleteForegroundAndSceneRectangles() = renderTest { renderer, awaitFrame ->
        val image = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.RED)
        val scene = WallpaperScene(null, Uri.parse("content://photos/red"), listOf(BackgroundRectangle(0f, 0f, 1f, 1f, Color.BLUE)))
        renderer.submit(scene, image, 100, 200)
        awaitFrame(1)
        renderer.setStyle(BackgroundStyle.BLURRED_PHOTO)
        awaitFrame(2)
        val output = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        try {
            renderer.draw(Canvas(output))
            assertEquals(Color.RED, output.getPixel(50, 75))
            assertEquals(Color.RED, output.getPixel(50, 124))
            assertEquals(199, Color.red(output.getPixel(0, 0)))
            assertEquals(255, Color.alpha(output.getPixel(99, 199)))
            assertFalse(image.isRecycled)
            renderer.setStyle(BackgroundStyle.RANDOM_RECTANGLES)
            awaitFrame(3)
            renderer.draw(Canvas(output))
            assertEquals(Color.BLUE, output.getPixel(0, 0))
            assertEquals(Color.RED, output.getPixel(50, 100))
        } finally { output.recycle() }
    }

    @Test fun cancellationDropsOldPhotoAndNoPhotoClearsDerivedBackground() = renderTest { renderer, awaitFrame ->
        renderer.setStyle(BackgroundStyle.BLURRED_PHOTO)
        val old = Bitmap.createBitmap(120, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val next = Bitmap.createBitmap(120, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        renderer.submit(WallpaperScene(null, Uri.parse("content://photos/old")), old, 100, 200)
        renderer.submit(WallpaperScene(null, Uri.parse("content://photos/new")), next, 100, 200)
        awaitFrame(1)
        delay(30.milliseconds)
        assertTrue(old.isRecycled)
        val output = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        try {
            renderer.draw(Canvas(output))
            assertEquals(Color.BLUE, output.getPixel(50, 100))
            assertEquals(199, Color.blue(output.getPixel(0, 0)))
            renderer.submit(WallpaperScene(null, null), null, 100, 200)
            awaitFrame(2)
            renderer.draw(Canvas(output))
            assertEquals(Color.BLACK, output.getPixel(50, 100))
            assertEquals(Color.BLACK, output.getPixel(0, 0))
            assertTrue(next.isRecycled)
        } finally { output.recycle() }
    }

    @Test fun deadlineFallsBackToBlackWhileKeepingTheSharpForeground() = renderTest { renderer, awaitFrame ->
        renderer.setStyle(BackgroundStyle.BLURRED_PHOTO)
        val image = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        renderer.submit(WallpaperScene(null, Uri.parse("content://photos/a")), image, 100, 200, deadline = PreparationDeadline.after(Duration.ZERO))
        awaitFrame(1)
        val output = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        try {
            renderer.draw(Canvas(output))
            assertEquals(Color.BLACK, output.getPixel(0, 0))
            assertEquals(Color.RED, output.getPixel(50, 100))
            assertFalse(image.isRecycled)
        } finally { output.recycle() }
    }

    @Test fun transparentForegroundShowsDerivedBackgroundRatherThanOldScene() = renderTest { renderer, awaitFrame ->
        renderer.setStyle(BackgroundStyle.BLURRED_PHOTO)
        val image = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }
        renderer.submit(WallpaperScene(null, Uri.parse("content://photos/transparent")), image, 100, 200)
        awaitFrame(1)
        val output = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        try {
            renderer.draw(Canvas(output))
            assertEquals(Color.BLACK, output.getPixel(50, 100))
            assertEquals(Color.BLACK, output.getPixel(0, 0))
        } finally { output.recycle() }
    }

    @Test fun foregroundPreservesAllFourEdgesInsteadOfCroppingOrStretching() = renderTest { renderer, awaitFrame ->
        val image = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
            for (y in 0 until 50) for (x in 0 until 100) {
                when {
                    x < 5 -> setPixel(x, y, Color.GREEN)
                    x >= 95 -> setPixel(x, y, Color.BLUE)
                    y < 5 -> setPixel(x, y, Color.YELLOW)
                    y >= 45 -> setPixel(x, y, Color.MAGENTA)
                }
            }
        }
        renderer.setStyle(BackgroundStyle.BLURRED_PHOTO)
        renderer.submit(WallpaperScene(null, Uri.parse("content://photos/edges")), image, 100, 200)
        awaitFrame(1)
        val output = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        try {
            renderer.draw(Canvas(output))
            assertEquals(Color.GREEN, output.getPixel(1, 100))
            assertEquals(Color.BLUE, output.getPixel(98, 100))
            assertEquals(Color.YELLOW, output.getPixel(50, 76))
            assertEquals(Color.MAGENTA, output.getPixel(50, 123))
            assertEquals(Color.RED, output.getPixel(50, 100))
        } finally { output.recycle() }
    }

    private fun renderTest(body: suspend (WallpaperFrameRenderer, suspend (Int) -> Unit) -> Unit) = runBlocking {
        withContext(Dispatchers.Main) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            var frames = 0
            val renderer = WallpaperFrameRenderer(scope, { true }, { frames++ })
            try {
                body(renderer) { count -> withTimeout(3.seconds) { while (frames < count) delay(10.milliseconds) } }
            } finally { renderer.close(); scope.cancel() }
        }
    }
}
