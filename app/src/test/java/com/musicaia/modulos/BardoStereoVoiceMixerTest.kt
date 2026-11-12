package com.musicaia.modulos

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoStereoVoiceMixerTest {
    @Test
    fun preservesStereoInstrumentalAndCentersMonoVoiceAfterResampling() {
        val dir = createTempDir(prefix = "bardo-stereo-mix-")
        try {
            val voice = File(dir, "voice.wav")
            val instrumental = File(dir, "instrumental.wav")
            val output = File(dir, "mix.wav")
            writeWav(voice, 22050, 1, ShortArray(2205) { 1000 })
            val stereo = ShortArray(4410 * 2) { index ->
                if (index % 2 == 0) 3000 else -3000
            }
            writeWav(instrumental, 44100, 2, stereo)
            val voiceBefore = voice.readBytes()
            val instrumentalBefore = instrumental.readBytes()

            val result = BardoStereoVoiceMixer.mix(voice, instrumental, output)

            assertEquals(44100, result.sampleRate)
            assertEquals(4410L, result.frames)
            val header = BardoWavHeaderValidator.inspect(output)
            assertEquals(2, header.channels)
            assertEquals(44100, header.sampleRate)
            assertEquals(16, header.bitsPerSample)
            val bytes = output.readBytes()
            val frame = 2200
            val left = sample(bytes, frame, 0)
            val right = sample(bytes, frame, 1)
            assertTrue("Left channel should retain the instrumental image.", left in 3900..4100)
            assertTrue("Right channel should retain the instrumental image.", right in -2100..-1900)
            assertEquals(voiceBefore.toList(), voice.readBytes().toList())
            assertEquals(instrumentalBefore.toList(), instrumental.readBytes().toList())
            assertTrue(result.peakAfterDbfs <= -1.0 + 0.05)
            assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith("bardo_stereo_voice_") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cancellationDoesNotPublishPartialStereoOutput() {
        val dir = createTempDir(prefix = "bardo-stereo-cancel-")
        try {
            val voice = File(dir, "voice.wav")
            val instrumental = File(dir, "instrumental.wav")
            val output = File(dir, "mix.wav")
            writeWav(voice, 44100, 1, ShortArray(20_000) { 1000 })
            writeWav(instrumental, 44100, 2, ShortArray(40_000) { 2000 })
            var checks = 0
            try {
                BardoStereoVoiceMixer.mix(voice, instrumental, output) {
                    checks += 1
                    checks >= 4
                }
                throw AssertionError("La mezcla debía cancelarse.")
            } catch (_: CancellationException) {
                assertTrue(checks >= 4)
            }
            assertFalse(output.exists())
            assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith("bardo_stereo_voice_") })
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun sample(bytes: ByteArray, frame: Int, channel: Int): Int =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            .getShort(44 + frame * 4 + channel * 2).toInt()

    private fun writeWav(file: File, rate: Int, channels: Int, samples: ShortArray) {
        require(samples.isNotEmpty() && samples.size % channels == 0)
        val dataBytes = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII)); put("fmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1.toShort()); putShort(channels.toShort()); putInt(rate); putInt(rate * channels * 2)
            putShort((channels * 2).toShort()); putShort(16.toShort())
            put("data".toByteArray(Charsets.US_ASCII)); putInt(dataBytes)
        }.array()
        file.outputStream().use { it.write(header); it.write(ByteBuffer.allocate(dataBytes)
            .order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it) } }.array()) }
    }
}
