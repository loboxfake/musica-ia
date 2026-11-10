package com.musicaia.modulos

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoWavHeaderValidatorTest {
    @Test
    fun acceptsPcm16WavWithoutLoadingWholeFile() {
        val directory = Files.createTempDirectory("bardo-wav-header").toFile()
        try {
            val wav = File(directory, "valid.wav")
            wav.writeBytes(createWav(format = 1, channels = 2, bits = 16, samples = 8))
            val header = BardoWavHeaderValidator.inspect(wav)
            assertEquals(44_100, header.sampleRate)
            assertEquals(2, header.channels)
            assertEquals(16, header.bitsPerSample)
            assertEquals(32L, header.dataBytes)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsFloatAndTruncatedWavFiles() {
        val directory = Files.createTempDirectory("bardo-wav-invalid").toFile()
        try {
            val floatWav = File(directory, "float.wav")
            floatWav.writeBytes(createWav(format = 3, channels = 1, bits = 32, samples = 4))
            assertTrue(runCatching { BardoWavHeaderValidator.inspect(floatWav) }.isFailure)

            val truncated = File(directory, "truncated.wav")
            truncated.writeBytes(createWav(format = 1, channels = 1, bits = 16, samples = 4).copyOf(46))
            assertTrue(runCatching { BardoWavHeaderValidator.inspect(truncated) }.isFailure)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun createWav(format: Int, channels: Int, bits: Int, samples: Int): ByteArray {
        val bytesPerSample = bits / 8
        val dataSize = samples * channels * bytesPerSample
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(format.toShort())
        buffer.putShort(channels.toShort())
        buffer.putInt(44_100)
        buffer.putInt(44_100 * channels * bytesPerSample)
        buffer.putShort((channels * bytesPerSample).toShort())
        buffer.putShort(bits.toShort())
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        repeat(dataSize) { buffer.put(0) }
        return buffer.array()
    }
}
