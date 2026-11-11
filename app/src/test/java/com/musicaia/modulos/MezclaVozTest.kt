package com.musicaia.modulos

import android.content.Context
import android.content.ContextWrapper
import com.musicaia.modelo.Dato
import java.io.File
import java.io.RandomAccessFile
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
            val originalVoice = voice.readBytes()
            val originalInstrumental = instrumental.readBytes()

            val result = MezclaVoz.mezclar(voice.absolutePath, instrumental.absolutePath, output.absolutePath)
            assertTrue(output.isFile)
            assertTrue(output.length() > 44L)
            assertEquals(output.absolutePath, result.rutaWav)

            val mixed = LectorWav.leer(output.absolutePath)
            assertEquals(44100, mixed.frecuencia)
            assertEquals(8, mixed.muestras.size)
            assertTrue(mixed.muestras.all { it.toInt() in 1990..2010 })
            assertArrayEquals(originalVoice, voice.readBytes())
            assertArrayEquals(originalInstrumental, instrumental.readBytes())
            assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith("bardo_voice_mix_") })
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
    fun mode3AlignsDifferentDurationsAndLimitsPcmClipping() {
        val dir = createTempDir(prefix = "bardo-mode3-levels-")
        try {
            val voice = File(dir, "voz_usuario.wav")
            val instrumental = File(dir, "instrumental.wav")
            val output = File(dir, "modo3_mezcla.wav")
            AudioUtil.escribirWavMono16(voice.absolutePath, 44100, 44_100) { _, count ->
                ShortArray(count) { 30_000 }
            }
            AudioUtil.escribirWavMono16(instrumental.absolutePath, 44100, 22_050) { _, count ->
                ShortArray(count) { 30_000 }
            }

            val result = Modo3MezclaVozUsuario(TestAudioContext(dir)).ejecutar(Dato.Texto("mezclar"))

            assertTrue("Modo 3 debe aceptar duraciones distintas: $result", result is Dato.Audio)
            val mixed = LectorWav.leer(output.absolutePath)
            assertEquals(44_100, mixed.muestras.size)
            assertTrue(mixed.muestras[0].toInt() in 28_990..29_000)
            assertTrue(mixed.muestras.all { kotlin.math.abs(it.toInt()) <= 29_000 })
            // Tras terminar el instrumental, permanece la voz sin superar el límite.
            assertTrue(mixed.muestras[30_000].toInt() in 19_300..19_400)
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
            voice.writeBytes(ByteArray(64) { 1 })
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


    @Test
    fun mixerPreservesExistingOutputWhenAnInputIsInvalid() {
        val dir = createTempDir(prefix = "bardo-mixer-atomic-failure-")
        try {
            val voice = File(dir, "voice.wav").apply { writeBytes(ByteArray(64) { 1 }) }
            val instrumental = File(dir, "instrumental.wav")
            val output = File(dir, "mixed.wav")
            AudioUtil.escribirWavMono16(instrumental.absolutePath, 44100, 8) { _, count ->
                ShortArray(count) { 2000 }
            }
            val previousOutput = byteArrayOf(1, 4, 9, 16, 25)
            output.writeBytes(previousOutput)

            try {
                MezclaVoz.mezclar(voice.absolutePath, instrumental.absolutePath, output.absolutePath)
                throw AssertionError("Se esperaba que un WAV inválido fuese rechazado.")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("RIFF/WAVE"))
            }

            assertArrayEquals(previousOutput, output.readBytes())
            assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith("bardo_voice_mix_") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun mixerNeverOverwritesEitherOriginalInput() {
        val dir = createTempDir(prefix = "bardo-mixer-no-overwrite-")
        try {
            val voice = File(dir, "voice.wav")
            val instrumental = File(dir, "instrumental.wav")
            AudioUtil.escribirWavMono16(voice.absolutePath, 44100, 8) { _, count ->
                ShortArray(count) { 1000 }
            }
            AudioUtil.escribirWavMono16(instrumental.absolutePath, 44100, 8) { _, count ->
                ShortArray(count) { 2000 }
            }
            val originalVoice = voice.readBytes()
            val originalInstrumental = instrumental.readBytes()

            try {
                MezclaVoz.mezclar(voice.absolutePath, instrumental.absolutePath, voice.absolutePath)
                throw AssertionError("El mezclador no debe sobrescribir un original.")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("no puede sobrescribir"))
            }

            try {
                MezclaVoz.mezclar(voice.absolutePath, instrumental.absolutePath, instrumental.absolutePath)
                throw AssertionError("El mezclador no debe sobrescribir un original.")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("no puede sobrescribir"))
            }

            assertArrayEquals(originalVoice, voice.readBytes())
            assertArrayEquals(originalInstrumental, instrumental.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun wavReaderRejectsOversizedFileBeforeReadingItIntoMemory() {
        val dir = createTempDir(prefix = "bardo-wav-size-limit-")
        try {
            val huge = File(dir, "oversized.wav")
            RandomAccessFile(huge, "rw").use { it.setLength(LectorWav.MAX_FILE_BYTES + 1L) }

            try {
                LectorWav.leer(huge.absolutePath)
                throw AssertionError("El lector debe rechazar archivos que superen 120 MiB.")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("120 MiB"))
            }
        } finally {
            dir.deleteRecursively()
        }
    }


    @Test
    fun wavReaderDownmixesStereoPcmFramesCorrectly() {
        val dir = createTempDir(prefix = "bardo-wav-stereo-")
        try {
            val stereo = File(dir, "stereo.wav")
            val dataBytes = 12
            val wav = java.nio.ByteBuffer.allocate(44 + dataBytes)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            wav.put("RIFF".toByteArray(Charsets.US_ASCII))
            wav.putInt(36 + dataBytes)
            wav.put("WAVE".toByteArray(Charsets.US_ASCII))
            wav.put("fmt ".toByteArray(Charsets.US_ASCII))
            wav.putInt(16)
            wav.putShort(1)
            wav.putShort(2)
            wav.putInt(44100)
            wav.putInt(44100 * 4)
            wav.putShort(4)
            wav.putShort(16)
            wav.put("data".toByteArray(Charsets.US_ASCII))
            wav.putInt(dataBytes)
            wav.putShort(1000)
            wav.putShort((-1000).toShort())
            wav.putShort(2000)
            wav.putShort(4000)
            wav.putShort(30000)
            wav.putShort((-10000).toShort())
            stereo.writeBytes(wav.array())

            val decoded = LectorWav.leer(stereo.absolutePath)

            assertEquals(44100, decoded.frecuencia)
            assertEquals(3, decoded.muestras.size)
            assertEquals(0, decoded.muestras[0].toInt())
            assertEquals(3000, decoded.muestras[1].toInt())
            assertEquals(10000, decoded.muestras[2].toInt())
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
