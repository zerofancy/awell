package top.ntutn.awall

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import top.ntutn.awall.photos.PreparationDeadline

class PreparationDeadlineTest {
    @Test fun sharedDeadlineConsumesOneBudgetAcrossPreparationStages() {
        val deadline = PreparationDeadline.after(5.seconds, nowElapsedRealtimeMillis = 1_000)
        assertEquals(5.seconds, deadline.remaining(nowElapsedRealtimeMillis = 1_000))
        assertEquals(2_500.milliseconds, deadline.remaining(nowElapsedRealtimeMillis = 3_500))
        assertEquals(Duration.ZERO, deadline.remaining(nowElapsedRealtimeMillis = 6_000))
        assertEquals(Duration.ZERO, deadline.remaining(nowElapsedRealtimeMillis = 10_000))
    }

    @Test fun invalidTimeoutsAreRejectedRatherThanBecomingClockReadings() {
        assertThrows(IllegalArgumentException::class.java) {
            PreparationDeadline.after(Duration.INFINITE, nowElapsedRealtimeMillis = 1_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PreparationDeadline.after((-1).seconds, nowElapsedRealtimeMillis = 1_000)
        }
    }
}
