package com.musicaia.modulos

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Une segmentos de audio IA PCM16 mediante fundidos cruzados.
 * No sintetiza ni inventa audio: solo organiza los WAV generados por el modelo local.
 */
object AudioAssembler {
    private data class Clip(
        val sampleRate: Int,
        val channels: Int,
        val samples: ShortArray
    ) {
        val frames: Int get() = samples.size / channels
    }

    fun assemble(
        inputFiles: List<File>,
        outputFile: File,
        crossfadeMs: Int = 400,
        fadeOutMs: Int = 900
    ): File {
        require(inputFiles.isNotEmpty()) { "No hay segmentos de audio para unir." }
        require(crossfadeMs >= 0 && fadeOutMs >= 0) { "Duración de fundido no válida." }

        val clips = inputFiles.map(::readPcm16Wav)
        val reference = clips.first()
        require(clips.all {
            it.sampleRate == reference.sampleRate && it.channels == reference.channels
        }) { "Los segmentos tienen formatos de audio incompatibles." }

        var mixed = clips.first().samples
        val channels = reference.channels
        val crossfadeFrames = (reference.sampleRate.toLong() * crossfadeMs / 1000L)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        for (clip in clips.drop(1)) {
            val overlap = min(crossfadeFrames, min(mixed.size / channels, clip.frames))
            val currentFrames = mixed.size / channels
            val output = ShortArray(mixed.size + clip.samples.size - overlap * channels)
            val prefixSamples = (currentFrames - overlap) * channels
            mixed.copyInto(output, 0, 0, prefixSamples)

            for (frame in 0 until overlap) {
                val progress = (frame + 1).toFloat() / (overlap + 1).toFloat()
                val oldGain = 1f - progress
                val newGain = progress
                for (channel in 0 until channels) {
                    val oldSample = mixed[prefixSamples + frame * channels + channel].toFloat()
                    val newSample = clip.samples[frame * channels + channel].toFloat()
                    output[prefixSamples + frame * channels + channel] =
                        (oldSample * oldGain + newSample * newGain)
                            .roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }

            val clipRemainingStart = overlap * channels
            val outputRemainingStart = prefixSamples + overlap * channels
            clip.samples.copyInto(output, outputRemainingStart, clipRemainingStart, clip.samples.size)
            mixed = output
        }

        applyFades(
            samples = mixed,
            channels = channels,
            sampleRate = reference.sampleRate,
            fadeInMs = 100,
            fadeOutMs = fadeOutMs
        )
        writePcm16Wav(outputFile, reference.sampleRate, channels, mixed)
        return outputFile
    }

    private fun readPcm16Wav(file: File): Clip {
        require(file.isFile && file.length() >= 44L) { "WAV inexistente o demasiado pequeño: ${file.name}" }
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WAVE") {
            "Cabecera RIFF/WAVE no válida: ${file.name}"
        }

        var format = -1
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var pcmData: ByteArray? = null
        var offset = 12

        while (offset + 8 <= bytes.size) {
            val chunkId = ascii(bytes, offset, 4)
            val chunkSize = buffer.getInt(offset + 4).toLong() and 0xffffffffL
            val dataStart = offset + 8
            val dataEndLong = dataStart.toLong() + chunkSize
            require(dataEndLong <= bytes.size.toLong()) { "Chunk WAV truncado: ${file.name}" }
            val dataEnd = dataEndLong.toInt()

            when (chunkId) {
                "fmt " -> {
                    require(chunkSize >= 16L) { "Chunk fmt incompleto: ${file.name}" }
                    format = buffer.getShort(dataStart).toInt() and 0xffff
                    channels = buffer.getShort(dataStart + 2).toInt() and 0xffff
                    sampleRate = buffer.getInt(dataStart + 4)
                    bitsPerSample = buffer.getShort(dataStart + 14).toInt() and 0xffff
                }
                "data" -> if (pcmData == null) pcmData = bytes.copyOfRange(dataStart, dataEnd)
            }

            val next = dataEndLong + (chunkSize and 1L)
            require(next <= Int.MAX_VALUE.toLong()) { "Tamaño WAV no válido: ${file.name}" }
            offset = next.toInt()
        }

        require(format == 1 && bitsPerSample == 16) {
            "El audio debe ser PCM16 (format=1, 16-bit): ${file.name}"
        }
        require(channels in 1..2 && sampleRate > 0) { "Formato de canales/frecuencia no válido: ${file.name}" }
        val data = pcmData ?: throw IllegalArgumentException("El WAV no contiene chunk data: ${file.name}")
        require(data.isNotEmpty() && data.size % 2 == 0) { "Datos PCM vacíos o truncados: ${file.name}" }

        val pcm = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(data.size / 2) { pcm.short }
        require(samples.size % channels == 0) { "Bloques PCM incompletos: ${file.name}" }
        return Clip(sampleRate, channels, samples)
    }

    private fun writePcm16Wav(file: File, sampleRate: Int, channels: Int, samples: ShortArray) {
        require(samples.size % channels == 0) { "Muestras PCM incompatibles con los canales." }
        file.parentFile?.mkdirs()
        val dataBytes = samples.size * 2
        val output = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        output.put("RIFF".toByteArray(Charsets.US_ASCII))
        output.putInt(36 + dataBytes)
        output.put("WAVE".toByteArray(Charsets.US_ASCII))
        output.put("fmt ".toByteArray(Charsets.US_ASCII))
        output.putInt(16)
        output.putShort(1)
        output.putShort(channels.toShort())
        output.putInt(sampleRate)
        output.putInt(sampleRate * channels * 2)
        output.putShort((channels * 2).toShort())
        output.putShort(16)
        output.put("data".toByteArray(Charsets.US_ASCII))
        output.putInt(dataBytes)
        samples.forEach(output::putShort)
        file.writeBytes(output.array())
    }

    private fun applyFades(
        samples: ShortArray,
        channels: Int,
        sampleRate: Int,
        fadeInMs: Int,
        fadeOutMs: Int
    ) {
        val frames = samples.size / channels
        val fadeInFrames = min(frames, sampleRate.toLong() * fadeInMs / 1000L).toInt()
        val fadeOutFrames = min(frames, sampleRate.toLong() * fadeOutMs / 1000L).toInt()

        for (frame in 0 until fadeInFrames) {
            val gain = frame.toFloat() / maxOf(1, fadeInFrames).toFloat()
            for (channel in 0 until channels) {
                val index = frame * channels + channel
                samples[index] = (samples[index] * gain).roundToInt().toShort()
            }
        }
        for (frame in 0 until fadeOutFrames) {
            val gain = (fadeOutFrames - frame).toFloat() / maxOf(1, fadeOutFrames).toFloat()
            val absoluteFrame = frames - fadeOutFrames + frame
            for (channel in 0 until channels) {
                val index = absoluteFrame * channels + channel
                samples[index] = (samples[index] * gain).roundToInt().toShort()
            }
        }
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)
}
