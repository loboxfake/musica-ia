package com.musicaia

import com.musicaia.desarrollo.BardoWorkspaceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BardoWorkspaceManagerTest {
    @Test fun candidateIsIndependentAndCanBeSafelyRemoved() {
        val base = createTempDir(prefix = "bardo-workspace")
        try {
            val workspace = File(base, "workspace").apply { mkdirs() }
            val source = File(workspace, "snapshot").apply { mkdirs() }
            val sourceFile = File(source, "nested/Example.kt").apply {
                parentFile?.mkdirs()
                writeText("original")
            }
            val manager = BardoWorkspaceManager(workspace)
            val candidate = manager.createCandidateCopy(source)
            val candidateFile = manager.resolveInside(candidate, "nested/Example.kt")

            assertTrue(candidate.isDirectory)
            assertEquals("original", candidateFile.readText())
            candidateFile.writeText("candidate")
            assertEquals("original", sourceFile.readText())
            assertEquals("candidate", candidateFile.readText())

            manager.deleteCandidate(candidate)
            assertTrue(!candidate.exists())
            assertTrue(sourceFile.isFile)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test fun refusesTraversalAndAbsoluteCandidatePaths() {
        val base = createTempDir(prefix = "bardo-workspace-path")
        try {
            val workspace = File(base, "workspace").apply { mkdirs() }
            val directory = File(workspace, "snapshot").apply { mkdirs() }
            val manager = BardoWorkspaceManager(workspace)
            listOf(
                "../outside.txt",
                "/tmp/outside.txt",
                "a/../outside.txt",
                "a\\outside.txt"
            ).forEach { path ->
                assertTrue("Debe rechazar $path", runCatching {
                    manager.resolveInside(directory, path)
                }.isFailure)
            }
        } finally {
            base.deleteRecursively()
        }
    }

    @Test fun refusesSymbolicLinksWhenSupported() {
        val base = createTempDir(prefix = "bardo-workspace-symlink")
        try {
            val workspace = File(base, "workspace").apply { mkdirs() }
            val source = File(workspace, "snapshot").apply { mkdirs() }
            val outside = File(base, "outside.txt").apply { writeText("outside") }
            val link = File(source, "link.txt")
            val created = runCatching {
                Files.createSymbolicLink(link.toPath(), outside.toPath())
            }.isSuccess
            if (created) {
                val manager = BardoWorkspaceManager(workspace)
                assertTrue(runCatching { manager.createCandidateCopy(source) }.isFailure)
                assertTrue(runCatching { manager.resolveInside(source, "link.txt") }.isFailure)
                assertEquals("outside", outside.readText())
            }
        } finally {
            base.deleteRecursively()
        }
    }
}
