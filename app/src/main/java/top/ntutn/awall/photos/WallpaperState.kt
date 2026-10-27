package top.ntutn.awall.photos

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.migrations.SharedPreferencesMigration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

const val DEFAULT_INTERVAL_MILLIS = 60 * 60 * 1_000L
val SUPPORTED_INTERVALS_MILLIS = listOf(15 * 60 * 1_000L, 30 * 60 * 1_000L,
    DEFAULT_INTERVAL_MILLIS, 6 * DEFAULT_INTERVAL_MILLIS, 24 * DEFAULT_INTERVAL_MILLIS)

@Serializable
data class WallpaperState(
    val directoryUri: String? = null,
    val directoryName: String? = null,
    val currentPhotoUri: String? = null,
    val unlockEnabled: Boolean = true,
    val timerEnabled: Boolean = false,
    val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    val timerBaseAtMillis: Long? = null,
)

object WallpaperStateSerializer : Serializer<WallpaperState> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    override val defaultValue = WallpaperState()

    override suspend fun readFrom(input: InputStream): WallpaperState = try {
        val state = json.decodeFromString<WallpaperState>(input.readBytes().decodeToString())
        if (state.intervalMillis in SUPPORTED_INTERVALS_MILLIS) state
        else state.copy(intervalMillis = DEFAULT_INTERVAL_MILLIS)
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
