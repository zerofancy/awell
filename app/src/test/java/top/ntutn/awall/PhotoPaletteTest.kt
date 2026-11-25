package top.ntutn.awall

import org.junit.Assert.*
import org.junit.Test
import top.ntutn.awall.photos.BackgroundStyle
import top.ntutn.awall.photos.PhotoPalette

class PhotoPaletteTest {
    @Test fun allFourStylesAreStableAndUnknownStylesKeepTheOldDefault() {
        assertEquals(listOf("random_rectangles", "blurred_photo", "dark_texture", "low_poly"), BackgroundStyle.supported)
        BackgroundStyle.supported.forEach { assertEquals(it, BackgroundStyle.normalize(it)) }
        assertEquals(BackgroundStyle.RANDOM_RECTANGLES, BackgroundStyle.normalize("future_style"))
        assertTrue(BackgroundStyle.isPhotoColor(BackgroundStyle.DARK_TEXTURE))
        assertTrue(BackgroundStyle.isPhotoColor(BackgroundStyle.LOW_POLY))
        assertFalse(BackgroundStyle.isPhotoColor(BackgroundStyle.BLURRED_PHOTO))
    }

    @Test fun dominantColorWinsButOtherDistinctPhotoColorsParticipate() {
        val red = 0xFFFF3020.toInt()
        val green = 0xFF20D050.toInt()
        val blue = 0xFF2040E0.toInt()
        val pixels = IntArray(100) { when { it < 60 -> red; it < 85 -> green; else -> blue } }
        val palette = PhotoPalette.extract(pixels)
        assertEquals(red, palette.first())
        assertTrue(palette.contains(green))
        assertTrue(palette.contains(blue))
        assertEquals(4, palette.size)
    }

    @Test fun transparentRgbDoesNotPolluteColorsAndPartialAlphaHasLessWeight() {
        val pixels = IntArray(100) { if (it < 70) 0x00FF0000 else 0xFF2050C0.toInt() }
        assertEquals(0xFF2050C0.toInt(), PhotoPalette.extract(pixels).first())
        val weighted = IntArray(100) { if (it < 70) 0x40FF0000 else 0xFF00FF00.toInt() }
        assertEquals(0xFF00FF00.toInt(), PhotoPalette.extract(weighted).first())
    }

    @Test fun emptyTransparentAndMonochromePhotosHaveOpaqueTonalFallbacks() {
        for (pixels in listOf(intArrayOf(), IntArray(32), IntArray(32) { -1 }, IntArray(32) { 0xFF000000.toInt() })) {
            val palette = PhotoPalette.extract(pixels)
            assertEquals(4, palette.size)
            assertTrue(palette.all { it ushr 24 == 255 })
            assertTrue(palette.distinct().size >= 3)
        }
    }

    @Test fun extractionIsDeterministicAndDoesNotModifyTheInput() {
        val pixels = IntArray(4096) { (255 shl 24) or (it * 1234567 and 0xFFFFFF) }
        val original = pixels.copyOf()
        val first = PhotoPalette.extract(pixels)
        assertEquals(first, PhotoPalette.extract(pixels.reversedArray()))
        assertArrayEquals(original, pixels)
    }

    @Test fun mutedColorsAndMixingStayOpaqueAndBounded() {
        assertEquals(0xFF000000.toInt(), PhotoPalette.muted(0xFF000000.toInt()))
        assertEquals(0xFFC2C2C2.toInt(), PhotoPalette.muted(-1))
        assertEquals(0xFF808080.toInt(), PhotoPalette.mix(0xFF000000.toInt(), -1, .5f))
        val red = PhotoPalette.muted(0xFFFF0000.toInt())
        assertTrue((red ushr 16 and 255) > (red and 255))
        assertTrue((red ushr 16 and 255) < 255)
        assertEquals(255, red ushr 24)
    }
}
