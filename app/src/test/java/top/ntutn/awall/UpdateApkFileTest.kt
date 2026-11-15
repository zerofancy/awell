package top.ntutn.awall

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.ntutn.awall.update.requireUpdateApk

class UpdateApkFileTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun acceptsNonemptyApkInDownloadTask() {
        val root = temporary.newFolder("kmp-updater")
        val task = File(root, "task-test").apply { mkdir() }
        val apk = File(task, "update.apk").apply { writeText("apk") }
        assertEquals(apk.canonicalFile, requireUpdateApk(root, apk.path))
    }

    @Test
    fun rejectsOutsideDirectoryAndSiblingWithSamePrefix() {
        val root = temporary.newFolder("kmp-updater")
        val sibling = temporary.newFolder("kmp-updater-other")
        val apk = File(sibling, "update.apk").apply { writeText("apk") }
        assertThrows(IllegalArgumentException::class.java) { requireUpdateApk(root, apk.path) }
        assertThrows(IllegalArgumentException::class.java) {
            requireUpdateApk(root, File(root, "../kmp-updater-other/update.apk").path)
        }
    }

    @Test
    fun rejectsSymbolicLinkEscapingUpdateDirectory() {
        val root = temporary.newFolder("kmp-updater")
        val outside = temporary.newFile("outside.apk").apply { writeText("apk") }
        val link = File(root, "update.apk")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { requireUpdateApk(root, link.path) }
    }

    @Test
    fun rejectsMissingEmptyDirectoryAndTemporaryFiles() {
        val root = temporary.newFolder("kmp-updater")
        val empty = File(root, "empty.apk").apply { createNewFile() }
        val part = File(root, "payload.part").apply { writeText("partial") }
        val directory = File(root, "folder.apk").apply { mkdir() }
        listOf(File(root, "missing.apk"), empty, part, directory).forEach { file ->
            assertThrows(IllegalArgumentException::class.java) { requireUpdateApk(root, file.path) }
        }
    }
}
