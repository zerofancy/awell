package top.ntutn.awall.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import top.ntutn.awall.R

@Composable
fun AWallTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) {
        darkColors(
            primary = colorResource(R.color.purple_200),
            primaryVariant = colorResource(R.color.purple_700),
            onPrimary = colorResource(R.color.black),
            secondary = colorResource(R.color.teal_200),
            secondaryVariant = colorResource(R.color.teal_200),
            onSecondary = colorResource(R.color.black)
        )
    } else {
        lightColors(
            primary = colorResource(R.color.purple_500),
            primaryVariant = colorResource(R.color.purple_700),
            onPrimary = colorResource(R.color.white),
            secondary = colorResource(R.color.teal_200),
            secondaryVariant = colorResource(R.color.teal_700),
            onSecondary = colorResource(R.color.black)
        )
    }
    MaterialTheme(colors = colors, content = content)
}
