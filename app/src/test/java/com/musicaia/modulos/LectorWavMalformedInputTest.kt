package com.musicaia.modulos

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LectorWavMalformedInputTest {
    @Test
    fun readsPcmWhenDataChunkPrecedesFormatChunkAndSkipsOddPaddedChunk() {
        val directory = createTempDir(prefix = "bardo-wav-chunk-order-")
        try {
            val file = File(directory, "reordered.wav")
            file.writeBytes(buildWav(
                channels = 1,
                sampleRate = 22050,
                samples = shortArrayOf(1200, -600, 300),
                dataBeforeFormat = true,
                includeOddJunkChunk = true
            ))

            val audio = LectorWav.leer(file.absolutePath)
            assertEquals(22050, audio.frecuencia)
            assertArrayEquals(shortArrayOf(1200, -600, 300), audio.muestras)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsUnsignedChunkSizeThatEscapesRiffBoundary() {
        val directory = createTempDir(prefix = "bardo-wav-oversized-chunk-")
        try {
            val file = File(directory, "oversized.wav")
            val bytes = buildWav(1, 44100, shortArrayOf(10, 20))
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(40, -1)
            file.writeBytes(bytes)

            val error = runCatching { LectorWav.leer(file.absolutePath) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains("chunk WAV truncado"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsPcmDataThatEndsInPartialFrame() {
        val directory = createTempDir(prefix = "bardo-wav-partial-frame-")
        try {
            val file = File(directory, "partial.wav")
            file.writeBytes(buildWav(
                channels = 2,
                sampleRate = 44100,
                samples = shortArrayOf(100),
                blockAlignOverride = 4
            ))

            val error = runCatching { LectorWav.leer(file.absolutePath) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains("marco PCM incompleto"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsInconsistentPcmBlockAlignment() {
        val directory = createTempDir(prefix = "bardo-wav-block-align-")
        try {
            val file = File(directory, "bad-align.wav")
            file.writeBytes(buildWav(
                channels = 2,
                sampleRate = 44100,
                samples = shortArrayOf(100, -100),
                blockAlignOverride = 2
            ))

            val error = runCatching { LectorWav.leer(file.absolutePath) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains("alineación PCM"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsTruncatedRiffDeclaration() {
        val directory = createTempDir(prefix = "bardo-wav-riff-size-")
        try {
            val file = File(directory, "truncated.wav")
            val bytes = buildWav(1, 44100, shortArrayOf(1, 2))
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, bytes.size)
            file.writeBytes(bytes)

            val error = runCatching { LectorWav.leer(file.absolutePath) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains("bloque RIFF está truncado"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsAudioUtilGeneratorThatReturnsAnUnexpectedBlockLength() {
        val directory = createTempDir(prefix = "bardo-wav-writer-block-")
        try {
            val file = File(directory, "bad-writer.wav")
            val error = runCatching {
                AudioUtil.escribirWavMono16(file.absolutePath, 44100, 4) { _, _ ->
                    shortArrayOf(1, 2)
                }
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
        } finally {
            directory.deleteRecursively()
        }
    }


    @Test
    fun rejectsDuplicateFormatAndDataChunksWithoutRejectingReorderedData() {
        val directory = createTempDir(prefix = "bardo-wav-duplicate-chunks-")
        try {
            val base = buildWav(
                channels = 1,
                sampleRate = 44100,
                samples = shortArrayOf(100, 200, 300)
            )
            val fmtChunk = base.copyOfRange(12, 36)
            val dataChunk = base.copyOfRange(36, base.size)

            val duplicateFmtBytes = ByteArray(base.size + fmtChunk.size)
            System.arraycopy(base, 0, duplicateFmtBytes, 0, 36)
            System.arraycopy(fmtChunk, 0, duplicateFmtBytes, 36, fmtChunk.size)
            System.arraycopy(dataChunk, 0, duplicateFmtBytes, 60, dataChunk.size)
            ByteBuffer.wrap(duplicateFmtBytes).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(4, duplicateFmtBytes.size - 8)
            val duplicateFmt = File(directory, "duplicate-fmt.wav")
            duplicateFmt.writeBytes(duplicateFmtBytes)
            val fmtError = runCatching { LectorWav.leer(duplicateFmt.absolutePath) }.exceptionOrNull()
            assertTrue(fmtError is IllegalArgumentException)
            assertTrue(fmtError?.message.orEmpty().contains("más de un chunk fmt"))

            val duplicateDataBytes = base + dataChunk
            ByteBuffer.wrap(duplicateDataBytes).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(4, duplicateDataBytes.size - 8)
            val duplicateData = File(directory, "duplicate-data.wav")
            duplicateData.writeBytes(duplicateDataBytes)
            val dataError = runCatching { LectorWav.leer(duplicateData.absolutePath) }.exceptionOrNull()
            assertTrue(dataError is IllegalArgumentException)
            assertTrue(dataError?.message.orEmpty().contains("más de un chunk data"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun buildWav(
        channels: Int,
        sampleRate: Int,
        samples: ShortArray,
        dataBeforeFormat: Boolean = false,
        includeOddJunkChunk: Boolean = false,
        blockAlignOverride: Int = channels * 2
    ): ByteArray {
        val chunks = ByteArrayOutputStream()
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { pcm.putShort(it) }
        val data = pcm.array()

        fun writeChunk(id: String, body: ByteArray) {
            chunks.write(id.toByteArray(Charsets.US_ASCII))
            writeInt(chunks, body.size)
            chunks.write(body)
            if ((body.size and 1) == 1) chunks.write(0)
        }

        val format = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1)
            .putShort(channels.toShort())
            .putInt(sampleRate)
            .putInt(sampleRate * blockAlignOverride)
            .putShort(blockAlignOverride.toShort())
            .putShort(16)
            .array()

        if (dataBeforeFormat) writeChunk("data", data)
        if (includeOddJunkChunk) writeChunk("JUNK", byteArrayOf(7, 8, 9))
        writeChunk("fmt ", format)
        if (!dataBeforeFormat) writeChunk("data", data)

        val body = ByteArrayOutputStream().also {
            it.write("WAVE".toByteArray(Charsets.US_ASCII))
            it.write(chunks.toByteArray())
        }.toByteArray()

        return ByteArrayOutputStream().also {
            it.write("RIFF".toByteArray(Charsets.US_ASCII))
            writeInt(it, body.size)
            it.write(body)
        }.toByteArray()
    }

    private fun writeInt(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
        output.write((value ushr 16) and 0xff)
        output.write((value ushr 24) and 0xff)
    }
}
