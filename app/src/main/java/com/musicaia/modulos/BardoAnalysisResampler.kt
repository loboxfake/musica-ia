package com.musicaia.modulos

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Remuestreador mono streaming para el análisis espectral.
 *
 * Al reducir la frecuencia aplica un FIR pasa-bajos Blackman-windowed sinc
 * antes de diezmar, evitando que agudos por encima de Nyquist se plieguen
 * falsamente dentro del cromagrama y del centroide espectral.
 */
internal class BardoAnalysisResampler(
    private val inputSampleRate: Int,
    private val outputSampleRate: Int,
    private val onOutput: (Float) -> Unit
) {
    private val coefficients: DoubleArray
    private val history = FloatArray(TAP_COUNT)
    private var writeIndex = 0
    private var phase = 0L

    init {
        require(inputSampleRate in 8_000..192_000)
        require(outputSampleRate in 8_000..48_000)
        coefficients = if (inputSampleRate <= outputSampleRate) {
            DoubleArray(1) { 1.0 }
        } else {
            designLowPass(inputSampleRate, outputSampleRate)
        }
    }

    fun accept(sample: Float) {
        if (coefficients.size == 1) {
            phase += outputSampleRate.toLong()
            while (phase >= inputSampleRate.toLong()) {
                phase -= inputSampleRate.toLong()
                onOutput(sample)
            }
            return
        }

        history[writeIndex] = sample
        writeIndex = (writeIndex + 1) % TAP_COUNT
        var filtered = 0.0
        for (tap in 0 until TAP_COUNT) {
            val index = (writeIndex - 1 - tap + TAP_COUNT) % TAP_COUNT
            filtered += history[index] * coefficients[tap]
        }

        phase += outputSampleRate.toLong()
        while (phase >= inputSampleRate.toLong()) {
            phase -= inputSampleRate.toLong()
            onOutput(filtered.toFloat())
        }
    }

    private fun designLowPass(inputRate: Int, outputRate: Int): DoubleArray {
        val cutoff = 0.45 * outputRate / inputRate.toDouble()
        val middle = (TAP_COUNT - 1) / 2.0
        val result = DoubleArray(TAP_COUNT)
        var sum = 0.0
        for (index in result.indices) {
            val offset = index - middle
            val ideal = if (offset == 0.0) {
                2.0 * cutoff
            } else {
                sin(2.0 * PI * cutoff * offset) / (PI * offset)
            }
            val window = 0.42 -
                0.5 * cos(2.0 * PI * index / (TAP_COUNT - 1)) +
                0.08 * cos(4.0 * PI * index / (TAP_COUNT - 1))
            result[index] = ideal * window
            sum += result[index]
        }
        require(sum != 0.0)
        for (index in result.indices) result[index] /= sum
        return result
    }

    private companion object {
        const val TAP_COUNT = 63
    }
}
