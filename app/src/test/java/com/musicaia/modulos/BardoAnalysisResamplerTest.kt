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
    }
}
