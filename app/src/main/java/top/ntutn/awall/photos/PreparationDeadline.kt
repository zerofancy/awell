package top.ntutn.awall.photos

import android.os.SystemClock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** One shared deadline on Android's elapsedRealtime clock; never confused with epoch timestamps. */
@JvmInline
value class PreparationDeadline private constructor(private val elapsedRealtimeMillis: Long) {
    fun remaining(nowElapsedRealtimeMillis: Long = SystemClock.elapsedRealtime()): Duration =
        (elapsedRealtimeMillis - nowElapsedRealtimeMillis).milliseconds.coerceAtLeast(Duration.ZERO)

    companion object {
        fun after(timeout: Duration, nowElapsedRealtimeMillis: Long = SystemClock.elapsedRealtime()): PreparationDeadline {
            require(timeout.isFinite() && timeout >= Duration.ZERO) { "Expected a finite non-negative timeout" }
            return PreparationDeadline(nowElapsedRealtimeMillis + timeout.inWholeMilliseconds)
        }
    }
}
