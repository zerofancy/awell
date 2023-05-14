package top.ntutn.awall

import android.graphics.Color
import android.graphics.Paint
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.artjimlop.altex.AltexImageDownloader
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.random.nextInt

class AWallpaperService : WallpaperService() {
    inner class AWallEngine : Engine() {
        private lateinit var scope: CoroutineScope
        private var job: Job? = null
        private val paint = Paint()
        private val picturesDir = File(filesDir, "pictures")
        private val random = Random(System.currentTimeMillis())

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            scope = CoroutineScope(Dispatchers.IO)
            scope.launch {
                while (true) {
                    checkFileDownload()
                    delay(60_000)
                }
            }
        }

        private suspend fun checkFileDownload() = withContext(Dispatchers.IO) {
            picturesDir.mkdirs()
            picturesDir.listFiles { file: File ->
                System.currentTimeMillis() - file.lastModified() > 7 *24 * 3600_000
            }?.forEach {
                it.deleteRecursively()
            }
            //AltexImageDownloader.writeToDisk(this@AWallpaperService, "https://unsplash.it/1600/900?random", picturesDir.canonicalPath);
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder?,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            refreshJob()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                refreshJob()
            } else {
                job?.cancel()
            }
        }

        private fun refreshJob() {
            job?.cancel()
            job = scope.launch {
                val random = Random(System.currentTimeMillis())
                while (true) {
                    val canvas = kotlin.runCatching {
                        surfaceHolder?.lockCanvas()
                    }.getOrNull()
                    canvas?.drawColor(Color.BLACK)
                    repeat(10) {
                        val rangeColor = 0..255
                        paint.color = Color.rgb(random.nextInt(rangeColor), random.nextInt(rangeColor), random.nextInt(rangeColor))
                        val rangeW = 0..(surfaceHolder?.surfaceFrame?.width()?:1)
                        val rangeH = 0..(surfaceHolder?.surfaceFrame?.height()?:1)
                        val w1 = random.nextInt(rangeW).toFloat()
                        val w2 = random.nextInt(rangeW).toFloat()
                        val h1 = random.nextInt(rangeH).toFloat()
                        val h2 = random.nextInt(rangeH).toFloat()
                        canvas?.drawRect(min(w1, w2), min(h1, h2), max(w1, w2), max(h1, h2), paint)
                    }
                    surfaceHolder?.unlockCanvasAndPost(canvas)
                    delay(60_000L)
                }
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            job?.cancel()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            super.onDestroy()
            scope.cancel()
        }
    }

    override fun onCreateEngine(): Engine = AWallEngine()
}