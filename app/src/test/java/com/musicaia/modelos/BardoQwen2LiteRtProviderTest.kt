package com.musicaia.modelos

import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoQwen2LiteRtProviderTest {
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun acceptsOnlyTheExpectedModelBytesAndHash() {
        val bytes = "tiny deterministic model fixture".toByteArray()
        val file = Files.createTempFile("bardo-qwen-model", ".litertlm").toFile()
        try {
            file.writeBytes(bytes)
            assertEquals(
                sha256(bytes),
                validateBardoQwenModelFile(file, bytes.size.toLong(), sha256(bytes))
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsMissingModelWithoutCreatingOrDeletingAnything() {
        val directory = Files.createTempDirectory("bardo-qwen-missing").toFile()
        val missing = File(directory, "missing.litertlm")
        try {
            val failure = runCatching {
                validateBardoQwenModelFile(missing, 1L, "00")
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(!missing.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsWrongSizeBeforeHashing() {
        val file = Files.createTempFile("bardo-qwen-size", ".litertlm").toFile()
        try {
            file.writeText("wrong-size")
            val failure = runCatching {
                validateBardoQwenModelFile(file, 999L, "00")
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().contains("tamaño"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsHashMismatchAndPreservesOriginalFile() {
        val bytes = "unchanged model bytes".toByteArray()
        val file = Files.createTempFile("bardo-qwen-hash", ".litertlm").toFile()
        try {
            file.writeBytes(bytes)
            val failure = runCatching {
                validateBardoQwenModelFile(file, bytes.size.toLong(), "00".repeat(32))
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(file.isFile)
            assertTrue(file.readBytes().contentEquals(bytes))
        } finally {
            file.delete()
        }
    }
}
