package com.musicaia.modulos

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoWavMasteringTest {
    @Test
    fun createsVerifiedMasteredWavAndPreservesOriginal() {
        val input = createTempFile(suffix = ".wav")
        val output = File(input.parentFile, "master-${System.nanoTime()}.wav")
        try {
            AudioUtil.escribirWavMono16(input.absolutePath, 48000, 48000) { start, count ->
                ShortArray(count) { offset ->
                    val t = (start + offset).toDouble() / 48000.0
                    (sin(2.0 * Math.PI * 440.0 * t) * 26000.0).toInt().toShort()
                }
            }
            val originalHash = sha256(input)
            val result = BardoWavMastering.process(input, output)
            assertTrue(output.isFile && output.length() > 44)
            assertEquals(originalHash, sha256(input))
            assertEquals(48000, result.sampleRate)
            assertEquals(48000, result.samples)
            assertTrue(result.peakAfterDbfs <= -0.8)
            assertTrue(result.peakAfterDbfs >= -1.2)
            val reopened = LectorWav.leer(output.absolutePath)
            assertEquals(result.samples, reopened.muestras.size)
            assertEquals(result.sampleRate, reopened.frecuencia)
        } finally { input.delete(); output.delete() }
    }

    @Test
    fun rejectsSilenceAndLeavesNoFalseSuccess() {
        val input = createTempFile(suffix = ".wav")
        val output = File(input.parentFile, "silent-${System.nanoTime()}.wav")
        try {
            AudioUtil.escribirWavMono16(input.absolutePath, 44100, 44100) { _, count -> ShortArray(count) }
            val error = runCatching { BardoWavMastering.process(input, output) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertNotEquals(true, output.isFile && output.length() > 44)
        } finally { input.delete(); output.delete() }
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    private fun sin(value: Double): Double = kotlin.math.sin(value)
}
