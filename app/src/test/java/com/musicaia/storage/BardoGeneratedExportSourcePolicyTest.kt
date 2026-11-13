package com.musicaia.storage

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoGeneratedExportSourcePolicyTest {
    @Test
    fun acceptsOnlyRegularFilesInsideAppPrivateRoots() {
        val root = Files.createTempDirectory("bardo-private").toFile()
        val outside = Files.createTempDirectory("bardo-outside").toFile()
        try {
            val nested = File(root, "cache/generated").apply { mkdirs() }
            val generated = File(nested, "song.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val unrelated = File(outside, "secret.txt").apply { writeText("private") }

            assertTrue(BardoGeneratedExportSourcePolicy.allows(listOf(root), generated))
            assertFalse(BardoGeneratedExportSourcePolicy.allows(listOf(root), unrelated))
            assertFalse(BardoGeneratedExportSourcePolicy.allows(listOf(root), root))
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun rejectsSymbolicLinkSources() {
        val root = Files.createTempDirectory("bardo-private-link").toFile()
        try {
            val original = File(root, "original.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val link = File(root, "linked.wav")
            Files.createSymbolicLink(link.toPath(), original.toPath())
            assertFalse(BardoGeneratedExportSourcePolicy.allows(listOf(root), link))
        } finally {
            root.deleteRecursively()
        }
    }
}
