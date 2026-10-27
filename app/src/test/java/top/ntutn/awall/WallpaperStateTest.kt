package top.ntutn.awall

import androidx.datastore.core.CorruptionException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.ntutn.awall.photos.DEFAULT_INTERVAL_MILLIS
import top.ntutn.awall.photos.WallpaperState
import top.ntutn.awall.photos.WallpaperStateSerializer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class WallpaperStateTest {
    @Test fun roundTripKeepsPhotoAndTimerTogether() = runBlocking {
        val state = WallpaperState(directoryUri = "content://photos/tree/root", directoryName = "Photos",
            currentPhotoUri = "content://photos/document/photo", unlockEnabled = false,
            timerEnabled = true, timerBaseAtMillis = 123_456)
        val output = ByteArrayOutputStream()
        WallpaperStateSerializer.writeTo(state, output)
        assertEquals(state, WallpaperStateSerializer.readFrom(ByteArrayInputStream(output.toByteArray())))
    }

    @Test fun missingAndUnknownFieldsRemainCompatible() = runBlocking {
        val state = decode("""{"directoryUri":"content://photos/root","futureField":true}""")
        assertEquals("content://photos/root", state.directoryUri)
        assertTrue(state.unlockEnabled)
        assertFalse(state.timerEnabled)
        assertNull(state.timerBaseAtMillis)
        assertEquals(DEFAULT_INTERVAL_MILLIS, state.intervalMillis)
    }

    @Test fun unsupportedIntervalFallsBackWithoutDiscardingPhoto() = runBlocking {
        val state = decode("""{"intervalMillis":0,"currentPhotoUri":"content://photos/a"}""")
        assertEquals(DEFAULT_INTERVAL_MILLIS, state.intervalMillis)
        assertEquals("content://photos/a", state.currentPhotoUri)
    }

    @Test fun malformedJsonIsCorruptionRatherThanAnEmptyState() = runBlocking {
        try {
            decode("{broken")
            fail("Malformed JSON must not become default state")
        } catch (_: CorruptionException) { }
    }

    private suspend fun decode(json: String): WallpaperState =
        WallpaperStateSerializer.readFrom(ByteArrayInputStream(json.encodeToByteArray()))
}
