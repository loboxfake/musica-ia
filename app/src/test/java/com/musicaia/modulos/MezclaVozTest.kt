package com.musicaia.modulos

import android.content.Context
import android.content.ContextWrapper
import com.musicaia.modelo.Dato
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MezclaVozTest {
    @Test
    fun mixesRecordedVoiceWithInstrumentalAndWritesRealPcmWav() {
        val dir = createTempDir(prefix = "bardo-voice-mix-")
        try {
            val voice = File(dir, "voice.wav")
            val instrumental = File(dir, "instrumental.wav")
            val output = File(dir, "mixed.wav")
            AudioUtil.escribirWavMono16(voice.absolutePath, 22050, 4) { _, count ->
                ShortArray(count) { 1000 }
            }
            AudioUtil.escribirWavMono16(instrumental.absolutePath, 44100, 8) { _, count ->
                ShortArray(count) { 2000 }
            }

            val result = MezclaVoz.mezclar(voice.absolutePath, instrumental.absolutePath, output.absolutePath)
            assertTrue(output.isFile)
            assertTrue(output.length() > 44L)
            assertEquals(output.absolutePath, result.rutaWav)

            val mixed = LectorWav.leer(output.absolutePath)
            assertEquals(44100, mixed.frecuencia)
            assertEquals(8, mixed.muestras.size)
            assertTrue(mixed.muestras.all { it.toInt() in 1990..2010 })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun mode3ActuallyMixesVoiceAndInstrumentalAndPreservesBothInputs() {
        val dir = createTempDir(prefix = "bardo-mode3-mix-")
        try {
            val voice = File(dir, "voz_usuario.wav")
            val instrumental = File(dir, "instrumental.wav")
            val output = File(dir, "modo3_mezcla.wav")

            AudioUtil.escribirWavMono16(voice.absolutePath, 22050, 4) { _, count ->
                ShortArray(count) { 1000 }
            }
            AudioUtil.escribirWavMono16(instrumental.absolutePath, 44100, 8) { _, count ->
                ShortArray(count) { 2000 }
            }
            val originalVoice = voice.readBytes()
            val originalInstrumental = instrumental.readBytes()

            val result = Modo3MezclaVozUsuario(TestAudioContext(dir)).ejecutar(Dato.Texto("mezclar"))

            assertTrue("Modo 3 debe devolver un audio real: $result", result is Dato.Audio)
            val audio = result as Dato.Audio
            assertEquals(output.absolutePath, audio.rutaWav)
            assertEquals(44100, audio.frecuenciaMuestreo)
            assertTrue(output.isFile && output.length() > 44L)

            val mixed = LectorWav.leer(output.absolutePath)
            assertEquals(44100, mixed.frecuencia)
            assertEquals(8, mixed.muestras.size)
            // Voz (1000) + instrumental atenuado (2000 * 0.5) = 2000.
            assertTrue(mixed.muestras.all { it.toInt() in 1990..2010 })

            assertArrayEquals("La grabación original no debe cambiar", originalVoice, voice.readBytes())
            assertArrayEquals("El instrumental original no debe cambiar", originalInstrumental, instrumental.readBytes())
            assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith("bardo_modo3_") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun mode3DoesNotReplacePreviousMixWhenAnInputWavIsInvalid() {
        val dir = createTempDir(prefix = "bardo-mode3-invalid-")
        try {
            val voice = File(dir, "voz_usuario.wav")
            val instrumental = File(dir, "instrumental.wav")
            val output = File(dir, "modo3_mezcla.wav")
            voice.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            AudioUtil.escribirWavMono16(instrumental.absolutePath, 44100, 8) { _, count ->
                ShortArray(count) { 2000 }
            }
            val previousOutput = byteArrayOf(9, 8, 7, 6)
            output.writeBytes(previousOutput)

            val result = Modo3MezclaVozUsuario(TestAudioContext(dir)).ejecutar(Dato.Texto("mezclar"))

            assertTrue("Un WAV inválido debe comunicarse como error", result is Dato.Texto)
            assertArrayEquals(previousOutput, output.readBytes())
            assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith("bardo_modo3_") })
        } finally {
            dir.deleteRecursively()
        }
    }

    private class TestAudioContext(private val directory: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getExternalFilesDir(type: String?): File = directory
        override fun getFilesDir(): File = directory
    }
}
