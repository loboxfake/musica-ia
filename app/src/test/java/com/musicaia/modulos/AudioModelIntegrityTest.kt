package com.musicaia.modulos

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioModelIntegrityTest {
    @Test fun parsesExactRequiredModelSet() {
        val files = setOf("conditioners_model.pte", "dit_model.pte")
        val first = "a".repeat(64)
        val second = "b".repeat(64)

        val result = AudioModelIntegrity.parseManifest(
            "$first  conditioners_model.pte\n$second  dit_model.pte\n",
            files
        )

        assertEquals(first, result.getValue("conditioners_model.pte"))
        assertEquals(second, result.getValue("dit_model.pte"))
        assertEquals(files, result.keys)
    }

    @Test fun rejectsMissingExtraDuplicateAndPathTraversalEntries() {
        val required = setOf("model.pte")
        val hash = "a".repeat(64)

        assertTrue(runCatching {
            AudioModelIntegrity.parseManifest("$hash  model.pte\n", setOf("model.pte", "other.pte"))
        }.isFailure)
        assertTrue(runCatching {
            AudioModelIntegrity.parseManifest("$hash  model.pte\n$hash  extra.pte\n", required)
        }.isFailure)
        assertTrue(runCatching {
            AudioModelIntegrity.parseManifest("$hash  model.pte\n$hash  model.pte\n", required)
        }.isFailure)
        assertTrue(runCatching {
            AudioModelIntegrity.parseManifest("$hash  ../model.pte\n", required)
        }.isFailure)
    }

    @Test fun matchesFileAgainstSha256AndRejectsCorruption() {
        val file = File.createTempFile("bardo-model-integrity", ".bin")
        try {
            file.writeText("abc")
            val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

            assertEquals(expected, AudioModelIntegrity.sha256(file))
            assertTrue(AudioModelIntegrity.matches(file, expected))
            file.writeText("abd")
            assertFalse(AudioModelIntegrity.matches(file, expected))
        } finally {
            file.delete()
        }
    }
}
