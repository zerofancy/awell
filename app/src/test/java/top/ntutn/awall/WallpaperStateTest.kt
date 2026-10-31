package top.ntutn.awall

import androidx.datastore.core.CorruptionException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.ntutn.awall.photos.DEFAULT_INTERVAL
import top.ntutn.awall.photos.WallpaperState
import top.ntutn.awall.photos.WallpaperStateSerializer

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
        assertEquals(DEFAULT_INTERVAL, state.interval)
    }

    @Test fun unsupportedIntervalFallsBackWithoutDiscardingPhoto() = runBlocking {
        val state = decode("""{"intervalMillis":0,"currentPhotoUri":"content://photos/a"}""")
        assertEquals(DEFAULT_INTERVAL, state.interval)
        assertEquals("content://photos/a", state.currentPhotoUri)
    }

    @Test fun malformedJsonIsCorruptionRatherThanAnEmptyState() = runBlocking {
        try {
            decode("{broken")
            fail("Malformed JSON must not become default state")
        } catch (_: CorruptionException) { }
    }

    @Test fun backgroundStyleDefaultsAndUnknownValuesPreserveOtherSettings() = runBlocking {
        assertEquals("random_rectangles", decode("{}").backgroundStyle)
        val old = decode("""{"backgroundStyle":"future","currentPhotoUri":"content://photos/a","timerEnabled":true,"timerBaseAtMillis":42}""")
        assertEquals("random_rectangles", old.backgroundStyle)
        assertEquals("content://photos/a", old.currentPhotoUri)
        assertEquals(42L, old.timerBaseAtMillis)
        assertTrue(old.timerEnabled)
        val state = old.copy(backgroundStyle = "blurred_photo")
        val output = ByteArrayOutputStream()
        WallpaperStateSerializer.writeTo(state, output)
        assertEquals(state, WallpaperStateSerializer.readFrom(ByteArrayInputStream(output.toByteArray())))
    }

    @Test fun typedIntervalKeepsTheExistingMillisecondJsonFormat() = runBlocking {
        val state = decode("""{"intervalMillis":900000,"timerBaseAtMillis":123456}""")
        assertEquals(15.minutes, state.interval)
        val output = ByteArrayOutputStream()
        WallpaperStateSerializer.writeTo(state, output)
        val json = output.toString("UTF-8")
        assertTrue(json.contains("\"intervalMillis\":900000"))
        assertFalse(json.contains("\"interval\":"))
        assertEquals(123456L, decode(json).timerBaseAtMillis)
    }

    private suspend fun decode(json: String): WallpaperState =
        WallpaperStateSerializer.readFrom(ByteArrayInputStream(json.encodeToByteArray()))
}
