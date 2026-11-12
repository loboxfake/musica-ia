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
            assertEquals(44L, header.dataOffset)
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

 
    @Test
    fun rejectsChunksThatExceedDeclaredRiffBoundaryEvenWhenFileHasExtraBytes() {
        val directory = Files.createTempDirectory("bardo-wav-riff-boundary").toFile()
        try {
            val wav = File(directory, "outside-riff.wav")
            val bytes = createWav(format = 1, channels = 1, bits = 16, samples = 4)
            // Preserve the physical file length but shrink RIFF's declared size.
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 36)
            wav.writeBytes(bytes)
            assertTrue(runCatching { BardoWavHeaderValidator.inspect(wav) }.isFailure)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsContradictoryBlockAlignAndByteRateFields() {
        val directory = Files.createTempDirectory("bardo-wav-pcm-fields").toFile()
        try {
            val badAlign = File(directory, "bad-align.wav")
            val alignBytes = createWav(format = 1, channels = 2, bits = 16, samples = 8)
            ByteBuffer.wrap(alignBytes).order(ByteOrder.LITTLE_ENDIAN).putShort(32, 2)
            badAlign.writeBytes(alignBytes)
            assertTrue(runCatching { BardoWavHeaderValidator.inspect(badAlign) }.isFailure)

            val badRate = File(directory, "bad-rate.wav")
            val rateBytes = createWav(format = 1, channels = 2, bits = 16, samples = 8)
            ByteBuffer.wrap(rateBytes).order(ByteOrder.LITTLE_ENDIAN).putInt(28, 44_100)
            badRate.writeBytes(rateBytes)
            assertTrue(runCatching { BardoWavHeaderValidator.inspect(badRate) }.isFailure)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun acceptsAnUnknownPaddedChunkInsideRiff() {
        val directory = Files.createTempDirectory("bardo-wav-extra-chunk").toFile()
        try {
            val base = createWav(format = 1, channels = 1, bits = 16, samples = 4)
            val extra = "JUNK".toByteArray() + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(3).array() +
                byteArrayOf(1, 2, 3, 0)
            val output = ByteArray(base.size + extra.size)
            System.arraycopy(base, 0, output, 0, 12)
            System.arraycopy(extra, 0, output, 12, extra.size)
            System.arraycopy(base, 12, output, 12 + extra.size, base.size - 12)
            ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).putInt(4, output.size - 8)
            val file = File(directory, "with-junk.wav")
            file.writeBytes(output)

            val header = BardoWavHeaderValidator.inspect(file)
            assertEquals(44_100, header.sampleRate)
            assertEquals(1, header.channels)
            assertEquals(8L, header.dataBytes)
            assertEquals(56L, header.dataOffset)
        } finally {
            directory.deleteRecursively()
        }
    }


    @Test
    fun rejectsDuplicateFormatChunkAndAcceptsDataBeforeFormat() {
        val directory = Files.createTempDirectory("bardo-wav-chunk-order").toFile()
        try {
            val base = createWav(format = 1, channels = 1, bits = 16, samples = 4)
            val fmtChunk = base.copyOfRange(12, 36)
            val dataChunk = base.copyOfRange(36, base.size)

            val duplicateFmt = ByteArray(base.size + fmtChunk.size)
            System.arraycopy(base, 0, duplicateFmt, 0, 36)
            System.arraycopy(fmtChunk, 0, duplicateFmt, 36, fmtChunk.size)
            System.arraycopy(dataChunk, 0, duplicateFmt, 60, dataChunk.size)
            ByteBuffer.wrap(duplicateFmt).order(ByteOrder.LITTLE_ENDIAN).putInt(4, duplicateFmt.size - 8)
            val duplicateFmtFile = File(directory, "duplicate-fmt.wav")
            duplicateFmtFile.writeBytes(duplicateFmt)
            assertTrue(runCatching { BardoWavHeaderValidator.inspect(duplicateFmtFile) }.isFailure)

            val dataBeforeFmt = ByteArray(base.size)
            System.arraycopy(base, 0, dataBeforeFmt, 0, 12)
            System.arraycopy(dataChunk, 0, dataBeforeFmt, 12, dataChunk.size)
            System.arraycopy(fmtChunk, 0, dataBeforeFmt, 12 + dataChunk.size, fmtChunk.size)
            ByteBuffer.wrap(dataBeforeFmt).order(ByteOrder.LITTLE_ENDIAN).putInt(4, dataBeforeFmt.size - 8)
            val dataFirstFile = File(directory, "data-before-fmt.wav")
            dataFirstFile.writeBytes(dataBeforeFmt)
            val reorderedHeader = BardoWavHeaderValidator.inspect(dataFirstFile)
            assertEquals(44_100, reorderedHeader.sampleRate)
            assertEquals(1, reorderedHeader.channels)
            assertEquals(20L, reorderedHeader.dataOffset)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsDuplicateDataChunks() {
        val directory = Files.createTempDirectory("bardo-wav-duplicate-data").toFile()
        try {
            val base = createWav(format = 1, channels = 1, bits = 16, samples = 4)
            val dataChunk = base.copyOfRange(36, base.size)
            val duplicate = base + dataChunk
            ByteBuffer.wrap(duplicate).order(ByteOrder.LITTLE_ENDIAN).putInt(4, duplicate.size - 8)
            val file = File(directory, "duplicate-data.wav")
            file.writeBytes(duplicate)
            assertTrue(runCatching { BardoWavHeaderValidator.inspect(file) }.isFailure)
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
