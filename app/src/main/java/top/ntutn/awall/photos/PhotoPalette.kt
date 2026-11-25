package top.ntutn.awall.photos

import kotlin.math.roundToInt

/** Small, deterministic palette from a whole-photo sample; no Android or foreground ownership. */
internal object PhotoPalette {
    private const val FALLBACK = 0xFF53646B.toInt()

    fun extract(pixels: IntArray): List<Int> {
        // Three bits per channel. Alpha weights avoid transparent RGB polluting the palette.
        val weight = IntArray(512)
        val red = LongArray(512)
        val green = LongArray(512)
        val blue = LongArray(512)
        for (pixel in pixels) {
            val alpha = pixel ushr 24
            if (alpha < 32) continue
            val r = pixel ushr 16 and 255
            val g = pixel ushr 8 and 255
            val b = pixel and 255
            val bucket = ((r shr 5) shl 6) or ((g shr 5) shl 3) or (b shr 5)
            weight[bucket] += alpha
            red[bucket] += r.toLong() * alpha
            green[bucket] += g.toLong() * alpha
            blue[bucket] += b.toLong() * alpha
        }
        val result = mutableListOf<Int>()
        val buckets = weight.indices.filter { weight[it] > 0 }
            .sortedWith(compareByDescending<Int> { weight[it] }.thenBy { it })
        for (bucket in buckets) {
            val color = rgb((red[bucket] / weight[bucket]).toInt(),
                (green[bucket] / weight[bucket]).toInt(), (blue[bucket] / weight[bucket]).toInt())
            if (result.all { distance(it, color) > 48 * 48 }) result.add(color)
            if (result.size == 4) break
        }
        if (result.isEmpty()) result.add(FALLBACK)
        // Gentle tonal variants keep monochrome photos from producing a flat geometric background.
        val dominant = result.first()
        while (result.size < 4) {
            result.add(mix(dominant, if (result.size % 2 == 0) 0xFF161E22.toInt() else 0xFFD3D0C3.toInt(), .18f))
        }
        return result.toList()
    }

    fun muted(color: Int): Int {
        val r = color ushr 16 and 255
        val g = color ushr 8 and 255
        val b = color and 255
        val luminance = .2126f * r + .7152f * g + .0722f * b
        fun channel(value: Int) = ((luminance + (value - luminance) * .75f) * .76f).roundToInt()
        return rgb(channel(r), channel(g), channel(b))
    }

    fun mix(first: Int, second: Int, amount: Float): Int {
        fun channel(shift: Int) = (((first ushr shift and 255) * (1 - amount)) +
            (second ushr shift and 255) * amount).roundToInt()
        return rgb(channel(16), channel(8), channel(0))
    }

    private fun distance(first: Int, second: Int): Int = listOf(16, 8, 0).sumOf { shift ->
        val delta = (first ushr shift and 255) - (second ushr shift and 255)
        delta * delta
    }

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        (255 shl 24) or (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)
}
