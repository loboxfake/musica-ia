package com.musicaia.modulos

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioAssemblerTest {
    @Test
    fun joinsPcm16SegmentsWithCrossfadeAndValidWavHeader() {
        val directory = createTempDir(prefix = "audio-assembler-test")
        try {
            val first = File(directory, "first.wav")
            val second = File(directory, "second.wav")
            val output = File(directory, "joined.wav")
            writeTestWav(first, ShortArray(8000) { 1000 })
            writeTestWav(second, ShortArray(8000) { -1000 })
            val originalFirst = first.readBytes()
            val originalSecond = second.readBytes()

            AudioAssembler.assemble(
                inputFiles = listOf(first, second),
                outputFile = output,
                crossfadeMs = 100,
                fadeOutMs = 100
            )

            val bytes = output.readBytes()
            assertTrue(bytes.size >= 44)
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(1, header.getShort(20).toInt())
            assertEquals(1, header.getShort(22).toInt())
            assertEquals(8000, header.getInt(24))
            assertEquals(16, header.getShort(34).toInt())
            assertEquals(30400, header.getInt(40))
            assertEquals(44 + 30400, bytes.size)
            assertArrayEquals(originalFirst, first.readBytes())
            assertArrayEquals(originalSecond, second.readBytes())
            assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith("bardo_audio_") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsSilentSegmentWithoutReplacingPreviousOutput() {
        val directory = createTempDir(prefix = "audio-assembler-silent-segment-test")
        try {
            val silent = File(directory, "silent.wav")
            val output = File(directory, "joined.wav")
            writeTestWav(silent, ShortArray(8000) { 0 })
            val previousOutput = byteArrayOf(3, 1, 4, 1, 5, 9)
            output.writeBytes(previousOutput)

            try {
                AudioAssembler.assemble(listOf(silent), output)
                throw AssertionError("No se debe publicar un WAV sin audio audible.")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("no contiene audio audible"))
            }

            assertArrayEquals(previousOutput, output.readBytes())
            assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith("bardo_audio_") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun trimsLongSilentTailBeforeCrossfadingSegments() {
        val directory = createTempDir(prefix = "audio-assembler-trim-test")
        try {
            val first = File(directory, "first.wav")
            val second = File(directory, "second.wav")
            val output = File(directory, "joined.wav")
            val withTailSilence = ShortArray(8000) { index -> if (index < 5600) 1000 else 0 }
            writeTestWav(first, withTailSilence)
            writeTestWav(second, withTailSilence)

            AudioAssembler.assemble(
                inputFiles = listOf(first, second),
                outputFile = output,
                crossfadeMs = 100,
                fadeOutMs = 100
            )

            val header = ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            // 6,240 retained frames per clip (700 ms audible + 80 ms tail) minus 100 ms overlap.
            assertEquals(11680 * 2, header.getInt(40))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun allowsIntentionalMusicalPauseAwayFromSegmentJoin() {
        val directory = createTempDir(prefix = "audio-assembler-musical-pause-test")
        try {
            val first = File(directory, "first.wav")
            val second = File(directory, "second.wav")
            val output = File(directory, "joined.wav")
            val firstSamples = ShortArray(16000) { index ->
                if (index in 4000..8000) 0 else 1000
            }
            writeTestWav(first, firstSamples)
            writeTestWav(second, ShortArray(16000) { 1000 })

            AudioAssembler.assemble(
                inputFiles = listOf(first, second),
                outputFile = output,
                crossfadeMs = 100,
                fadeOutMs = 100
            )

            assertTrue(output.isFile)
            assertTrue(output.length() > 44L)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsLongSilenceThatCrossesSegmentJoin() {
        val directory = createTempDir(prefix = "audio-assembler-seam-gap-test")
        try {
            val first = File(directory, "first.wav")
            val second = File(directory, "second.wav")
            val output = File(directory, "joined.wav")
            writeTestWav(first, ShortArray(8000) { index -> if (index < 5600) 1000 else 0 })
            writeTestWav(second, ShortArray(8000) { index -> if (index < 2800) 0 else 1000 })

            try {
                AudioAssembler.assemble(
                    inputFiles = listOf(first, second),
                    outputFile = output,
                    crossfadeMs = 100,
                    fadeOutMs = 100
                )
                throw AssertionError("Expected a seam silence validation failure.")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("unión entre segmentos"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }


    @Test
    fun preservesPreviousOutputWhenAnInputHasAnInvalidRiffBoundary() {
        val directory = createTempDir(prefix = "audio-assembler-corrupt-riff-test")
        try {
            val first = File(directory, "first.wav")
            val second = File(directory, "second.wav")
            val output = File(directory, "joined.wav")
            writeTestWav(first, ShortArray(8000) { 1000 })
            writeTestWav(second, ShortArray(8000) { -1000 })
            val corrupt = second.readBytes()
            ByteBuffer.wrap(corrupt).order(ByteOrder.LITTLE_ENDIAN).putInt(4, Int.MAX_VALUE)
            second.writeBytes(corrupt)
            val previousOutput = byteArrayOf(8, 6, 7, 5, 3, 0, 9)
            output.writeBytes(previousOutput)

            try {
                AudioAssembler.assemble(listOf(first, second), output, crossfadeMs = 100)
                throw AssertionError("Se esperaba rechazo de un tamaño RIFF truncado.")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("Tamaño RIFF"))
            }

            assertArrayEquals(previousOutput, output.readBytes())
            assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith("bardo_audio_") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun refusesToOverwriteAnOriginalSegmentWithTheAssembledOutput() {
        val directory = createTempDir(prefix = "audio-assembler-output-alias-test")
        try {
            val first = File(directory, "first.wav")
            val second = File(directory, "second.wav")
            writeTestWav(first, ShortArray(8000) { 1000 })
            writeTestWav(second, ShortArray(8000) { -1000 })
            val originalFirst = first.readBytes()

            try {
                AudioAssembler.assemble(listOf(first, second), first, crossfadeMs = 100)
                throw AssertionError("El ensamblador no debe sobrescribir una entrada.")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("no puede sobrescribir"))
            }

            assertArrayEquals(originalFirst, first.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }


    @Test
    fun rejectsOversizedSparseWavBeforeLoadingItsPcm() {
        val directory = createTempDir(prefix = "audio-assembler-size-limit-test")
        try {
            val oversized = File(directory, "oversized.wav")
            RandomAccessFile(oversized, "rw").use {
                it.setLength(120L * 1024L * 1024L + 1L)
            }
            val output = File(directory, "joined.wav")
            try {
                AudioAssembler.assemble(listOf(oversized), output)
                throw AssertionError("Expected an oversized WAV to be rejected.")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("120 MiB"))
            }
            assertTrue(!output.exists())
            assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith("bardo_audio_") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aggregateBudgetCanHoldTheFullStableAudioStereoArrangement() {
        val expectedSamples = 18L * 11L * 44_100L * 2L
        assertTrue("The bounded mixer must fit 18 stereo 11-second model segments.",
            expectedSamples <= AudioAssembler.MAX_TOTAL_INPUT_SAMPLES)
    }

    @Test
    fun rejectsCombinedInputSampleCountAboveMemoryBudget() {
        val directory = createTempDir(prefix = "audio-assembler-total-limit-test")
        try {
            val first = File(directory, "first.wav")
            val second = File(directory, "second.wav")
            val third = File(directory, "third.wav")
            val output = File(directory, "joined.wav")
            // Each clip is below the per-file limit; their combined sample count exceeds
            // the bounded in-memory mixing budget.
            val frames = 6_666_667
            writeLargeTestWav(first, frames)
            writeLargeTestWav(second, frames)
            writeLargeTestWav(third, frames)
            try {
                AudioAssembler.assemble(listOf(first, second, third), output)
                throw AssertionError("Expected aggregate PCM sample limit to be enforced.")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("límite conjunto"))
            }
            assertTrue(!output.exists())
        } finally {
            directory.deleteRecursively()
        }
    }


    @Test
    fun preservesInterleavedStereoChannelsWhenJoiningSegments() {
        val directory = createTempDir(prefix = "audio-assembler-stereo-test")
        try {
            val first = File(directory, "first-stereo.wav")
            val second = File(directory, "second-stereo.wav")
            val output = File(directory, "joined-stereo.wav")
            val samples = ShortArray(20_000) { index ->
                if (index % 2 == 0) 1000 else -1000
            }
            writeTestStereoWav(first, samples)
            writeTestStereoWav(second, samples)

            AudioAssembler.assemble(
                inputFiles = listOf(first, second),
                outputFile = output,
                crossfadeMs = 0,
                fadeOutMs = 0
            )

            val bytes = output.readBytes()
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(2, header.getShort(22).toInt())
            assertEquals(4, header.getShort(32).toInt())
            val sampleOffset = 44 + 9000 * 4
            assertEquals(1000, header.getShort(sampleOffset).toInt())
            assertEquals(-1000, header.getShort(sampleOffset + 2).toInt())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun writeTestStereoWav(file: File, samples: ShortArray) {
        val dataBytes = samples.size * 2
        val output = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        output.put("RIFF".toByteArray(Charsets.US_ASCII))
        output.putInt(36 + dataBytes)
        output.put("WAVE".toByteArray(Charsets.US_ASCII))
        output.put("fmt ".toByteArray(Charsets.US_ASCII))
        output.putInt(16)
        output.putShort(1)
        output.putShort(2)
        output.putInt(44100)
        output.putInt(44100 * 4)
        output.putShort(4)
        output.putShort(16)
        output.put("data".toByteArray(Charsets.US_ASCII))
        output.putInt(dataBytes)
        samples.forEach { output.putShort(it) }
        file.writeBytes(output.array())
    }

    private fun writeLargeTestWav(file: File, frames: Int) {
        val dataBytes = frames.toLong() * 2L
        RandomAccessFile(file, "rw").use { output ->
            output.write("RIFF".toByteArray(Charsets.US_ASCII))
            output.write(byteArrayOf(
                (36L + dataBytes).toByte(),
                ((36L + dataBytes) shr 8).toByte(),
                ((36L + dataBytes) shr 16).toByte(),
                ((36L + dataBytes) shr 24).toByte()
            ))
            output.write("WAVE".toByteArray(Charsets.US_ASCII))
            output.write("fmt ".toByteArray(Charsets.US_ASCII))
            output.write(byteArrayOf(16, 0, 0, 0, 1, 0, 1, 0))
            output.write(byteArrayOf(0x40, 0x1F, 0, 0)) // 8000 Hz
            output.write(byteArrayOf(0x80.toByte(), 0x3E, 0, 0)) // 16000 bytes/s
            output.write(byteArrayOf(2, 0, 16, 0))
            output.write("data".toByteArray(Charsets.US_ASCII))
            output.write(byteArrayOf(
                dataBytes.toByte(),
                (dataBytes shr 8).toByte(),
                (dataBytes shr 16).toByte(),
                (dataBytes shr 24).toByte()
            ))
            // Keep every frame audible so the trailing-silence trimmer cannot
            // reduce the fixture before the aggregate-memory guard is exercised.
            val pcmBlock = ByteArray(64 * 1024) { index ->
                if (index % 2 == 0) 0xE8.toByte() else 0x03
            }
            var remaining = dataBytes
            while (remaining > 0L) {
                val count = minOf(pcmBlock.size.toLong(), remaining).toInt()
                output.write(pcmBlock, 0, count)
                remaining -= count
            }
        }
    }


    private fun writeTestWav(file: File, samples: ShortArray) {
        val dataBytes = samples.size * 2
        val output = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        output.put("RIFF".toByteArray(Charsets.US_ASCII))
        output.putInt(36 + dataBytes)
        output.put("WAVE".toByteArray(Charsets.US_ASCII))
        output.put("fmt ".toByteArray(Charsets.US_ASCII))
        output.putInt(16)
        output.putShort(1)
        output.putShort(1)
        output.putInt(8000)
        output.putInt(16000)
        output.putShort(2)
        output.putShort(16)
        output.put("data".toByteArray(Charsets.US_ASCII))
        output.putInt(dataBytes)
        samples.forEach { output.putShort(it) }
        file.writeBytes(output.array())
    }
}
