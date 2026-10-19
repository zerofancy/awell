package top.ntutn.awall.photos

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.NonCancellable
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
    suspend fun scan(tree: Uri): DirectoryPhotos
    suspend fun <T> withDirectory(tree: Uri, block: suspend () -> T): T
    fun report(tree: Uri?, status: DirectoryStatus, count: Int = 0, partial: Boolean = false)
    fun lastPhoto(): Uri?
    fun saveLastPhoto(tree: Uri?, photo: Uri?)
}

class PhotoDirectoryRepository internal constructor(
    context: Context,
    private val preferences: SharedPreferences = context.getSharedPreferences("photo_directory", Context.MODE_PRIVATE),
) : WallpaperPhotoSource {
    private val resolver = context.contentResolver
    private val activeReaders = mutableMapOf<Uri, Int>()
    private val obsoleteGrants = mutableSetOf<Uri>()
    private val initial = preferences.getString("tree_uri", null)?.let {
        PhotoDirectory(Uri.parse(it), preferences.getString("tree_name", null) ?: "照片目录")
    }
    private val mutableDirectory = MutableStateFlow(initial)
    override val directory = mutableDirectory.asStateFlow()
    private val mutableUi = MutableStateFlow(DirectoryUiState(initial,
        if (initial == null) DirectoryStatus.UNSELECTED else DirectoryStatus.SCANNING))
    val ui = mutableUi.asStateFlow()

    /** Call on the main thread. Existing readers keep their old grant until they have exited. */
    suspend fun select(uri: Uri, resultFlags: Int) {
        val old = directory.value
        val alreadyGranted = hasReadGrant(uri)
        try {
            require(resultFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { "Missing read grant" }
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val name = withTimeout(PHOTO_TIMEOUT_MS) { readRootName(uri) }
            // Once persistence starts, finish the in-memory transition even if the Activity closes.
            withContext(NonCancellable) {
                val saved = PhotoIo.run {
                    preferences.edit().putString("tree_uri", uri.toString())
                        .putString("tree_name", name).remove("last_photo").commit()
                }
                if (!saved) throw IOException("Cannot save directory")
                mutableDirectory.value = PhotoDirectory(uri, name, (old?.revision ?: 0) + 1)
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

    override fun lastPhoto(): Uri? = preferences.getString("last_photo", null)?.let(Uri::parse)
    override fun saveLastPhoto(tree: Uri?, photo: Uri?) {
        if (directory.value?.uri == tree) preferences.edit().putString("last_photo", photo?.toString()).apply()
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
