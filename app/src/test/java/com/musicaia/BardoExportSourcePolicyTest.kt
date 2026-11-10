package com.musicaia

import com.musicaia.storage.BardoExportSourcePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BardoExportSourcePolicyTest {
    @Test fun allowsOnlyRegularFilesInsideBardoDeveloperWorkspace() {
        val root = Files.createTempDirectory("bardo-export-policy").toFile()
        try {
            val workspace = File(root, "bardo_developer").apply { mkdirs() }
            val allowed = File(workspace, "report.md").apply { writeText("BARDO report") }
            val outside = File(root, "private-token-backup.txt").apply { writeText("not exportable") }

            assertTrue(BardoExportSourcePolicy.allows(workspace, allowed))
            assertFalse(BardoExportSourcePolicy.allows(workspace, outside))
            assertFalse(BardoExportSourcePolicy.allows(workspace, File(workspace, "missing.md")))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun rejectsSymbolicLinksAsExportSources() {
        val root = Files.createTempDirectory("bardo-export-symlink").toFile()
        try {
            val workspace = File(root, "bardo_developer").apply { mkdirs() }
            val outside = File(root, "outside.txt").apply { writeText("private") }
            val link = File(workspace, "linked.txt")
            Files.createSymbolicLink(link.toPath(), outside.toPath())

            assertFalse(BardoExportSourcePolicy.allows(workspace, link))
        } finally {
            root.deleteRecursively()
        }
    }
}
