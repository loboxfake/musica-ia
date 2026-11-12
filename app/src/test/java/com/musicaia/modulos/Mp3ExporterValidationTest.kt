package com.musicaia.modulos

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp3ExporterValidationTest {
    @Test fun acceptsSupportedStereoPcm16Wav() {
        val directory = Files.createTempDirectory("bardo-mp3-valid").toFile()
        try {
            val file = File(directory, "supported.wav")
            writeWav(file, channels = 2, sampleRate = 44_100, bits = 16)
            val header = Mp3Exporter.validateInputWav(file)
            assertEquals(44_100, header.sampleRate)
            assertEquals(2, header.channels)
            assertEquals(16, header.bitsPerSample)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun rejectsValidPcmWavWithUnsupportedRateOrChannelCount() {
        val directory = Files.createTempDirectory("bardo-mp3-unsupported").toFile()
        try {
            val lowRate = File(directory, "22050.wav")
            writeWav(lowRate, channels = 2, sampleRate = 22_050, bits = 16)
            val rateError = runCatching { Mp3Exporter.validateInputWav(lowRate) }.exceptionOrNull()
            assertTrue(rateError is IllegalArgumentException)
            assertTrue(rateError?.message.orEmpty().contains("32 a 48 kHz"))

            val multichannel = File(directory, "six-channel.wav")
            writeWav(multichannel, channels = 6, sampleRate = 44_100, bits = 16)
            val channelError = runCatching { Mp3Exporter.validateInputWav(multichannel) }.exceptionOrNull()
            assertTrue(channelError is IllegalArgumentException)
            assertTrue(channelError?.message.orEmpty().contains("mono o estéreo"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun refusesToEncodeOverItsWavSourceAndRejectsUnsupportedBitrateBeforeJni() {
        val directory = Files.createTempDirectory("bardo-mp3-guards").toFile()
        try {
            val file = File(directory, "source.wav")
            writeWav(file, channels = 1, sampleRate = 44_100, bits = 16)

            val aliasError = runCatching {
                Mp3Exporter.encodeValidatedWavToMp3(file.absolutePath, file.absolutePath, 320)
            }.exceptionOrNull()
            assertTrue(aliasError is IllegalArgumentException)
            assertTrue(aliasError?.message.orEmpty().contains("no puede sobrescribir"))

            val bitrateError = runCatching {
                Mp3Exporter.encodeValidatedWavToMp3(file.absolutePath, File(directory, "out.mp3").absolutePath, 64)
            }.exceptionOrNull()
            assertTrue(bitrateError is IllegalArgumentException)
            assertTrue(bitrateError?.message.orEmpty().contains("128 y 320"))
        } finally {
            directory.deleteRecursively()
        }
    }


    @Test fun publishesEncodedMp3AtomicallyAndConsumesTheTemporaryFile() {
        val directory = Files.createTempDirectory("bardo-mp3-publish").toFile()
        try {
            val temporary = File(directory, "encoded.part")
            val output = File(directory, "existing.mp3")
            val encoded = ByteArray(512) { index -> (index % 127).toByte() }
            temporary.writeBytes(encoded)
            output.writeBytes(byteArrayOf(9, 8, 7, 6))

            assertTrue(Mp3Exporter.publishEncodedFile(temporary, output))

            assertArrayEquals(encoded, output.readBytes())
            assertFalse(temporary.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun refusesShortMp3AndPreservesTheExistingDestination() {
        val directory = Files.createTempDirectory("bardo-mp3-short").toFile()
        try {
            val temporary = File(directory, "short.part")
            val output = File(directory, "existing.mp3")
            val previous = byteArrayOf(7, 3, 1, 4, 1, 5)
            temporary.writeBytes(ByteArray(128) { 1 })
            output.writeBytes(previous)

            assertFalse(Mp3Exporter.publishEncodedFile(temporary, output))

            assertArrayEquals(previous, output.readBytes())
            assertTrue(temporary.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun rejectsUsingTheSameFileAsTemporaryAndFinalMp3() {
        val directory = Files.createTempDirectory("bardo-mp3-same-path").toFile()
        try {
            val file = File(directory, "same.mp3")
            file.writeBytes(ByteArray(256) { 2 })

            val error = runCatching {
                Mp3Exporter.publishEncodedFile(file, file)
            }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains("deben ser diferentes"))
            assertEquals(256L, file.length())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun writeWav(file: File, channels: Int, sampleRate: Int, bits: Int) {
        val bytesPerSample = bits / 8
        val frames = 4
        val dataSize = frames * channels * bytesPerSample
        val align = channels * bytesPerSample
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * align)
        buffer.putShort(align.toShort())
        buffer.putShort(bits.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        repeat(dataSize) { buffer.put(0) }
        file.writeBytes(buffer.array())
    }
}
