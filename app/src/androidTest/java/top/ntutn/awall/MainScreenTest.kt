package top.ntutn.awall

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.ntutn.awall.ui.MainScreen
import top.ntutn.awall.ui.theme.AWallTheme

@RunWith(AndroidJUnit4::class)
class MainScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun lightThemeShowsTitleAndOpensPreviewOnClick() {
        verifyPreviewButton(darkTheme = false)
    }

    @Test
    fun darkThemeShowsTitleAndOpensPreviewOnClick() {
        verifyPreviewButton(darkTheme = true)
    }

    private fun verifyPreviewButton(darkTheme: Boolean) {
        var previewRequests = 0
        composeRule.setContent {
            AWallTheme(darkTheme = darkTheme) {
                MainScreen(onOpenWallpaperPreview = { previewRequests++ })
            }
        }
        composeRule.onNodeWithText("AWell").assertIsDisplayed()
        composeRule.onNodeWithText("设置动态壁纸").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, previewRequests) }
    }
}
