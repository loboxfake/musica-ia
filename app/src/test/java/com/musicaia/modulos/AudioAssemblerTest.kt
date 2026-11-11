package com.musicaia.modulos

import java.io.File
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
            writeTestWav(first, ShortArray(1000) { 1000 })
            writeTestWav(second, ShortArray(1000) { -1000 })
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
            assertEquals(1000, header.getInt(24))
            assertEquals(16, header.getShort(34).toInt())
            assertEquals(3800, header.getInt(40))
            assertEquals(44 + 3800, bytes.size)
            assertArrayEquals(originalFirst, first.readBytes())
            assertArrayEquals(originalSecond, second.readBytes())
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
            val withTailSilence = ShortArray(1000) { index -> if (index < 700) 1000 else 0 }
            writeTestWav(first, withTailSilence)
            writeTestWav(second, withTailSilence)

            AudioAssembler.assemble(
                inputFiles = listOf(first, second),
                outputFile = output,
                crossfadeMs = 100,
                fadeOutMs = 100
            )

            val header = ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            // 780 retained frames per clip (700 audible + 80 ms tail) minus 100 ms overlap.
            assertEquals(1460 * 2, header.getInt(40))
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
            val firstSamples = ShortArray(2000) { index ->
                if (index in 500..1000) 0 else 1000
            }
            writeTestWav(first, firstSamples)
            writeTestWav(second, ShortArray(2000) { 1000 })

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
            writeTestWav(first, ShortArray(1000) { index -> if (index < 700) 1000 else 0 })
            writeTestWav(second, ShortArray(1000) { index -> if (index < 350) 0 else 1000 })

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
            writeTestWav(first, ShortArray(1000) { 1000 })
            writeTestWav(second, ShortArray(1000) { -1000 })
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
            writeTestWav(first, ShortArray(1000) { 1000 })
            writeTestWav(second, ShortArray(1000) { -1000 })
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
        output.putInt(1000)
        output.putInt(2000)
        output.putShort(2)
        output.putShort(16)
        output.put("data".toByteArray(Charsets.US_ASCII))
        output.putInt(dataBytes)
        samples.forEach { output.putShort(it) }
        file.writeBytes(output.array())
    }
}
