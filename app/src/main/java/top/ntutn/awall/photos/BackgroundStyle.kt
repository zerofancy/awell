package top.ntutn.awall.photos

object BackgroundStyle {
    const val RANDOM_RECTANGLES = "random_rectangles"
    const val BLURRED_PHOTO = "blurred_photo"
    const val DARK_TEXTURE = "dark_texture"
    const val LOW_POLY = "low_poly"

    val supported = listOf(RANDOM_RECTANGLES, BLURRED_PHOTO, DARK_TEXTURE, LOW_POLY)

    fun normalize(value: String): String = value.takeIf { it in supported } ?: RANDOM_RECTANGLES

    fun isPhotoColor(value: String): Boolean = value == DARK_TEXTURE || value == LOW_POLY
}
