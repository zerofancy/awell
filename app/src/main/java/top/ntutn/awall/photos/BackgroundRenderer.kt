package top.ntutn.awall.photos

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

data class BackgroundRectangle(val left: Float, val top: Float, val right: Float, val bottom: Float, val color: Int)

object BackgroundRenderer {
    fun create(random: Random = Random.Default): List<BackgroundRectangle> = List(10) {
        val x1 = random.nextFloat()
        val x2 = random.nextFloat()
        val y1 = random.nextFloat()
        val y2 = random.nextFloat()
        BackgroundRectangle(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2),
            Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)))
    }

    fun draw(canvas: Canvas, rectangles: List<BackgroundRectangle>, paint: Paint) {
        canvas.drawColor(Color.BLACK)
        rectangles.forEach {
            paint.color = it.color
            canvas.drawRect(it.left * canvas.width, it.top * canvas.height,
                it.right * canvas.width, it.bottom * canvas.height, paint)
        }
    }
}
