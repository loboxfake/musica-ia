package com.musicaia.modulos

import java.io.File
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
            assertTrue(mixed.muestras.all { it in 2990..3010 })
        } finally {
            dir.deleteRecursively()
        }
    }
}
