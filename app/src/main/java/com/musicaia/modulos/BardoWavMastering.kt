package com.musicaia.modulos

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Procesado básico y no destructivo de WAV PCM 16-bit.
 * No se etiqueta como mastering profesional ni sustituye una mezcla multipista.
 */
object BardoWavMastering {
    private const val MAX_INPUT_BYTES = 25L * 1024L * 1024L
    private const val HIGH_PASS_HZ = 28.0
    private const val COMPRESSOR_THRESHOLD_DB = -12.0
    private const val COMPRESSOR_RATIO = 2.5
    private const val TARGET_PEAK_DBFS = -1.0

    data class Result(
        val output: File,
        val sampleRate: Int,
        val samples: Int,
        val peakBeforeDbfs: Double,
        val peakAfterDbfs: Double,
        val rmsBeforeDbfs: Double,
        val rmsAfterDbfs: Double
    )

    fun process(input: File, output: File): Result {
        require(input.isFile && input.length() in 44..MAX_INPUT_BYTES) {
            "El WAV debe existir y ocupar como máximo 25 MB."
        }
        require(input.canonicalFile != output.canonicalFile) {
            "El archivo de salida debe ser distinto del original."
        }
        val pcm = LectorWav.leer(input.absolutePath)
        require(pcm.frecuencia in 8000..192000) { "Frecuencia de muestreo no compatible." }
        require(pcm.muestras.size >= pcm.frecuencia / 2) { "El audio es demasiado corto para procesarlo." }

        val samples = pcm.muestras
        val peakBefore = samples.maxOf { abs(it.toDouble() / 32768.0) }
        val rmsBefore = sqrt(samples.sumOf { val v = it.toDouble() / 32768.0; v * v } / samples.size)
        val alpha = exp(-2.0 * Math.PI * HIGH_PASS_HZ / pcm.frecuencia)
        val threshold = 10.0.pow(COMPRESSOR_THRESHOLD_DB / 20.0)
        var previousInput = 0.0
        var previousHighPass = 0.0
        var envelope = 0.0
        val processed = DoubleArray(samples.size)

        for (index in samples.indices) {
            val inputSample = samples[index].toDouble() / 32768.0
            val highPass = inputSample - previousInput + alpha * previousHighPass
            previousInput = inputSample
            previousHighPass = highPass

            val magnitude = abs(highPass)
            val coefficient = if (magnitude > envelope) {
                exp(-1.0 / (0.010 * pcm.frecuencia))
            } else {
                exp(-1.0 / (0.120 * pcm.frecuencia))
            }
            envelope = coefficient * envelope + (1.0 - coefficient) * magnitude

            val compressedEnvelope = if (envelope > threshold) {
                threshold * (envelope / threshold).pow(1.0 / COMPRESSOR_RATIO)
            } else envelope
            val gain = if (envelope > 1e-9) compressedEnvelope / envelope else 1.0
            processed[index] = highPass * gain
        }

        val peakProcessed = processed.maxOf { abs(it) }
        require(peakProcessed > 1e-7) { "El audio está en silencio o no contiene señal útil." }
        val targetPeak = 10.0.pow(TARGET_PEAK_DBFS / 20.0)
        val makeup = targetPeak / peakProcessed
        for (index in processed.indices) {
            val amplified = processed[index] * makeup
            // Soft-knee safety limiter, bounded below full scale.
            processed[index] = if (abs(amplified) <= targetPeak) amplified
            else kotlin.math.sign(amplified) * (targetPeak + (1.0 - targetPeak) *
                (1.0 - exp(-(abs(amplified) - targetPeak) / (1.0 - targetPeak))))
        }
        val peakAfter = processed.maxOf { abs(it) }
        val rmsAfter = sqrt(processed.sumOf { it * it } / processed.size)

        val outputDirectory = output.absoluteFile.parentFile ?: File(".")
        check(outputDirectory.isDirectory || outputDirectory.mkdirs()) {
            "No se pudo crear el directorio del WAV masterizado."
        }
        val temporary = File.createTempFile("bardo_master_", ".wav.tmp", outputDirectory)
        try {
            AudioUtil.escribirWavMono16(temporary.absolutePath, pcm.frecuencia, processed.size) { start, count ->
                ShortArray(count) { offset ->
                    (processed[start + offset].coerceIn(-0.999969, 0.999969) * 32768.0)
                        .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }

            fun validateWav(file: File) {
                val info = WavSegmentDiagnostics.inspect(file)
                require(
                    file.length() == 44L + samples.size.toLong() * 2L &&
                        info.sampleRate == pcm.frecuencia &&
                        info.channels == 1 &&
                        info.bitsPerSample == 16 &&
                        info.frames == samples.size.toLong()
                ) { "La verificación del WAV masterizado no coincide con el audio procesado." }
            }

            validateWav(temporary)
            try {
                Files.move(
                    temporary.toPath(),
                    output.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    output.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            validateWav(output)
        } finally {
            if (temporary.exists()) temporary.delete()
        }

        fun db(value: Double) = if (value <= 1e-12) -120.0 else 20.0 * ln(value) / ln(10.0)
        return Result(
            output, pcm.frecuencia, samples.size,
            db(peakBefore), db(peakAfter), db(rmsBefore), db(rmsAfter)
        )
    }
}
