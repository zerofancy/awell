package top.ntutn.awall.photos

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

val PHOTO_TIMEOUT = 5.seconds

data class PhotoDirectory(val uri: Uri, val name: String, val revision: Long = 0)
enum class DirectoryStatus { UNSELECTED, SCANNING, READY, EMPTY, INACCESSIBLE, RETRY, UNDECODABLE }
data class DirectoryUiState(
    val directory: PhotoDirectory? = null,
    val status: DirectoryStatus = DirectoryStatus.UNSELECTED,
    val count: Int = 0,
    val partial: Boolean = false,
)
data class DirectoryPhotos(val uris: List<Uri>, val partial: Boolean)

interface WallpaperPhotoSource {
    val directory: StateFlow<PhotoDirectory?>
    val state: StateFlow<WallpaperState?>
    suspend fun scan(tree: Uri): DirectoryPhotos
    suspend fun <T> withDirectory(tree: Uri, block: suspend () -> T): T
    fun report(tree: Uri?, status: DirectoryStatus, count: Int = 0, partial: Boolean = false)
    suspend fun updateState(transform: (WallpaperState) -> WallpaperState): WallpaperState
}

class PhotoDirectoryRepository internal constructor(
    context: Context,
    private val dataStore: DataStore<WallpaperState> = createWallpaperDataStore(context),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : WallpaperPhotoSource {
    private val resolver = context.contentResolver
    private val activeReaders = mutableMapOf<Uri, Int>()
    private val obsoleteGrants = mutableSetOf<Uri>()
    private val loadRequest = MutableStateFlow(0L)
    private val observation = MutableStateFlow<StorageObservation?>(null)
    private val mutableState = MutableStateFlow<WallpaperState?>(null)
    override val state = mutableState.asStateFlow()
    private val mutableStorageError = MutableStateFlow(false)
    val storageError = mutableStorageError.asStateFlow()
    private val mutableDirectory = MutableStateFlow<PhotoDirectory?>(null)
    override val directory = mutableDirectory.asStateFlow()
    private val mutableUi = MutableStateFlow(DirectoryUiState())
    val ui = mutableUi.asStateFlow()

    init {
        scope.launch {
            loadRequest.collectLatest { request ->
                try {
                    dataStore.data.collect { saved -> observeState(request, saved) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    mutableStorageError.value = true
                    observation.value = StorageObservation(request, failure = error)
                }
            }
        }.invokeOnCompletion { cause ->
            observation.value = StorageObservation(null, failure = cause ?: CancellationException("Repository closed"))
        }
    }

    override suspend fun updateState(transform: (WallpaperState) -> WallpaperState): WallpaperState {
        val saved = try {
            dataStore.updateData { transform(it) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableStorageError.value = true
            throw error
        }
        // A successful no-op can recover storage without causing a new DataStore emission.
        if (storageError.value) requestReload()
        return saved
    }

    suspend fun retryLoading() = awaitObservation(requestReload())

    private fun requestReload(): Long = loadRequest.updateAndGet { request ->
        if (observation.value?.request == request) request + 1 else request
    }

    private suspend fun awaitObservation(request: Long, minimumDirectoryRevision: Long = 0) {
        val result = observation.filterNotNull().first {
            it.request == null ||
                (it.request >= request && (it.failure != null || it.directoryRevision >= minimumDirectoryRevision))
        }
        result.failure?.let { throw it }
    }

    private fun observeState(request: Long, saved: WallpaperState) {
        val source = saved.directoryUri?.let {
            PhotoDirectory(it.toUri(), saved.directoryName ?: "照片目录", saved.directoryRevision)
        }
        val directoryChanged = directory.value != source
        val recoveredRead = observation.value?.failure != null
        if (directoryChanged) {
            mutableDirectory.value = source
            mutableUi.value = DirectoryUiState(source,
                if (source == null) DirectoryStatus.UNSELECTED else DirectoryStatus.SCANNING)
        }
        mutableState.value = saved
        mutableStorageError.value = false
        observation.value = StorageObservation(request, saved.directoryRevision)
        if (directoryChanged || recoveredRead) synchronized(activeReaders) { releaseRetired() }
    }

    private data class StorageObservation(
        val request: Long?,
        val directoryRevision: Long = 0,
        val failure: Throwable? = null,
    )

    suspend fun setBackgroundStyle(style: String) {
        require(style == BackgroundStyle.normalize(style))
        updateState { it.copy(backgroundStyle = style) }
    }

    suspend fun setNightDimmingEnabled(enabled: Boolean) {
        updateState { it.copy(nightDimmingEnabled = enabled) }
    }

    suspend fun setUnlockEnabled(enabled: Boolean) {
        updateState { it.copy(unlockEnabled = enabled) }
    }

    suspend fun setTimerEnabled(enabled: Boolean) {
        updateState {
            if (it.timerEnabled == enabled) it else it.copy(timerEnabled = enabled,
                timerBaseAtMillis = if (enabled) nowMillis() else null)
        }
    }

    suspend fun setInterval(interval: Duration) {
        require(interval in SUPPORTED_INTERVALS) { "Unsupported wallpaper interval" }
        updateState { it.copy(intervalMillis = interval.inWholeMilliseconds) }
    }

    /** Existing readers keep their old grant until they have exited. */
    suspend fun select(uri: Uri, resultFlags: Int) {
        if (state.value == null) retryLoading()
        withDirectory(uri) {
            val alreadyGranted = hasReadGrant(uri)
            var persisted = false
            try {
                require(resultFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { "Missing read grant" }
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val name = withTimeout(PHOTO_TIMEOUT) { readRootName(uri) }
                // Keep the new grant until the repository has observed the durable selection.
                withContext(NonCancellable) {
                    val selectedUri = uri.toString()
                    var previousUri: String? = null
                    val saved = updateState { current ->
                        previousUri = current.directoryUri
                        current.copy(
                            directoryUri = selectedUri,
                            directoryName = name,
                            currentPhotoUri = null,
                            // Selecting the same directory still invalidates any in-flight reads.
                            directoryRevision = current.directoryRevision + 1,
                        )
                    }
                    persisted = true
                    try {
                        awaitObservation(loadRequest.value, saved.directoryRevision)
                    } finally {
                        previousUri?.takeIf { it != selectedUri }?.let { retire(it.toUri()) }
                    }
                }
            } catch (error: Exception) {
                if (!persisted && !alreadyGranted && directory.value?.uri != uri) retire(uri)
                throw error
            }
        }
    }

    private suspend fun readRootName(tree: Uri): String = PhotoIo.run { signal ->
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        resolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null, signal)?.use { cursor ->
            if (!cursor.moveToFirst()) throw FileNotFoundException("Directory missing")
            if (cursor.getString(1) != DocumentsContract.Document.MIME_TYPE_DIR) throw IOException("Not a directory")
            cursor.getString(0) ?: "照片目录"
        } ?: throw IOException("Directory unavailable")
    }

    fun hasReadGrant(uri: Uri): Boolean = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    override suspend fun <T> withDirectory(tree: Uri, block: suspend () -> T): T {
        synchronized(activeReaders) { activeReaders[tree] = (activeReaders[tree] ?: 0) + 1 }
        return try { block() } finally {
            synchronized(activeReaders) {
                val remaining = (activeReaders[tree] ?: 1) - 1
                if (remaining == 0) activeReaders.remove(tree) else activeReaders[tree] = remaining
                releaseRetired()
            }
        }
    }

    private fun retire(uri: Uri) = synchronized(activeReaders) {
        obsoleteGrants.add(uri)
        releaseRetired()
    }

    private fun releaseRetired() {
        // A durable selection may be newer than the displayed directory after a read failure.
        if (observation.value?.failure != null) return
        val unused = obsoleteGrants.filter { it != directory.value?.uri && it !in activeReaders }
        unused.forEach { uri ->
            try { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* Already revoked. */ }
            obsoleteGrants.remove(uri)
        }
    }

    override suspend fun scan(tree: Uri): DirectoryPhotos {
        if (state.value == null) retryLoading()
        if (!hasReadGrant(tree)) throw SecurityException("Directory grant missing")
        readRootName(tree)
        val snapshot = scanPhotoTree(DocumentsContract.getTreeDocumentId(tree)) { id ->
            PhotoIo.run { signal ->
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
                resolver.query(children, PROJECTION, null, null, null, signal)?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            signal.throwIfCanceled()
                            add(DocumentEntry(cursor.getString(0), cursor.getString(1).orEmpty(),
                                cursor.getString(2),
                                cursor.getInt(3) and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0))
                        }
                    }
                } ?: throw IOException("Directory listing unavailable")
            }
        }
        return DirectoryPhotos(snapshot.photos.map { DocumentsContract.buildDocumentUriUsingTree(tree, it.id) }, snapshot.partial)
    }

    override fun report(tree: Uri?, status: DirectoryStatus, count: Int, partial: Boolean) {
        if (directory.value?.uri == tree) mutableUi.value = DirectoryUiState(directory.value, status, count, partial)
    }

    suspend fun refresh() {
        if (state.value == null || storageError.value) {
            try { retryLoading() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { return }
        }
        val source = directory.value ?: return
        val tree = source.uri
        fun reportScan(status: DirectoryStatus, count: Int = 0, partial: Boolean = false) {
            if (directory.value == source) mutableUi.value = DirectoryUiState(source, status, count, partial)
        }
        reportScan(DirectoryStatus.SCANNING)
        try {
            val result = withTimeout(PHOTO_TIMEOUT) { withDirectory(tree) { scan(tree) } }
            reportScan(if (result.uris.isEmpty()) DirectoryStatus.EMPTY else DirectoryStatus.READY,
                result.uris.size, result.partial)
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            reportScan(DirectoryStatus.RETRY)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: SecurityException) {
            reportScan(DirectoryStatus.INACCESSIBLE)
        } catch (_: FileNotFoundException) {
            reportScan(DirectoryStatus.INACCESSIBLE)
        } catch (_: Exception) {
            reportScan(DirectoryStatus.RETRY)
        }
    }

    companion object {
        private val PROJECTION = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS)
        @Volatile private var instance: PhotoDirectoryRepository? = null
        fun get(context: Context): PhotoDirectoryRepository = instance ?: synchronized(this) {
            instance ?: PhotoDirectoryRepository(context.applicationContext).also { instance = it }
        }
    }
}
