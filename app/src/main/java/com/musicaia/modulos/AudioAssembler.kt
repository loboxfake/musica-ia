package com.musicaia.modulos

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

        val destinoCanonico = outputFile.canonicalFile
        require(inputFiles.none { it.canonicalFile == destinoCanonico }) {
            "El archivo de salida no puede sobrescribir uno de los segmentos originales."
        }

        val clips = inputFiles.map(::readPcm16Wav).map {
            trimTrailingSilence(it, threshold = 104, minimumSilenceMs = 200, keepTailMs = 80)
        }
        val reference = clips.first()
        require(clips.all {
            it.sampleRate == reference.sampleRate && it.channels == reference.channels
        }) { "Los segmentos tienen formatos de audio incompatibles." }

        var mixed = clips.first().samples
        val channels = reference.channels
        val seamRanges = mutableListOf<IntRange>()
        val crossfadeFrames = (reference.sampleRate.toLong() * crossfadeMs / 1000L)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        for (clip in clips.drop(1)) {
            val overlap = min(crossfadeFrames, min(mixed.size / channels, clip.frames))
            val currentFrames = mixed.size / channels
            val output = ShortArray(mixed.size + clip.samples.size - overlap * channels)
            val prefixFrames = currentFrames - overlap
            val prefixSamples = prefixFrames * channels
            seamRanges += prefixFrames..(prefixFrames + overlap)
            mixed.copyInto(output, 0, 0, prefixSamples)

            for (frame in 0 until overlap) {
                val progress = (frame + 1).toDouble() / (overlap + 1).toDouble()
                val angle = progress * (Math.PI / 2.0)
                val oldGain = kotlin.math.cos(angle).toFloat()
                val newGain = kotlin.math.sin(angle).toFloat()
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

        require(!hasBoundarySilenceLongerThan(mixed, channels, reference.sampleRate, threshold = 104, maximumSilenceMs = 200, seamRanges = seamRanges)) {
            "Se detectó un silencio superior a 0,2 s alrededor de una unión entre segmentos. No se guardará un resultado con una discontinuidad en la transición."
        }
        applyFades(
            samples = mixed,
            channels = channels,
            sampleRate = reference.sampleRate,
            fadeInMs = 100,
            fadeOutMs = fadeOutMs
        )

        val outputDirectory = outputFile.absoluteFile.parentFile ?: File(".")
        check(outputDirectory.isDirectory || outputDirectory.mkdirs()) {
            "No se pudo crear el directorio del WAV final."
        }
        val temporary = File.createTempFile("bardo_audio_", ".wav.tmp", outputDirectory)
        try {
            writePcm16Wav(temporary, reference.sampleRate, channels, mixed)
            validateWrittenWav(temporary, reference.sampleRate, channels, mixed.size)

            try {
                Files.move(
                    temporary.toPath(),
                    outputFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    outputFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }

            validateWrittenWav(outputFile, reference.sampleRate, channels, mixed.size)
            return outputFile
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun trimTrailingSilence(
        clip: Clip, threshold: Int, minimumSilenceMs: Int, keepTailMs: Int
    ): Clip {
        val channels = clip.channels
        val frames = clip.frames
        val minimumFrames = clip.sampleRate.toLong() * minimumSilenceMs / 1000L
        val keepFrames = (clip.sampleRate.toLong() * keepTailMs / 1000L).toInt()
        var lastAudible = frames - 1
        while (lastAudible >= 0) {
            var peak = 0
            for (channel in 0 until channels) peak = maxOf(peak, kotlin.math.abs(clip.samples[lastAudible * channels + channel].toInt()))
            if (peak > threshold) break
            lastAudible--
        }
        val silentFrames = frames - 1 - lastAudible
        if (silentFrames < minimumFrames) return clip
        val retainedFrames = (lastAudible + 1 + keepFrames).coerceIn(1, frames)
        return clip.copy(samples = clip.samples.copyOf(retainedFrames * channels))
    }

    /**
     * Solo comprueba las zonas de empalme. Los silencios musicales intencionados
     * dentro de un segmento no deben confundirse con huecos introducidos al unir WAV.
     */
    private fun hasBoundarySilenceLongerThan(
        samples: ShortArray,
        channels: Int,
        sampleRate: Int,
        threshold: Int,
        maximumSilenceMs: Int,
        seamRanges: List<IntRange>
    ): Boolean {
        if (seamRanges.isEmpty()) return false
        val frames = samples.size / channels
        val maximumFrames = sampleRate.toLong() * maximumSilenceMs / 1000L
        for (seam in seamRanges) {
            val scanStart = (seam.first.toLong() - maximumFrames).coerceAtLeast(0L).toInt()
            val scanEnd = (seam.last.toLong() + maximumFrames + 1L).coerceAtMost(frames.toLong()).toInt()
            var silentStart = -1
            for (frame in scanStart until scanEnd) {
                var peak = 0
                for (channel in 0 until channels) {
                    peak = maxOf(peak, kotlin.math.abs(samples[frame * channels + channel].toInt()))
                }
                if (peak <= threshold) {
                    if (silentStart < 0) silentStart = frame
                    val silentLength = frame - silentStart + 1
                    if (silentLength.toLong() > maximumFrames && silentStart <= seam.last && frame >= seam.first) {
                        return true
                    }
                } else {
                    silentStart = -1
                }
            }
        }
        return false
    }

    private fun readPcm16Wav(file: File): Clip {
        require(file.isFile && file.length() >= 44L) { "WAV inexistente o demasiado pequeño: ${file.name}" }
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WAVE") {
            "Cabecera RIFF/WAVE no válida: ${file.name}"
        }

        val riffSize = buffer.getInt(4).toLong() and 0xffffffffL
        val riffEndLong = 8L + riffSize
        require(riffSize >= 4L && riffEndLong <= bytes.size.toLong()) {
            "Tamaño RIFF truncado o no válido: ${file.name}"
        }
        val riffEnd = riffEndLong.toInt()

        var format = -1
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var byteRate = 0L
        var blockAlign = 0
        var pcmData: ByteArray? = null
        var offset = 12

        while (offset < riffEnd) {
            require(riffEnd - offset >= 8) { "Cabecera de chunk WAV incompleta: ${file.name}" }
            val chunkId = ascii(bytes, offset, 4)
            val chunkSize = buffer.getInt(offset + 4).toLong() and 0xffffffffL
            val dataStartLong = offset.toLong() + 8L
            val dataEndLong = dataStartLong + chunkSize
            require(dataEndLong <= riffEndLong && dataEndLong <= Int.MAX_VALUE.toLong()) {
                "Chunk WAV truncado: ${file.name}"
            }
            val dataStart = dataStartLong.toInt()
            val dataEnd = dataEndLong.toInt()

            when (chunkId) {
                "fmt " -> {
                    require(chunkSize >= 16L) { "Chunk fmt incompleto: ${file.name}" }
                    format = buffer.getShort(dataStart).toInt() and 0xffff
                    channels = buffer.getShort(dataStart + 2).toInt() and 0xffff
                    sampleRate = buffer.getInt(dataStart + 4)
                    byteRate = buffer.getInt(dataStart + 8).toLong() and 0xffffffffL
                    blockAlign = buffer.getShort(dataStart + 12).toInt() and 0xffff
                    bitsPerSample = buffer.getShort(dataStart + 14).toInt() and 0xffff
                }
                "data" -> if (pcmData == null) pcmData = bytes.copyOfRange(dataStart, dataEnd)
            }

            val next = dataEndLong + (chunkSize and 1L)
            require(next <= riffEndLong) { "Falta el byte de relleno de un chunk WAV: ${file.name}" }
            offset = next.toInt()
        }
        require(offset == riffEnd) { "El bloque RIFF no termina en un límite de chunk: ${file.name}" }

        require(format == 1 && bitsPerSample == 16) {
            "El audio debe ser PCM16 (format=1, 16-bit): ${file.name}"
        }
        require(channels in 1..2 && sampleRate > 0) { "Formato de canales/frecuencia no válido: ${file.name}" }
        val expectedBlockAlign = channels * 2
        require(blockAlign == expectedBlockAlign) { "Alineación PCM no válida: ${file.name}" }
        require(byteRate == sampleRate.toLong() * expectedBlockAlign.toLong()) {
            "Velocidad de bytes PCM no válida: ${file.name}"
        }
        val data = pcmData ?: throw IllegalArgumentException("El WAV no contiene chunk data: ${file.name}")
        require(data.isNotEmpty() && data.size % expectedBlockAlign == 0) {
            "Datos PCM vacíos o truncados: ${file.name}"
        }

        val pcm = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(data.size / 2) { pcm.short }
        require(samples.size % channels == 0) { "Bloques PCM incompletos: ${file.name}" }
        return Clip(sampleRate, channels, samples)
    }

    private fun writePcm16Wav(file: File, sampleRate: Int, channels: Int, samples: ShortArray) {
        require(channels in 1..2 && sampleRate > 0) { "Formato WAV de salida no válido." }
        require(samples.isNotEmpty() && samples.size % channels == 0) {
            "Muestras PCM vacías o incompatibles con los canales."
        }
        val dataBytesLong = samples.size.toLong() * 2L
        require(dataBytesLong <= Int.MAX_VALUE.toLong() - 36L) {
            "El WAV de salida supera el tamaño PCM16 admitido."
        }
        val dataBytes = dataBytesLong.toInt()
        val byteRateLong = sampleRate.toLong() * channels.toLong() * 2L
        require(byteRateLong <= Int.MAX_VALUE.toLong()) { "Velocidad de bytes WAV no válida." }

        val output = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        output.put("RIFF".toByteArray(Charsets.US_ASCII))
        output.putInt(36 + dataBytes)
        output.put("WAVE".toByteArray(Charsets.US_ASCII))
        output.put("fmt ".toByteArray(Charsets.US_ASCII))
        output.putInt(16)
        output.putShort(1)
        output.putShort(channels.toShort())
        output.putInt(sampleRate)
        output.putInt(byteRateLong.toInt())
        output.putShort((channels * 2).toShort())
        output.putShort(16)
        output.put("data".toByteArray(Charsets.US_ASCII))
        output.putInt(dataBytes)
        for (sample in samples) output.putShort(sample)

        file.parentFile?.let { parent ->
            check(parent.isDirectory || parent.mkdirs()) {
                "No se pudo crear el directorio del WAV temporal."
            }
        }
        file.writeBytes(output.array())
    }

    private fun validateWrittenWav(
        file: File,
        sampleRate: Int,
        channels: Int,
        expectedSampleCount: Int
    ) {
        val expectedDataBytes = expectedSampleCount.toLong() * 2L
        require(file.isFile && file.length() == 44L + expectedDataBytes) {
            "El tamaño del WAV escrito no coincide con las muestras PCM."
        }
        val header = ByteArray(44)
        RandomAccessFile(file, "r").use { input -> input.readFully(header) }
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val expectedByteRate = sampleRate.toLong() * channels.toLong() * 2L

        require(ascii(header, 0, 4) == "RIFF" && ascii(header, 8, 4) == "WAVE") {
            "La cabecera RIFF/WAVE de salida no es válida."
        }
        require((buffer.getInt(4).toLong() and 0xffffffffL) == 36L + expectedDataBytes) {
            "El tamaño RIFF de salida no coincide con el contenido."
        }
        require(ascii(header, 12, 4) == "fmt " && buffer.getInt(16) == 16) {
            "El chunk fmt de salida no es válido."
        }
        require(
            (buffer.getShort(20).toInt() and 0xffff) == 1 &&
                (buffer.getShort(22).toInt() and 0xffff) == channels &&
                buffer.getInt(24) == sampleRate &&
                (buffer.getInt(28).toLong() and 0xffffffffL) == expectedByteRate &&
                (buffer.getShort(32).toInt() and 0xffff) == channels * 2 &&
                (buffer.getShort(34).toInt() and 0xffff) == 16 &&
                ascii(header, 36, 4) == "data" &&
                (buffer.getInt(40).toLong() and 0xffffffffL) == expectedDataBytes
        ) { "Los metadatos WAV de salida no coinciden con el PCM generado." }
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)
}
