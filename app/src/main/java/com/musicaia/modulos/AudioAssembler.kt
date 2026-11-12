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
    private const val MAX_INPUT_FILE_BYTES = 120L * 1024L * 1024L
    private const val MAX_FRAMES_PER_CLIP = 8_000_000L
    // Stable Audio genera 18 segmentos estéreo de 11 s a 44,1 kHz:
    // 18 × 11 × 44.100 × 2 = 17.463.600 muestras. El límite admite
    // esa canción completa con margen acotado, manteniendo el uso de heap limitado.
    internal const val MAX_TOTAL_INPUT_SAMPLES = 20_000_000L

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

        val clips = ArrayList<Clip>(inputFiles.size)
        var totalInputSamples = 0L
        for (file in inputFiles) {
            val decoded = readPcm16Wav(file)
            require(decoded.samples.any { kotlin.math.abs(it.toInt()) > 104 }) {
                "El segmento no contiene audio audible: ${file.name}"
            }
            val clip = trimTrailingSilence(
                decoded,
                threshold = 104,
                minimumSilenceMs = 200,
                keepTailMs = 80
            )
            totalInputSamples += clip.samples.size.toLong()
            require(totalInputSamples <= MAX_TOTAL_INPUT_SAMPLES) {
                "Los segmentos superan el límite conjunto de memoria para ensamblado de audio."
            }
            clips += clip
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
        require(file.isFile && file.length() >= 44L) {
            "WAV inexistente o demasiado pequeño: ${file.name}"
        }
        require(file.length() <= MAX_INPUT_FILE_BYTES) {
            "El WAV supera el límite de seguridad de 120 MiB: ${file.name}"
        }

        RandomAccessFile(file, "r").use { input ->
            require(readAscii(input, 4) == "RIFF") { "Cabecera RIFF no válida: ${file.name}" }
            val riffSize = readUnsignedIntLe(input)
            require(riffSize >= 4L) { "Tamaño RIFF no válido: ${file.name}" }
            val riffEnd = 8L + riffSize
            require(riffEnd <= input.length()) { "Tamaño RIFF truncado o no válido: ${file.name}" }
            input.seek(8L)
            require(readAscii(input, 4) == "WAVE") { "Cabecera WAVE no válida: ${file.name}" }

            var format = -1
            var channels = 0
            var sampleRate = 0L
            var bitsPerSample = 0
            var byteRate = 0L
            var blockAlign = 0
            var dataStart = -1L
            var dataSize = -1L
            var offset = 12L

            while (offset < riffEnd) {
                require(riffEnd - offset >= 8L) { "Cabecera de chunk WAV incompleta: ${file.name}" }
                input.seek(offset)
                val chunkId = readAscii(input, 4)
                val chunkSize = readUnsignedIntLe(input)
                val chunkStart = offset + 8L
                val chunkEnd = chunkStart + chunkSize
                require(chunkEnd >= chunkStart && chunkEnd <= riffEnd && chunkEnd <= input.length()) {
                    "Chunk WAV truncado: ${file.name}"
                }

                when (chunkId) {
                    "fmt " -> {
                        require(chunkSize >= 16L) { "Chunk fmt incompleto: ${file.name}" }
                        input.seek(chunkStart)
                        format = readUnsignedShortLe(input)
                        channels = readUnsignedShortLe(input)
                        sampleRate = readUnsignedIntLe(input)
                        byteRate = readUnsignedIntLe(input)
                        blockAlign = readUnsignedShortLe(input)
                        bitsPerSample = readUnsignedShortLe(input)
                    }
                    "data" -> if (dataStart < 0L) {
                        dataStart = chunkStart
                        dataSize = chunkSize
                    }
                }

                val next = chunkEnd + (chunkSize and 1L)
                require(next <= riffEnd) { "Falta el byte de relleno de un chunk WAV: ${file.name}" }
                offset = next
            }
            require(offset == riffEnd) { "El bloque RIFF no termina en un límite de chunk: ${file.name}" }

            require(format == 1 && bitsPerSample == 16) {
                "El audio debe ser PCM16 (format=1, 16-bit): ${file.name}"
            }
            require(channels in 1..2 && sampleRate in 8_000L..192_000L) {
                "Formato de canales/frecuencia no válido: ${file.name}"
            }
            val expectedBlockAlign = channels * 2
            require(blockAlign == expectedBlockAlign) { "Alineación PCM no válida: ${file.name}" }
            require(byteRate == sampleRate * expectedBlockAlign.toLong()) {
                "Velocidad de bytes PCM no válida: ${file.name}"
            }
            require(dataStart >= 0L && dataSize > 0L) {
                "El WAV no contiene chunk data: ${file.name}"
            }
            require(dataSize % expectedBlockAlign == 0L) {
                "Los datos PCM terminan en un marco incompleto: ${file.name}"
            }
            val framesLong = dataSize / expectedBlockAlign
            require(framesLong <= MAX_FRAMES_PER_CLIP) {
                "El WAV supera el máximo de muestras admitido para ensamblado: ${file.name}"
            }
            val sampleCountLong = framesLong * channels.toLong()
            require(sampleCountLong <= Int.MAX_VALUE.toLong()) {
                "El WAV supera el tamaño de muestras admitido: ${file.name}"
            }

            val samples = ShortArray(sampleCountLong.toInt())
            input.seek(dataStart)
            val framesPerBlock = 8_192
            val block = ByteArray(framesPerBlock * expectedBlockAlign)
            var frameOffset = 0
            while (frameOffset < framesLong.toInt()) {
                val frameCount = min(framesPerBlock, framesLong.toInt() - frameOffset)
                val byteCount = frameCount * expectedBlockAlign
                input.readFully(block, 0, byteCount)
                var byteOffset = 0
                for (i in 0 until frameCount * channels) {
                    val low = block[byteOffset++].toInt() and 0xff
                    val high = block[byteOffset++].toInt()
                    samples[frameOffset * channels + i] = (low or (high shl 8)).toShort()
                }
                frameOffset += frameCount
            }
            return Clip(sampleRate.toInt(), channels, samples)
        }
    }

    private fun readAscii(input: RandomAccessFile, count: Int): String {
        val bytes = ByteArray(count)
        input.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun readUnsignedShortLe(input: RandomAccessFile): Int {
        val low = input.read()
        val high = input.read()
        require(low >= 0 && high >= 0) { "Cabecera WAV truncada." }
        return (low and 0xff) or ((high and 0xff) shl 8)
    }

    private fun readUnsignedIntLe(input: RandomAccessFile): Long {
        val a = input.read()
        val b = input.read()
        val c = input.read()
        val d = input.read()
        require(a >= 0 && b >= 0 && c >= 0 && d >= 0) { "Cabecera WAV truncada." }
        return (a.toLong() and 0xffL) or
            ((b.toLong() and 0xffL) shl 8) or
            ((c.toLong() and 0xffL) shl 16) or
            ((d.toLong() and 0xffL) shl 24)
    }

    private fun writePcm16Wav(file: File, sampleRate: Int, channels: Int, samples: ShortArray) {
        require(channels in 1..2 && sampleRate > 0) { "Formato WAV de salida no válido." }
        require(samples.isNotEmpty() && samples.size % channels == 0) {
            "Muestras PCM vacías o incompatibles con los canales."
        }
        val dataBytesLong = samples.size.toLong() * 2L
        require(dataBytesLong <= Int.MAX_VALUE.toLong() - 44L) {
            "El WAV de salida supera el tamaño PCM16 admitido."
        }
        val dataBytes = dataBytesLong.toInt()
        val byteRateLong = sampleRate.toLong() * channels.toLong() * 2L
        require(byteRateLong <= Int.MAX_VALUE.toLong()) { "Velocidad de bytes WAV no válida." }

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataBytes)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRateLong.toInt())
        header.putShort((channels * 2).toShort())
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes)

        file.parentFile?.let { parent ->
            check(parent.isDirectory || parent.mkdirs()) {
                "No se pudo crear el directorio del WAV temporal."
            }
        }
        file.outputStream().buffered(64 * 1024).use { stream ->
            stream.write(header.array())
            val samplesPerBlock = 32 * 1024
            val block = ByteBuffer.allocate(samplesPerBlock * 2).order(ByteOrder.LITTLE_ENDIAN)
            var offset = 0
            while (offset < samples.size) {
                val count = min(samplesPerBlock, samples.size - offset)
                block.clear()
                for (i in 0 until count) block.putShort(samples[offset + i])
                stream.write(block.array(), 0, count * 2)
                offset += count
            }
        }
    }


    private fun applyFades(
        samples: ShortArray,
        channels: Int,
        sampleRate: Int,
        fadeInMs: Int,
        fadeOutMs: Int
    ) {
        val frames = samples.size / channels
        val fadeInFrames = min(frames.toLong(), sampleRate.toLong() * fadeInMs / 1000L).toInt()
        val fadeOutFrames = min(frames.toLong(), sampleRate.toLong() * fadeOutMs / 1000L).toInt()

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
