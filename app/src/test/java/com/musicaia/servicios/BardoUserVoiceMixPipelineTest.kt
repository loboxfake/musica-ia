package com.musicaia.servicios

import com.musicaia.modulos.AudioUtil
import com.musicaia.modulos.LectorWav
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoUserVoiceMixPipelineTest {
    @Test
    fun pipelineReturnsRealVoiceInstrumentalMixAndPreservesSources() {
        val dir = createTempDir(prefix = "bardo-voice-pipeline-")
        try {
            val voice = File(dir, "voice.wav")
            val instrumental = File(dir, "instrumental.wav")
            val output = File(dir, "library-mix.wav")
            AudioUtil.escribirWavMono16(voice.absolutePath, 22050, 4) { _, count ->
                ShortArray(count) { 1000 }
            }
            AudioUtil.escribirWavMono16(instrumental.absolutePath, 44100, 8) { _, count ->
                ShortArray(count) { 2000 }
            }
            val originalVoice = voice.readBytes()
            val originalInstrumental = instrumental.readBytes()

            val finalWav = BardoUserVoiceMixPipeline.prepareWav(
                instrumentalWav = instrumental,
                voiceWav = voice,
                outputWav = output
            )

            assertEquals(output.canonicalFile, finalWav.canonicalFile)
            assertTrue(finalWav.isFile && finalWav.length() > 44L)
            val mixed = LectorWav.leer(finalWav.absolutePath)
            assertEquals(44100, mixed.frecuencia)
            assertEquals(8, mixed.muestras.size)
            assertTrue(mixed.muestras.all { it.toInt() in 1990..2010 })
            assertArrayEquals(originalVoice, voice.readBytes())
            assertArrayEquals(originalInstrumental, instrumental.readBytes())
            assertFalse(dir.listFiles().orEmpty().any { it.name.startsWith("bardo_voice_mix_") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun instrumentalPathIsReturnedUnchangedWhenNoVoiceWasSelected() {
        val dir = createTempDir(prefix = "bardo-instrumental-pipeline-")
        try {
            val instrumental = File(dir, "instrumental.wav")
            val output = File(dir, "unused-mix.wav")
            AudioUtil.escribirWavMono16(instrumental.absolutePath, 44100, 8) { _, count ->
                ShortArray(count) { 2000 }
            }
            val original = instrumental.readBytes()

            val finalWav = BardoUserVoiceMixPipeline.prepareWav(
                instrumentalWav = instrumental,
                voiceWav = null,
                outputWav = output
            )

            assertEquals(instrumental.canonicalFile, finalWav.canonicalFile)
            assertFalse(output.exists())
            assertArrayEquals(original, instrumental.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cancellationBeforeMixDoesNotCreateOutputOrChangeInputs() {
        val dir = createTempDir(prefix = "bardo-cancel-pipeline-")
        try {
            val voice = File(dir, "voice.wav")
            val instrumental = File(dir, "instrumental.wav")
            val output = File(dir, "mix.wav")
            AudioUtil.escribirWavMono16(voice.absolutePath, 44100, 8) { _, count ->
                ShortArray(count) { 1000 }
            }
            AudioUtil.escribirWavMono16(instrumental.absolutePath, 44100, 8) { _, count ->
                ShortArray(count) { 2000 }
            }
            val voiceBefore = voice.readBytes()
            val instrumentalBefore = instrumental.readBytes()

            try {
                BardoUserVoiceMixPipeline.prepareWav(
                    instrumentalWav = instrumental,
                    voiceWav = voice,
                    outputWav = output,
                    isCancellationRequested = { true }
                )
                throw AssertionError("Se esperaba cancelación.")
            } catch (_: CancellationException) {
                // Esperado.
            }

            assertFalse(output.exists())
            assertArrayEquals(voiceBefore, voice.readBytes())
            assertArrayEquals(instrumentalBefore, instrumental.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun invalidVoiceCannotReplaceExistingOutput() {
        val dir = createTempDir(prefix = "bardo-invalid-pipeline-")
        try {
            val voice = File(dir, "voice.wav").apply { writeBytes(ByteArray(64) { 0x11 }) }
            val instrumental = File(dir, "instrumental.wav")
            val output = File(dir, "mix.wav").apply { writeBytes(byteArrayOf(7, 6, 5, 4)) }
            AudioUtil.escribirWavMono16(instrumental.absolutePath, 44100, 8) { _, count ->
                ShortArray(count) { 2000 }
            }
            val oldOutput = output.readBytes()

            try {
                BardoUserVoiceMixPipeline.prepareWav(instrumental, voice, output)
                throw AssertionError("Se esperaba rechazo del WAV de voz.")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("RIFF"))
            }

            assertArrayEquals(oldOutput, output.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }
}
