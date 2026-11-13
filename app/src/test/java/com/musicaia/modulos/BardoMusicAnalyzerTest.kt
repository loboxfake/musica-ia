package com.musicaia.modulos

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoMusicAnalyzerTest {
    @Test
    fun measuresPcmLevelAndFindsDominantPitchClass() {
        val wav = createWav(sampleRate = 22_050, channels = 1, seconds = 5.0) { frame, _ ->
            (sin(2.0 * PI * 440.0 * frame / 22_050.0) * 16_000.0).toInt().toShort()
        }
        try {
            val report = BardoMusicAnalyzer.analyze(wav)
            assertEquals(5.0, report.durationSeconds, 0.0001)
            assertEquals(22_050, report.sampleRate)
            assertEquals(1, report.channels)
            assertTrue(report.peakDbfs in -7.0..-5.0)
            assertTrue(report.rmsDbfs in -10.0..-8.0)
            assertEquals(9, report.pitchClassEnergy.indices.maxByOrNull { report.pitchClassEnergy[it] } ?: -1)
            assertTrue(report.spectralCentroidHz!! in 400.0..480.0)
        } finally {
            wav.delete()
        }
    }

    @Test
    fun antiAliasFilteringPreventsHighFrequencyFoldoverIn48KhzAudio() {
        val sampleRate = 48_000
        val wav = createWav(sampleRate = sampleRate, channels = 1, seconds = 5.0) { frame, _ ->
            val time = frame.toDouble() / sampleRate
            val fundamental = sin(2.0 * PI * 1_000.0 * time)
            val ultrasonicForAnalysis = sin(2.0 * PI * 15_000.0 * time)
            ((fundamental + ultrasonicForAnalysis) * 10_000.0).toInt().toShort()
        }
        try {
            val report = BardoMusicAnalyzer.analyze(wav)
            assertTrue(
                "El componente de 15 kHz se ha plegado al espectro analizado: ${report.spectralCentroidHz}",
                report.spectralCentroidHz!! in 800.0..1_300.0
            )
        } finally {
            wav.delete()
        }
    }

    @Test
    fun estimatesRegularPulseNear120Bpm() {
        val wav = createWav(sampleRate = 22_050, channels = 1, seconds = 12.0) { frame, _ ->
            val withinBeat = frame % 11_025
            if (withinBeat < 900) {
                (sin(2.0 * PI * 1_000.0 * withinBeat / 22_050.0) *
                    (18_000.0 * (1.0 - withinBeat / 900.0))).toInt().toShort()
            } else 0
        }
        try {
            val tempo = BardoMusicAnalyzer.analyze(wav).estimatedTempoBpm
            assertNotNull(tempo)
            assertTrue(kotlin.math.abs(tempo!! - 120.0) < 4.0)
        } finally {
            wav.delete()
        }
    }

    @Test
    fun detectsOutOfPhaseStereoAndSilentAudioWithoutInventingTempoOrKey() {
        val wav = createWav(sampleRate = 22_050, channels = 2, seconds = 5.0) { frame, channel ->
            val value = sin(2.0 * PI * 220.0 * frame / 22_050.0) * 12_000.0
            if (channel == 0) value.toInt().toShort() else (-value).toInt().toShort()
        }
        try {
            val report = BardoMusicAnalyzer.analyze(wav)
            assertTrue(report.stereoCorrelation!! < -0.99)
            assertNull(report.estimatedTempoBpm)
            assertNull(report.estimatedKey)
            assertTrue(report.warnings.any { it.contains("mono", ignoreCase = true) })
        } finally {
            wav.delete()
        }

        val silence = createWav(sampleRate = 22_050, channels = 1, seconds = 5.0) { _, _ -> 0 }
        try {
            val report = BardoMusicAnalyzer.analyze(silence)
            assertEquals(100.0, report.silencePercent, 0.01)
            assertNull(report.estimatedTempoBpm)
            assertNull(report.estimatedKey)
        } finally {
            silence.delete()
        }
    }

    private fun createWav(
        sampleRate: Int,
        channels: Int,
        seconds: Double,
        sample: (frame: Int, channel: Int) -> Short
    ): File {
        val frames = (sampleRate * seconds).toInt()
        val dataBytes = frames * channels * 2
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataBytes)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * channels * 2)
        buffer.putShort((channels * 2).toShort())
        buffer.putShort(16)
        buffer.put("data".toByteArray())
        buffer.putInt(dataBytes)
        repeat(frames) { frame ->
            repeat(channels) { channel -> buffer.putShort(sample(frame, channel)) }
        }
        val file = File.createTempFile("bardo-music-analysis-", ".wav")
        file.writeBytes(buffer.array())
        return file
    }
}
