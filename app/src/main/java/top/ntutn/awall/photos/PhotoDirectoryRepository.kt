package top.ntutn.awall.photos

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.datastore.core.DataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.FileNotFoundException
import java.io.IOException

const val PHOTO_TIMEOUT_MS = 5_000L

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
    private val updates = Mutex()
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
            try { retryLoading() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* storageError exposes a retryable loading failure. */ }
        }
    }

    /** All writes go through this repository; this Flow mirrors only confirmed DataStore values. */
    override suspend fun updateState(transform: (WallpaperState) -> WallpaperState): WallpaperState =
        updates.withLock {
            try {
                dataStore.updateData { transform(it) }.also(::acceptState)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                mutableStorageError.value = true
                throw error
            }
        }

    suspend fun retryLoading() = updates.withLock {
        try { acceptState(dataStore.data.first()) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            mutableStorageError.value = true
            throw error
        }
    }

    private fun acceptState(saved: WallpaperState) {
        val oldDirectory = directory.value
        mutableState.value = saved
        mutableStorageError.value = false
        val uri = saved.directoryUri?.let(Uri::parse)
        val name = saved.directoryName ?: "照片目录"
        if (oldDirectory?.uri != uri || (uri != null && oldDirectory?.name != name)) {
            mutableDirectory.value = uri?.let { PhotoDirectory(it, name, (oldDirectory?.revision ?: 0) + 1) }
            mutableUi.value = DirectoryUiState(directory.value,
                if (uri == null) DirectoryStatus.UNSELECTED else DirectoryStatus.SCANNING)
        }
    }

    suspend fun setBackgroundStyle(style: String) {
        require(style == BackgroundStyle.normalize(style))
        updateState { it.copy(backgroundStyle = style) }
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

    suspend fun setIntervalMillis(interval: Long) {
        require(interval in SUPPORTED_INTERVALS_MILLIS) { "Unsupported wallpaper interval" }
        updateState { it.copy(intervalMillis = interval) }
    }

    /** Call on the main thread. Existing readers keep their old grant until they have exited. */
    suspend fun select(uri: Uri, resultFlags: Int) {
        if (state.value == null) retryLoading()
        val old = directory.value
        val alreadyGranted = hasReadGrant(uri)
        try {
            require(resultFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { "Missing read grant" }
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val name = withTimeout(PHOTO_TIMEOUT_MS) { readRootName(uri) }
            // Once persistence starts, finish the in-memory transition even if the Activity closes.
            withContext(NonCancellable) {
                updateState {
                    it.copy(directoryUri = uri.toString(), directoryName = name, currentPhotoUri = null)
                }
                // StateFlow does not emit equal values; reselecting the same URI must invalidate readers too.
                if (old?.uri == uri) mutableDirectory.value = directory.value!!.copy(
                    revision = directory.value!!.revision + 1)
                mutableUi.value = DirectoryUiState(directory.value, DirectoryStatus.SCANNING)
                if (old != null && old.uri != uri) retire(old.uri)
            }
        } catch (error: Exception) {
            if (!alreadyGranted && directory.value?.uri != uri) retire(uri)
            throw error
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
                                cursor.getString(2), Build.VERSION.SDK_INT >= 24 &&
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
        val tree = directory.value?.uri ?: return
        report(tree, DirectoryStatus.SCANNING)
        try {
            val result = withTimeout(PHOTO_TIMEOUT_MS) { withDirectory(tree) { scan(tree) } }
            report(tree, if (result.uris.isEmpty()) DirectoryStatus.EMPTY else DirectoryStatus.READY,
                result.uris.size, result.partial)
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            report(tree, DirectoryStatus.RETRY)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: SecurityException) {
            report(tree, DirectoryStatus.INACCESSIBLE)
        } catch (_: FileNotFoundException) {
            report(tree, DirectoryStatus.INACCESSIBLE)
        } catch (_: Exception) {
            report(tree, DirectoryStatus.RETRY)
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
