package top.ntutn.awall.photos

import kotlin.random.Random

/** All directories contribute to one pool; ordering is randomized without replacement. */
fun <T> photoCandidates(items: List<T>, current: T?, random: Random = Random.Default): List<T> {
    val unique = items.distinct()
    return (if (unique.size > 1) unique.filterNot { it == current } else unique).shuffled(random)
}

data class DocumentEntry(val id: String, val name: String, val mime: String?, val virtual: Boolean = false) {
    val isDirectory: Boolean get() = mime == "vnd.android.document/directory"
    val isPhoto: Boolean get() = !virtual && !isDirectory && (
        mime?.startsWith("image/") == true ||
            ((mime.isNullOrEmpty() || mime == "application/octet-stream") &&
                name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS)
        )

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "gif", "bmp")
    }
}

data class TreeSnapshot(val photos: List<DocumentEntry>, val partial: Boolean)

/** Iterative traversal avoids stack overflow and document-provider cycles. */
suspend fun scanPhotoTree(rootId: String, children: suspend (String) -> List<DocumentEntry>): TreeSnapshot {
    val pending = ArrayDeque<String>()
    val visited = mutableSetOf<String>()
    val photos = linkedMapOf<String, DocumentEntry>()
    var partial = false
    pending.add(rootId)
    while (pending.isNotEmpty()) {
        val id = pending.removeFirst()
        if (!visited.add(id)) continue
        val entries = try {
            children(id)
        } catch (error: java.io.IOException) {
            if (id == rootId) throw error
            partial = true
            continue
        } catch (error: SecurityException) {
            if (id == rootId) throw error
            partial = true
            continue
        }
        entries.forEach { entry ->
            if (entry.isDirectory && !entry.virtual) pending.add(entry.id)
            else if (entry.isPhoto && entry.id !in photos) photos[entry.id] = entry
        }
    }
    return TreeSnapshot(photos.values.toList(), partial)
}

/** Tokens also guard work that ignores coroutine cancellation inside a provider. */
class RequestVersion {
    @Volatile private var value = 0L
    fun next(): Long = ++value
    fun isCurrent(token: Long): Boolean = value == token
}
