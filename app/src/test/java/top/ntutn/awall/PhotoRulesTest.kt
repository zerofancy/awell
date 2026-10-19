package top.ntutn.awall

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.ntutn.awall.photos.DocumentEntry
import top.ntutn.awall.photos.RequestVersion
import top.ntutn.awall.photos.photoCandidates
import top.ntutn.awall.photos.scanPhotoTree
import java.io.IOException
import kotlin.random.Random

class PhotoRulesTest {
    @Test fun selectionExcludesCurrentAndDeduplicates() {
        repeat(100) { seed ->
            val result = photoCandidates(listOf("a", "a", "b", "c"), "a", Random(seed))
            assertEquals(setOf("b", "c"), result.toSet())
            assertEquals(2, result.size)
        }
        assertEquals(listOf("a"), photoCandidates(listOf("a", "a"), "a"))
        assertTrue(photoCandidates(emptyList<String>(), null).isEmpty())
    }

    @Test fun allPhotosParticipateRegardlessOfDirectorySize() {
        val counts = mutableMapOf<String, Int>()
        repeat(3000) { seed ->
            val photo = photoCandidates(listOf("root", "small/a", "large/b", "large/c"), null, Random(seed)).first()
            counts[photo] = (counts[photo] ?: 0) + 1
        }
        assertEquals(4, counts.size)
        assertTrue(counts.values.all { it in 550..950 })
    }

    @Test fun recursiveTraversalHandlesDeepTreesCyclesAndDuplicateDocuments() = runBlocking {
        val seen = mutableSetOf<String>()
        val result = scanPhotoTree("0") { id ->
            assertTrue("Directory visited twice", seen.add(id))
            val depth = id.toInt()
            if (depth < 10_000) listOf(dir((depth + 1).toString()), dir("0"), image("shared"))
            else listOf(image("nested"), DocumentEntry("video", "a.mp4", "video/mp4"))
        }
        assertEquals(setOf("shared", "nested"), result.photos.map { it.id }.toSet())
        assertEquals(10_001, seen.size)
        assertFalse(result.partial)
    }

    @Test fun unreadableSubdirectoryDoesNotRejectOtherPhotos() = runBlocking {
        val result = scanPhotoTree("root") { id ->
            when (id) {
                "root" -> listOf(dir("bad"), dir("good"))
                "bad" -> throw SecurityException()
                else -> listOf(image("photo"))
            }
        }
        assertTrue(result.partial)
        assertEquals(listOf("photo"), result.photos.map { it.id })
    }

    @Test fun rootFailureIsNotReportedAsEmptyDirectory() = runBlocking {
        try {
            scanPhotoTree("root") { throw IOException("unavailable") }
            fail("Must propagate root error")
        } catch (_: IOException) { }
    }

    @Test fun cancellationDoesNotReturnTruncatedCandidates() = runBlocking {
        try {
            scanPhotoTree("root") { id ->
                if (id == "root") listOf(image("photo"), dir("child")) else throw CancellationException()
            }
            fail("Must propagate cancellation")
        } catch (_: CancellationException) { }
    }

    @Test fun filtersVirtualFilesAndUsesExtensionOnlyForMissingOrGenericMime() {
        assertTrue(DocumentEntry("1", "photo.JPG", null).isPhoto)
        assertTrue(DocumentEntry("2", "photo", "image/webp").isPhoto)
        assertFalse(DocumentEntry("3", "photo.jpg", "video/mp4").isPhoto)
        assertFalse(DocumentEntry("4", "photo.jpg", "image/jpeg", virtual = true).isPhoto)
        assertFalse(dir("photos.jpg").isPhoto)
    }

    @Test fun supersededResultsCannotCommit() {
        val versions = RequestVersion()
        val first = versions.next()
        assertTrue(versions.isCurrent(first))
        val second = versions.next()
        assertFalse(versions.isCurrent(first))
        assertTrue(versions.isCurrent(second))
    }

    private fun dir(id: String) = DocumentEntry(id, id, "vnd.android.document/directory")
    private fun image(id: String) = DocumentEntry(id, "$id.jpg", "image/jpeg")
}
