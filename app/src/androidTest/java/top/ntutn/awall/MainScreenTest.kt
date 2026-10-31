package top.ntutn.awall

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.ntutn.awall.photos.WallpaperState
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

    @Test
    fun switchesRemainIndependentAndIntervalRequiresTimer() {
        var state by mutableStateOf(WallpaperState())
        composeRule.setContent {
            AWallTheme {
                MainScreen(
                    onOpenWallpaperPreview = {},
                    wallpaperState = state,
                    onUnlockEnabledChanged = { state = state.copy(unlockEnabled = it) },
                    onTimerEnabledChanged = { state = state.copy(timerEnabled = it) },
                )
            }
        }
        composeRule.onNodeWithTag("unlock_setting").performScrollTo().assertIsOn()
        composeRule.onNodeWithTag("timer_setting").assertIsOff()
        composeRule.onNodeWithTag("interval_setting").assertIsNotEnabled()

        composeRule.onNodeWithTag("unlock_setting").performScrollTo().performClick().assertIsOff()
        composeRule.onNodeWithTag("timer_setting").performScrollTo().performClick().assertIsOn()
        composeRule.onNodeWithTag("unlock_setting").assertIsOff()
        composeRule.onNodeWithTag("interval_setting").assertIsEnabled()

        composeRule.onNodeWithTag("unlock_setting").performScrollTo().performClick().assertIsOn()
        composeRule.onNodeWithTag("timer_setting").assertIsOn()
        composeRule.onNodeWithTag("timer_setting").performScrollTo().performClick().assertIsOff()
        composeRule.onNodeWithTag("unlock_setting").assertIsOn()
        composeRule.onNodeWithTag("interval_setting").assertIsNotEnabled()
    }

    @Test
    fun intervalMenuOffersAllSupportedDurations() {
        var state by mutableStateOf(WallpaperState(timerEnabled = true))
        composeRule.setContent {
            AWallTheme {
                MainScreen(
                    onOpenWallpaperPreview = {},
                    wallpaperState = state,
                    onIntervalChanged = { state = state.copy(intervalMillis = it.inWholeMilliseconds) },
                )
            }
        }
        listOf(
            "15 分钟" to 15.minutes,
            "30 分钟" to 30.minutes,
            "1 小时" to 1.hours,
            "6 小时" to 6.hours,
            "24 小时" to 24.hours,
        ).forEach { (label, duration) ->
            composeRule.onNodeWithTag("interval_setting").performScrollTo().performClick()
            composeRule.onNodeWithText(label).performClick()
            composeRule.runOnIdle { assertEquals(duration, state.interval) }
            composeRule.onNodeWithText("更换间隔：$label").assertIsDisplayed()
        }
    }

    @Test
    fun settingsOnlyShowCommittedValues() {
        var committedState by mutableStateOf(WallpaperState())
        var requestedTimerValue: Boolean? = null
        composeRule.setContent {
            AWallTheme {
                MainScreen(
                    onOpenWallpaperPreview = {},
                    wallpaperState = committedState,
                    onTimerEnabledChanged = { requestedTimerValue = it },
                )
            }
        }
        composeRule.onNodeWithTag("timer_setting").performScrollTo().performClick().assertIsOff()
        composeRule.runOnIdle {
            assertEquals(true, requestedTimerValue)
            committedState = committedState.copy(timerEnabled = true)
        }
        composeRule.onNodeWithTag("timer_setting").assertIsOn()
    }

    @Test
    fun loadingSavingAndReadErrorsDisableEditing() {
        var state by mutableStateOf<WallpaperState?>(null)
        var saving by mutableStateOf(false)
        var storageError by mutableStateOf(false)
        var retries = 0
        composeRule.setContent {
            AWallTheme {
                MainScreen(
                    onOpenWallpaperPreview = {},
                    wallpaperState = state,
                    savingSettings = saving,
                    storageError = storageError,
                    onRetryLoading = { retries++ },
                )
            }
        }
        composeRule.onNodeWithTag("unlock_setting").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("timer_setting").assertIsNotEnabled()
        composeRule.onNodeWithTag("interval_setting").assertIsNotEnabled()

        composeRule.runOnIdle {
            state = WallpaperState(timerEnabled = true)
            saving = true
        }
        composeRule.onNodeWithTag("unlock_setting").assertIsNotEnabled()
        composeRule.onNodeWithTag("timer_setting").assertIsNotEnabled()
        composeRule.onNodeWithTag("interval_setting").assertIsNotEnabled()

        composeRule.runOnIdle {
            saving = false
            storageError = true
        }
        composeRule.onNodeWithText("暂时无法读取设置，请重试。").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("重试").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
        composeRule.onNodeWithTag("timer_setting").assertIsNotEnabled()
    }

    @Test fun backgroundSelectionWaitsForPersistenceAndSupportsDisabledState() {
        var state by mutableStateOf(WallpaperState())
        var saving by mutableStateOf(false)
        var requested: String? = null
        composeRule.setContent {
            AWallTheme {
                MainScreen(onOpenWallpaperPreview = {}, wallpaperState = state, savingSettings = saving,
                    onBackgroundStyleChanged = { requested = it })
            }
        }
        composeRule.onNodeWithTag("background_random_rectangles").performScrollTo()
            .assertIsSelected().performClick()
        composeRule.runOnIdle { assertEquals(null, requested) }
        composeRule.onNodeWithTag("background_blurred_photo").performScrollTo().performClick().assertIsNotSelected()
        composeRule.runOnIdle {
            assertEquals("blurred_photo", requested)
            state = state.copy(backgroundStyle = requested!!)
        }
        composeRule.onNodeWithTag("background_blurred_photo").assertIsSelected()
        composeRule.onNodeWithTag("background_random_rectangles").assertIsNotSelected()
        composeRule.runOnIdle { saving = true }
        composeRule.onNodeWithTag("background_blurred_photo").assertIsNotEnabled()
    }

    private fun verifyPreviewButton(darkTheme: Boolean) {
        var previewRequests = 0
        composeRule.setContent {
            AWallTheme(darkTheme = darkTheme) {
                MainScreen(onOpenWallpaperPreview = { previewRequests++ })
            }
        }
        composeRule.onNodeWithText("AWell").assertIsDisplayed()
        composeRule.onNodeWithText("设置动态壁纸").performScrollTo().assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, previewRequests) }
    }
}
