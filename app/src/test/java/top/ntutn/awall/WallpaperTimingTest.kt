package top.ntutn.awall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.ntutn.awall.photos.WallpaperState
import top.ntutn.awall.photos.timerRemainingMillis

class WallpaperTimingTest {
    private val state = WallpaperState(timerEnabled = true, timerBaseAtMillis = 1_000L, intervalMillis = 5_000L)

    @Test fun disabledTimerDoesNotSchedule() {
        assertNull(timerRemainingMillis(state.copy(timerEnabled = false), 10_000L))
    }

    @Test fun deadlineIsInclusiveAndMissedPeriodsCollapseToOneDueResult() {
        assertEquals(1L, timerRemainingMillis(state, 5_999L))
        assertEquals(0L, timerRemainingMillis(state, 6_000L))
        assertEquals(0L, timerRemainingMillis(state, 60_000L))
    }

    @Test fun restartKeepsOnlyTheRemainingInterval() {
        assertEquals(3_000L, timerRemainingMillis(state, 3_000L))
    }

    @Test fun missingOrFutureBaseUsesAFullIntervalUntilItIsRepaired() {
        assertEquals(5_000L, timerRemainingMillis(state.copy(timerBaseAtMillis = null), 3_000L))
        assertEquals(5_000L, timerRemainingMillis(state, 500L))
    }
}
