package top.ntutn.awall.photos

import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A bounded pool keeps a stalled document provider from creating unlimited workers. */
internal object PhotoIo {
    private val executor = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(8), { task -> Thread(task, "photo-io").apply { isDaemon = true } })

    suspend fun <T> run(discard: (T) -> Unit = {}, block: (CancellationSignal) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            val task = executor.submit {
                try {
                    val value = block(signal)
                    continuation.resume(value, onCancellation = { _, result, _ -> discard(result) })
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                }
            }
            continuation.invokeOnCancellation {
                signal.cancel()
                task.cancel(true)
            }
        }
}
