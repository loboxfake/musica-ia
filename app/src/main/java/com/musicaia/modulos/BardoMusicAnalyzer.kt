package com.musicaia.modulos

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Analizador musical determinista y offline para WAV PCM16.
 *
 * Mide niveles y defectos directamente de las muestras y estima tempo/tonalidad
 * mediante envolvente de ataques y cromagrama FFT. Las estimaciones musicales
 * nunca se presentan como certezas ni sustituyen una escucha humana.
 */
object BardoMusicAnalyzer {
    private const val ANALYSIS_RATE = 22_050
    private const val FFT_SIZE = 4_096
    private const val FFT_HOP = 2_048
    private const val ENERGY_HOP = 256
    private const val DYNAMIC_WINDOW = 5_512 // ~250 ms a 22.05 kHz
    private const val MAX_DURATION_SECONDS = 30.0 * 60.0
    private const val EPSILON = 1.0e-12

    data class Report(
        val sampleRate: Int,
        val channels: Int,
        val durationSeconds: Double,
        val peakDbfs: Double,
        val rmsDbfs: Double,
        val crestFactorDb: Double,
        val dcOffset: Double,
        val clippedSamplePercent: Double,
        val silencePercent: Double,
        val dynamicRangeDb: Double?,
        val stereoCorrelation: Double?,
        val spectralCentroidHz: Double?,
        val lowBandEnergyPercent: Double?,
        val midBandEnergyPercent: Double?,
        val highBandEnergyPercent: Double?,
        val spectralRolloff85Hz: Double?,
        val estimatedTempoBpm: Double?,
        val tempoConfidence: Double?,
        val estimatedKey: String?,
        val keyConfidence: Double?,
        val pitchClassEnergy: List<Double>,
        val warnings: List<String>
    )

