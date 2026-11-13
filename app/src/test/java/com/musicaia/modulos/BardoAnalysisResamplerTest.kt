package com.musicaia.modulos

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoAnalysisResamplerTest {
    @Test
    fun suppressesFrequenciesAboveOutputNyquistBeforeDownsampling() {
        val inputRate = 48_000
        val outputRate = 22_050
        val output = ArrayList<Float>()
        val resampler = BardoAnalysisResampler(inputRate, outputRate) { output.add(it) }
        val frames = inputRate * 2
        for (frame in 0 until frames) {
            val time = frame.toDouble() / inputRate
            val wanted = sin(2.0 * PI * 1_000.0 * time)
            val aliasingTone = sin(2.0 * PI * 15_000.0 * time)
            resampler.accept((wanted + aliasingTone).toFloat())
        }

        val stable = output.drop(100)
        val rms = kotlin.math.sqrt(stable.map { it.toDouble() * it }.average())
        // La componente de 15 kHz debe atenuarse; la de 1 kHz permanece.
        assertTrue("RMS inesperadamente bajo: $rms", rms in 0.60..0.78)
    }

    @Test
    fun linearlyInterpolatesUpsampledSineWithoutSampleDuplicationSteps() {
        val inputRate = 11_025
        val outputRate = 22_050
        val output = ArrayList<Float>()
        val resampler = BardoAnalysisResampler(inputRate, outputRate) { output.add(it) }
        val frames = inputRate * 2
        for (frame in 0 until frames) {
            val sample = sin(2.0 * PI * 1_000.0 * frame / inputRate)
            resampler.accept(sample.toFloat())
        }

        assertTrue("El número de muestras de salida no coincide", output.size == frames * 2)
        val stable = output.drop(100)
        val rms = kotlin.math.sqrt(stable.map { it.toDouble() * it }.average())
        assertTrue("La interpolación alteró demasiado la energía de la señal: $rms", rms in 0.66..0.75)
    }

    @Test
    fun placesIntegerRatioInterpolatedSamplesAtCorrectTimePositions() {
        val output = ArrayList<Float>()
        val resampler = BardoAnalysisResampler(11_025, 22_050) { output.add(it) }
        repeat(5) { resampler.accept(it.toFloat()) }
        assertTrue("La interpolación debe conservar los puntos temporales de entrada", output == listOf(
            0.0f, 0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f
        ))
    }

    @Test
    fun interpolatesNonIntegerUpsamplingRatioWithoutClockDrift() {
        val output = ArrayList<Float>()
        val resampler = BardoAnalysisResampler(16_000, 22_050) { output.add(it) }
        repeat(16_000) { frame ->
            resampler.accept(sin(2.0 * PI * 440.0 * frame / 16_000.0).toFloat())
        }
        assertTrue("El remuestreo 16 kHz → 22,05 kHz acumuló deriva", output.size == 22_050)
        assertTrue(output.all { it.isFinite() })
    }

    @Test
    fun preservesSampleCountForEqualRatesAndProducesExpectedUpsampledCount() {
        val sameRate = ArrayList<Float>()
        val identity = BardoAnalysisResampler(22_050, 22_050) { sameRate.add(it) }
        repeat(1_000) { identity.accept(0.25f) }
        assertTrue(sameRate.size == 1_000)
        assertTrue(sameRate.all { it == 0.25f })

        val upsampled = ArrayList<Float>()
        val upsampler = BardoAnalysisResampler(11_025, 22_050) { upsampled.add(it) }
        repeat(1_000) { upsampler.accept(0.5f) }
        assertTrue(upsampled.size == 2_000)

        val nonIntegerRatio = ArrayList<Float>()
        val downsampler = BardoAnalysisResampler(48_000, 22_050) { nonIntegerRatio.add(it) }
        repeat(48_000) { downsampler.accept(0.125f) }
        assertTrue("El remuestreo acumuló deriva de reloj", nonIntegerRatio.size == 22_050)
    }
}
