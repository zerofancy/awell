package top.ntutn.awall

import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.ntutn.awall.photos.WallpaperState
import top.ntutn.awall.photos.timerRemaining

class WallpaperTimingTest {
    private val state = WallpaperState(timerEnabled = true, timerBaseAtMillis = 1_000L, intervalMillis = 5_000L)

    @Test fun disabledTimerDoesNotSchedule() {
        assertNull(timerRemaining(state.copy(timerEnabled = false), 10_000L))
    }

    @Test fun deadlineIsInclusiveAndMissedPeriodsCollapseToOneDueResult() {
        assertEquals(1.milliseconds, timerRemaining(state, 5_999L))
        assertEquals(0.milliseconds, timerRemaining(state, 6_000L))
        assertEquals(0.milliseconds, timerRemaining(state, 60_000L))
    }

    @Test fun restartKeepsOnlyTheRemainingInterval() {
        assertEquals(3_000.milliseconds, timerRemaining(state, 3_000L))
    }

    @Test fun missingOrFutureBaseUsesAFullIntervalUntilItIsRepaired() {
        assertEquals(5_000.milliseconds, timerRemaining(state.copy(timerBaseAtMillis = null), 3_000L))
        assertEquals(5_000.milliseconds, timerRemaining(state, 500L))
    }
}
