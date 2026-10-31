package top.ntutn.awall.photos

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.migrations.SharedPreferencesMigration
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

val DEFAULT_INTERVAL = 1.hours
val SUPPORTED_INTERVALS = listOf(15.minutes, 30.minutes, DEFAULT_INTERVAL, 6.hours, 24.hours)

@Serializable
data class WallpaperState(
    val backgroundStyle: String = BackgroundStyle.RANDOM_RECTANGLES,
    val directoryUri: String? = null,
    val directoryName: String? = null,
    val currentPhotoUri: String? = null,
    val unlockEnabled: Boolean = true,
    val timerEnabled: Boolean = false,
    val intervalMillis: Long = DEFAULT_INTERVAL.inWholeMilliseconds,
    val timerBaseAtMillis: Long? = null,
) {
    // Preserve the existing JSON field; business code uses the typed duration.
    val interval: Duration get() = intervalMillis.milliseconds
}

object WallpaperStateSerializer : Serializer<WallpaperState> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    override val defaultValue = WallpaperState()

    override suspend fun readFrom(input: InputStream): WallpaperState = try {
        val state = json.decodeFromString<WallpaperState>(input.readBytes().decodeToString())
        state.copy(
            backgroundStyle = BackgroundStyle.normalize(state.backgroundStyle),
            intervalMillis = (state.interval.takeIf { it in SUPPORTED_INTERVALS } ?: DEFAULT_INTERVAL).inWholeMilliseconds,
        )
    } catch (error: SerializationException) {
        throw CorruptionException("Cannot decode wallpaper settings", error)
    }

    override suspend fun writeTo(t: WallpaperState, output: OutputStream) {
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}

/** Production calls this once through PhotoDirectoryRepository.get; tests use separate files. */
internal fun createWallpaperDataStore(
    context: Context,
    file: File = File(context.noBackupFilesDir, "wallpaper_state.json"),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    legacyPreferencesName: String = "photo_directory",
): DataStore<WallpaperState> = DataStoreFactory.create(
    serializer = WallpaperStateSerializer,
    migrations = listOf(SharedPreferencesMigration<WallpaperState>(
        context, legacyPreferencesName, setOf("tree_uri", "tree_name", "last_photo"),
    ) { old, current ->
        // A completed new record wins if migration cleanup needs to be retried.
        if (current != WallpaperState()) current else {
            val directory = old.getString("tree_uri", null)
            current.copy(
                directoryUri = directory,
                directoryName = old.getString("tree_name", null),
                currentPhotoUri = if (directory != null) old.getString("last_photo", null) else null,
            )
        }
    }),
    scope = scope,
    produceFile = { file },
)