    fun analyze(file: File): Report {
        val header = BardoWavHeaderValidator.inspect(file)
        require(header.bitsPerSample == 16 && header.channels in 1..2) {
            "El analizador musical acepta WAV PCM16 mono o estéreo."
        }
        val frameBytes = header.channels * 2
        val totalFrames = header.dataBytes / frameBytes
        val duration = totalFrames.toDouble() / header.sampleRate
        require(duration in 0.05..MAX_DURATION_SECONDS) {
            "La duración debe estar entre 0,05 segundos y 30 minutos."
        }

        var sumSquares = 0.0
        var sumSamples = 0.0
        var sampleCount = 0L
        var peak = 0
        var clipped = 0L
        var totalChannelSamples = 0L
        var silentFrames = 0L
        var leftSum = 0.0
        var rightSum = 0.0
        var leftSquares = 0.0
        var rightSquares = 0.0
        var cross = 0.0

        val onsetEnvelope = ArrayList<Float>()
        val dynamicRms = ArrayList<Double>()
        var dynamicSumSquares = 0.0
        var dynamicCount = 0
        val fftRing = FloatArray(FFT_SIZE)
        val fftReal = DoubleArray(FFT_SIZE)
        val fftImag = DoubleArray(FFT_SIZE)
        val chroma = DoubleArray(12)
        var ringWrite = 0
        var analysisSamples = 0L
        var energySumSquares = 0.0
        var energyCount = 0
        var previousEnergy = 0.0
        var centroidWeighted = 0.0
        var centroidPower = 0.0
        var lowBandPower = 0.0
        var midBandPower = 0.0
        var highBandPower = 0.0
        val accumulatedSpectrum = DoubleArray(FFT_SIZE / 2)
        var fftFrames = 0

        fun acceptAnalysisSample(value: Float) {
            fftRing[ringWrite] = value
            ringWrite = (ringWrite + 1) % FFT_SIZE
            analysisSamples++

            energySumSquares += value.toDouble() * value.toDouble()
            energyCount++
            if (energyCount == ENERGY_HOP) {
                val rms = sqrt(energySumSquares / ENERGY_HOP)
                val onset = max(0.0, rms - previousEnergy)
                onsetEnvelope.add(onset.toFloat())
                if (rms < 0.001) silentFrames += ENERGY_HOP.toLong()
                previousEnergy = rms
                energySumSquares = 0.0
                energyCount = 0
            }

            dynamicSumSquares += value.toDouble() * value.toDouble()
            dynamicCount++
            if (dynamicCount == DYNAMIC_WINDOW) {
                dynamicRms.add(sqrt(dynamicSumSquares / DYNAMIC_WINDOW))
                dynamicSumSquares = 0.0
                dynamicCount = 0
            }

            if (analysisSamples >= FFT_SIZE &&
                (analysisSamples - FFT_SIZE) % FFT_HOP == 0L
            ) {
                for (i in 0 until FFT_SIZE) {
                    val sourceIndex = (ringWrite + i) % FFT_SIZE
                    val hann = 0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1))
                    fftReal[i] = fftRing[sourceIndex].toDouble() * hann
                    fftImag[i] = 0.0
                }
                fftInPlace(fftReal, fftImag)
                for (bin in 1 until FFT_SIZE / 2) {
                    val frequency = bin.toDouble() * ANALYSIS_RATE / FFT_SIZE
                    if (frequency !in 55.0..10_000.0) continue
                    val power = fftReal[bin] * fftReal[bin] + fftImag[bin] * fftImag[bin]
                    if (power <= EPSILON) continue
                    centroidWeighted += frequency * power
                    centroidPower += power
                    accumulatedSpectrum[bin] += power
                    when {
                        frequency < 250.0 -> lowBandPower += power
                        frequency < 2_000.0 -> midBandPower += power
                        else -> highBandPower += power
                    }
                    if (frequency <= 1_760.0) {
                        val midi = (69.0 + 12.0 * (ln(frequency / 440.0) / ln(2.0))).toInt()
                        val pitchClass = ((midi % 12) + 12) % 12
                        chroma[pitchClass] += power
                    }
                }
                fftFrames++
            }
        }

        RandomAccessFile(file, "r").use { input ->
            input.seek(header.dataOffset)
            val blockFrames = 8_192
            val bytes = ByteArray(blockFrames * frameBytes)
            var remaining = totalFrames
            val resampler = BardoAnalysisResampler(
                inputSampleRate = header.sampleRate,
                outputSampleRate = ANALYSIS_RATE
            ) { sample -> acceptAnalysisSample(sample) }
            while (remaining > 0L) {
                val frames = min(blockFrames.toLong(), remaining).toInt()
                val byteCount = frames * frameBytes
                input.readFully(bytes, 0, byteCount)
                var offset = 0
                repeat(frames) {
                    val left = decodePcm16(bytes[offset], bytes[offset + 1])
                    offset += 2
                    val right = if (header.channels == 2) {
                        decodePcm16(bytes[offset], bytes[offset + 1]).also { offset += 2 }
                    } else left

                    val leftNorm = left / 32768.0
                    val rightNorm = right / 32768.0
                    val mono = (leftNorm + rightNorm) * 0.5
                    sumSquares += mono * mono
                    sumSamples += mono
                    sampleCount++
                    peak = max(peak, max(abs(left), abs(right)))
                    if (abs(left) >= 32_760) clipped++
                    if (header.channels == 2 && abs(right) >= 32_760) clipped++
                    totalChannelSamples += if (header.channels == 2) 2L else 1L

                    if (header.channels == 2) {
                        leftSum += leftNorm
                        rightSum += rightNorm
                        leftSquares += leftNorm * leftNorm
                        rightSquares += rightNorm * rightNorm
                        cross += leftNorm * rightNorm
                    }

                    // Remuestreo con filtro anti-alias al reducir la frecuencia.
                    // Las métricas de nivel siguen calculándose sobre el PCM original.
                    resampler.accept(mono.toFloat())
                }
                remaining -= frames
            }
        }

        if (energyCount > 0 && sqrt(energySumSquares / energyCount) < 0.001) {
            silentFrames += energyCount.toLong()
        }
        // Conserva la última ventana dinámica si tiene al menos media ventana;
        // evita perder transitorios finales sin dar demasiado peso a unos pocos samples.
        if (dynamicCount >= DYNAMIC_WINDOW / 2) {
            dynamicRms.add(sqrt(dynamicSumSquares / dynamicCount))
        }
        val rms = sqrt(sumSquares / max(1L, sampleCount).toDouble())
        val peakLinear = peak / 32768.0
        val peakDb = toDbfs(peakLinear)
        val rmsDb = toDbfs(rms)
        val crest = if (rms > 0.0) 20.0 * log10(max(peakLinear / rms, EPSILON)) else 0.0
        val dc = if (sampleCount > 0L) sumSamples / sampleCount else 0.0
        val clippingPercent = 100.0 * clipped / max(1L, totalChannelSamples).toDouble()
        val stereoCorrelation = if (header.channels == 2) {
            val n = totalFrames.toDouble()
            val covariance = cross - leftSum * rightSum / n
            val leftVariance = leftSquares - leftSum * leftSum / n
            val rightVariance = rightSquares - rightSum * rightSum / n
            if (leftVariance > EPSILON && rightVariance > EPSILON) {
                (covariance / sqrt(leftVariance * rightVariance)).coerceIn(-1.0, 1.0)
            } else null
        } else null

        val durationRange = if (dynamicRms.size >= 4) {
            val sorted = dynamicRms.filter { it > 0.001 }.sorted()
            if (sorted.size >= 4) {
                // Nearest-rank percentiles retain the top tail when only a
                // few windows exist (important for short intros/outros/transients).
                val lowIndex = (ceil(sorted.size * 0.10).toInt() - 1).coerceIn(0, sorted.lastIndex)
                val highIndex = (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(0, sorted.lastIndex)
                val low = sorted[lowIndex]
                val high = sorted[highIndex]
                if (low > 0.0) 20.0 * log10(high / low) else null
            } else null
        } else null

        val tempo = estimateTempo(onsetEnvelope)
        val chromaTotal = chroma.sum()
        val normalizedChroma = if (chromaTotal > EPSILON) chroma.map { it / chromaTotal } else List(12) { 0.0 }
        val key = if (fftFrames >= 2) estimateKey(chroma) else null
        val centroid = if (centroidPower > EPSILON) centroidWeighted / centroidPower else null
        val bandPowerTotal = lowBandPower + midBandPower + highBandPower
        val lowBandPercent = if (bandPowerTotal > EPSILON) 100.0 * lowBandPower / bandPowerTotal else null
        val midBandPercent = if (bandPowerTotal > EPSILON) 100.0 * midBandPower / bandPowerTotal else null
        val highBandPercent = if (bandPowerTotal > EPSILON) 100.0 * highBandPower / bandPowerTotal else null
        val rolloffTarget = centroidPower * 0.85
        var accumulatedForRolloff = 0.0
        var rolloffBin = -1
        for (bin in accumulatedSpectrum.indices) {
            accumulatedForRolloff += accumulatedSpectrum[bin]
            if (accumulatedForRolloff >= rolloffTarget) {
                rolloffBin = bin
                break
            }
        }
        val rolloff85 = if (rolloffBin > 0 && fftFrames > 0) rolloffBin.toDouble() * ANALYSIS_RATE / FFT_SIZE else null

        val warnings = buildList {
            if (clippingPercent > 0.1) add("Hay muestras cerca de saturación; revisa la ganancia o el limitador.")
            if (rmsDb < -35.0) add("El nivel medio es muy bajo.")
            if (durationRange != null && durationRange < 5.0) add("La dinámica medida es reducida; podría haber compresión intensa.")
            if (stereoCorrelation != null && stereoCorrelation < -0.2) add("La correlación estéreo es negativa; comprueba la compatibilidad mono.")
            if (abs(dc) > 0.01) add("Se detecta desplazamiento DC apreciable.")
            if (tempo == null) add("No se pudo estimar un pulso estable con suficiente confianza.")
            if (key == null) add("No se pudo estimar una tonalidad estable con suficiente confianza.")
        }

        return Report(
            sampleRate = header.sampleRate,
            channels = header.channels,
            durationSeconds = duration,
            peakDbfs = peakDb,
            rmsDbfs = rmsDb,
            crestFactorDb = crest,
            dcOffset = dc,
            clippedSamplePercent = clippingPercent,
            silencePercent = 100.0 * silentFrames / max(1L, analysisSamples).toDouble(),
            dynamicRangeDb = durationRange,
            stereoCorrelation = stereoCorrelation,
            spectralCentroidHz = centroid,
            lowBandEnergyPercent = lowBandPercent,
            midBandEnergyPercent = midBandPercent,
            highBandEnergyPercent = highBandPercent,
            spectralRolloff85Hz = rolloff85,
            estimatedTempoBpm = tempo?.first,
            tempoConfidence = tempo?.second,
            estimatedKey = key?.first,
            keyConfidence = key?.second,
            pitchClassEnergy = normalizedChroma,
            warnings = warnings
        )
    }

    private fun decodePcm16(low: Byte, high: Byte): Int =
        ((low.toInt() and 0xff) or (high.toInt() shl 8)).toShort().toInt()

    private fun toDbfs(value: Double): Double =
        if (value <= EPSILON) -120.0 else 20.0 * log10(value)

    private fun fftInPlace(real: DoubleArray, imaginary: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val realValue = real[i]
                real[i] = real[j]
                real[j] = realValue
                val imaginaryValue = imaginary[i]
                imaginary[i] = imaginary[j]
                imaginary[j] = imaginaryValue
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wLengthReal = cos(angle)
            val wLengthImaginary = sin(angle)
            var start = 0
            while (start < n) {
                var wReal = 1.0
                var wImaginary = 0.0
                for (offset in 0 until length / 2) {
                    val even = start + offset
                    val odd = even + length / 2
                    val oddReal = real[odd] * wReal - imaginary[odd] * wImaginary
                    val oddImaginary = real[odd] * wImaginary + imaginary[odd] * wReal
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary
                    val nextWReal = wReal * wLengthReal - wImaginary * wLengthImaginary
                    wImaginary = wReal * wLengthImaginary + wImaginary * wLengthReal
                    wReal = nextWReal
                }
                start += length
            }
            length = length shl 1
        }
    }

    private fun estimateTempo(envelope: List<Float>): Pair<Double, Double>? {
        if (envelope.size < 4 * 86) return null
        val mean = envelope.average().toFloat()
        val centered = FloatArray(envelope.size) { envelope[it] - mean }
        var energy = 0.0
        centered.forEach { energy += it.toDouble() * it }
        if (energy <= EPSILON) return null

        val frameRate = ANALYSIS_RATE.toDouble() / ENERGY_HOP
        val minLag = floor(frameRate * 60.0 / 200.0).toInt().coerceAtLeast(1)
        val maxLag = floor(frameRate * 60.0 / 50.0).toInt()
        val scores = DoubleArray(min(maxLag, centered.size / 2) + 1)
        var bestLag = -1
        var bestScore = 0.0
        for (lag in minLag..min(maxLag, centered.size / 2)) {
            var correlation = 0.0
            var leftEnergy = 0.0
            var rightEnergy = 0.0
            for (i in lag until centered.size) {
                val a = centered[i].toDouble()
                val b = centered[i - lag].toDouble()
                correlation += a * b
                leftEnergy += a * a
                rightEnergy += b * b
            }
            val score = if (leftEnergy > EPSILON && rightEnergy > EPSILON) {
                correlation / sqrt(leftEnergy * rightEnergy)
            } else 0.0
            scores[lag] = score
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag <= 0 || bestScore < 0.08) return null

        // Autocorrelation often gives a stronger peak at 1/2 or 1/3 of the
        // actual beat period because rounded onset frames align every few
        // beats. Prefer the fastest plausible candidate whose normalized
        // correlation remains a substantial fraction of the strongest peak.
        // This reduces systematic half-tempo errors in fast songs while still
        // requiring a meaningful periodic relationship.
        val selectedLag = (minLag until bestLag)
            .firstOrNull { scores[it] >= bestScore * 0.55 }
            ?: bestLag
        val selectedScore = scores[selectedLag]
        return (60.0 * frameRate / selectedLag) to selectedScore.coerceIn(0.0, 1.0)
    }

    private fun estimateKey(chroma: DoubleArray): Pair<String, Double>? {
        val total = chroma.sum()
        if (total <= EPSILON) return null
        val major = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val minor = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
        val names = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
        val centeredChroma = chroma.map { it / total }.toDoubleArray()
        var bestName: String? = null
        var bestCorrelation = -1.0
        for (tonic in 0 until 12) {
            for ((profile, mode) in listOf(major to "mayor", minor to "menor")) {
                val rotated = DoubleArray(12) { profile[(it - tonic + 12) % 12] }
                val correlation = pearson(centeredChroma, rotated)
                if (correlation > bestCorrelation) {
                    bestCorrelation = correlation
                    bestName = "${names[tonic]} ${mode}"
                }
            }
        }
        return if (bestCorrelation >= 0.12) bestName!! to bestCorrelation else null
    }

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val meanA = a.average()
        val meanB = b.average()
        var covariance = 0.0
        var varianceA = 0.0
        var varianceB = 0.0
        for (i in a.indices) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            covariance += da * db
            varianceA += da * da
            varianceB += db * db
        }
        return if (varianceA > EPSILON && varianceB > EPSILON) {
            covariance / sqrt(varianceA * varianceB)
        } else -1.0
    }
}
