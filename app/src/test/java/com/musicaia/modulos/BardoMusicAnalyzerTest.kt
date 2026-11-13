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
    fun includesLoudFinalPartialWindowInDynamicRangeEstimate() {
        val sampleRate = 22_050
        val wav = createWav(sampleRate = sampleRate, channels = 1, seconds = 1.15) { frame, _ ->
            val amplitude = if (frame < sampleRate) 1_000.0 else 12_000.0
            (sin(2.0 * PI * 220.0 * frame / sampleRate) * amplitude).toInt().toShort()
        }
        try {
            val range = BardoMusicAnalyzer.analyze(wav).dynamicRangeDb
            assertTrue("No se incorporó el último bloque dinámico: $range", range != null && range > 15.0)
        } finally {
            wav.delete()
        }
    }

    @Test
    fun reportsSpectralBandBalanceAnd85PercentRolloffForMixedTones() {
        val sampleRate = 22_050
        val wav = createWav(sampleRate = sampleRate, channels = 1, seconds = 5.0) { frame, _ ->
            val time = frame.toDouble() / sampleRate
            val mixed = sin(2.0 * PI * 100.0 * time) + sin(2.0 * PI * 1_000.0 * time) + sin(2.0 * PI * 5_000.0 * time)
            (mixed * 5_000.0 / 3.0).toInt().toShort()
        }
        try {
            val report = BardoMusicAnalyzer.analyze(wav)
            assertNotNull(report.lowBandEnergyPercent)
            assertNotNull(report.midBandEnergyPercent)
            assertNotNull(report.highBandEnergyPercent)
            assertTrue(report.lowBandEnergyPercent!! in 20.0..45.0)
            assertTrue(report.midBandEnergyPercent!! in 20.0..45.0)
            assertTrue(report.highBandEnergyPercent!! in 20.0..45.0)
            assertTrue(report.spectralRolloff85Hz!! in 4_500.0..5_500.0)
            assertEquals(100.0, report.lowBandEnergyPercent!! + report.midBandEnergyPercent!! + report.highBandEnergyPercent!!, 0.01)
        } finally {
            wav.delete()
        }
    }

    @Test
    fun detectsMinorTriadWithoutRelabelingItAsMajor() {
        val sampleRate = 22_050
        val aMinor = doubleArrayOf(220.00, 261.63, 329.63)
        val wav = createWav(sampleRate = sampleRate, channels = 1, seconds = 8.0) { frame, _ ->
            val time = frame.toDouble() / sampleRate
            val value = aMinor.sumOf { sin(2.0 * PI * it * time) } / aMinor.size
            (value * 14_000.0).toInt().toShort()
        }
        try {
            val progression = BardoMusicAnalyzer.analyze(wav).chordProgression
            assertTrue("No se reconoció La menor: $progression", progression.any { it.chord == "La menor" })
            assertTrue("Se etiquetó erróneamente como La mayor: $progression", progression.none { it.chord == "La mayor" })
        } finally {
            wav.delete()
        }
    }

    @Test
    fun doesNotMergeSameChordAcrossAnEvidenceGap() {
        val sampleRate = 22_050
        val aMinor = doubleArrayOf(220.00, 261.63, 329.63)
        val wav = createWav(sampleRate = sampleRate, channels = 1, seconds = 14.0) { frame, _ ->
            val time = frame.toDouble() / sampleRate
            if (time in 4.0..8.0) 0.toShort()
            else {
                val value = aMinor.sumOf { sin(2.0 * PI * it * time) } / aMinor.size
                (value * 14_000.0).toInt().toShort()
            }
        }
        try {
            val segments = BardoMusicAnalyzer.analyze(wav).chordProgression
                .filter { it.chord == "La menor" }
            assertEquals("El hueco de silencio debe separar los dos segmentos", 2, segments.size)
            assertTrue(segments[0].endSeconds < segments[1].startSeconds)
            assertTrue(segments[1].startSeconds - segments[0].endSeconds >= 2.0)
        } finally {
            wav.delete()
        }
    }

    @Test
    fun mergesAdjacentWindowsWhenTheSameChordContinues() {
        val sampleRate = 22_050
        val cMajor = doubleArrayOf(261.63, 329.63, 392.00)
        val wav = createWav(sampleRate = sampleRate, channels = 1, seconds = 8.0) { frame, _ ->
            val time = frame.toDouble() / sampleRate
            val value = cMajor.sumOf { sin(2.0 * PI * it * time) } / cMajor.size
            (value * 14_000.0).toInt().toShort()
        }
        try {
            val progression = BardoMusicAnalyzer.analyze(wav).chordProgression
            assertEquals("Do mayor", progression.single().chord)
            assertEquals(0.0, progression.single().startSeconds, 0.001)
            assertEquals(8.0, progression.single().endSeconds, 0.001)
        } finally {
            wav.delete()
        }
    }

    @Test
    fun followsSyntheticCToGMajorChordChangeAcrossTwoSecondWindows() {
        val sampleRate = 22_050
        val cMajor = doubleArrayOf(261.63, 329.63, 392.00)
        val gMajor = doubleArrayOf(196.00, 246.94, 293.66)
        val wav = createWav(sampleRate = sampleRate, channels = 1, seconds = 8.0) { frame, _ ->
            val time = frame.toDouble() / sampleRate
            val frequencies = if (time < 4.0) cMajor else gMajor
            val value = frequencies.sumOf { sin(2.0 * PI * it * time) } / frequencies.size
            (value * 14_000.0).toInt().toShort()
        }
        try {
            val progression = BardoMusicAnalyzer.analyze(wav).chordProgression
            assertTrue("No se detectó Do mayor: $progression", progression.any { it.chord == "Do mayor" && it.startSeconds < 4.0 })
            assertTrue("No se detectó Sol mayor después del cambio: $progression", progression.any { it.chord == "Sol mayor" && it.startSeconds >= 4.0 })
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
    fun avoidsHalfTempoErrorsForFastRegularPulses() {
        val sampleRate = 22_050
        for (expectedBpm in listOf(150.0, 160.0, 170.0, 180.0)) {
            val beatFrames = sampleRate * 60.0 / expectedBpm
            val wav = createWav(sampleRate = sampleRate, channels = 1, seconds = 12.0) { frame, _ ->
                val beatIndex = kotlin.math.floor(frame / beatFrames).toInt()
                val withinBeat = frame - kotlin.math.floor(beatIndex * beatFrames).toInt()
                if (withinBeat < 900.0) {
                    (sin(2.0 * PI * 1_000.0 * withinBeat / sampleRate) *
                        (18_000.0 * (1.0 - withinBeat / 900.0))).toInt().toShort()
                } else 0
            }
            try {
                val tempo = BardoMusicAnalyzer.analyze(wav).estimatedTempoBpm
                assertNotNull("No se estimó el pulso de $expectedBpm BPM", tempo)
                assertTrue(
                    "Se estimó $tempo BPM para un pulso de $expectedBpm BPM",
                    kotlin.math.abs(tempo!! - expectedBpm) < 6.0
                )
            } finally {
                wav.delete()
            }
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
