package com.musicaia.modulos

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Mezcla una voz con la instrumental conservando la imagen estéreo.
 * Remuestrea la voz a la frecuencia de la base, centra voces mono,
 * mantiene los canales de una instrumental estéreo y limita picos sin
 * elevar el volumen de origen. No modifica las pistas originales.
 */
object BardoStereoVoiceMixer {
    const val MAX_INPUT_BYTES = 120L * 1024L * 1024L
    private const val MAX_FRAMES = 32_000_000L
    private const val BLOCK_FRAMES = 8_192
    private const val VOICE_GAIN = 1.0
    private const val INSTRUMENTAL_GAIN = 0.5
    private const val TARGET_PEAK = 0.891250938 // -1 dBFS

    data class Result(
        val output: File,
        val sampleRate: Int,
        val frames: Long,
        val peakBeforeDbfs: Double,
        val peakAfterDbfs: Double,
        val attenuationDb: Double
    )

    private data class Pcm(
        val samples: ShortArray,
        val channels: Int,
        val sampleRate: Int,
        val frames: Int
    )

    fun mix(
        voiceFile: File,
        instrumentalFile: File,
        outputFile: File,
        isCancellationRequested: () -> Boolean = { false }
    ): Result {
        fun checkCancellation() {
            if (isCancellationRequested()) {
                throw java.util.concurrent.CancellationException("La mezcla estéreo fue cancelada.")
            }
        }
        require(voiceFile.canonicalFile != instrumentalFile.canonicalFile) {
            "La voz y la instrumental deben ser archivos distintos."
        }
        require(outputFile.canonicalFile != voiceFile.canonicalFile &&
            outputFile.canonicalFile != instrumentalFile.canonicalFile
        ) { "La salida no puede sobrescribir una pista original." }

        val voice = readPcm(voiceFile, ::checkCancellation)
        val instrumental = readPcm(instrumentalFile, ::checkCancellation)
        checkCancellation()

        val rate = instrumental.sampleRate
        val voiceFramesAtOutputRate = (voice.frames.toDouble() * rate / voice.sampleRate)
            .toLong().coerceAtLeast(1L)
        val outputFrames = max(instrumental.frames.toLong(), voiceFramesAtOutputRate)
        require(outputFrames in 1L..MAX_FRAMES) {
            "La duración combinada supera el límite seguro de audio."
        }
        val dataBytes = outputFrames * 4L
        require(dataBytes <= Int.MAX_VALUE.toLong() - 44L) {
            "El WAV estéreo final supera el límite RIFF."
        }

        val fadeInFrames = min(voiceFramesAtOutputRate / 4L, rate.toLong() * 5L / 1000L)
        val fadeOutFrames = min(voiceFramesAtOutputRate / 4L, rate.toLong() * 80L / 1000L)

        fun voiceGainAt(frame: Long): Double {
            var gain = 1.0
            if (fadeInFrames > 0L && frame < fadeInFrames) {
                val x = (frame.toDouble() / fadeInFrames).coerceIn(0.0, 1.0)
                gain *= x * x * (3.0 - 2.0 * x)
            }
            val remaining = voiceFramesAtOutputRate - frame
            if (fadeOutFrames > 0L && remaining < fadeOutFrames) {
                val x = (remaining.toDouble() / fadeOutFrames).coerceIn(0.0, 1.0)
                gain *= x * x * (3.0 - 2.0 * x)
            }
            return gain
        }

        fun sampleAt(track: Pcm, framePosition: Double, channel: Int): Double {
            if (framePosition < 0.0 || framePosition >= track.frames.toDouble()) return 0.0
            val first = floor(framePosition).toInt()
            val fraction = framePosition - first
            val second = min(first + 1, track.frames - 1)
            val sourceChannel = if (track.channels == 1) 0 else channel
            val a = track.samples[first * track.channels + sourceChannel].toDouble()
            val b = track.samples[second * track.channels + sourceChannel].toDouble()
            return (a + (b - a) * fraction) / 32768.0
        }

        fun render(frame: Long, channel: Int): Double {
            val instrumentalValue = if (frame < instrumental.frames) {
                sampleAt(instrumental, frame.toDouble(), channel) * INSTRUMENTAL_GAIN
            } else 0.0
            val voicePosition = frame.toDouble() * voice.sampleRate / rate.toDouble()
            val voiceValue = sampleAt(voice, voicePosition, channel) *
                VOICE_GAIN * voiceGainAt(frame)
            return instrumentalValue + voiceValue
        }

        var peak = 0.0
        var cursor = 0L
        while (cursor < outputFrames) {
            checkCancellation()
            val end = min(outputFrames, cursor + BLOCK_FRAMES)
            while (cursor < end) {
                peak = max(peak, abs(render(cursor, 0)))
                peak = max(peak, abs(render(cursor, 1)))
                cursor++
            }
        }
        require(peak > 1e-8) { "La mezcla de voz e instrumental está en silencio." }
        val scale = if (peak > TARGET_PEAK) TARGET_PEAK / peak else 1.0
        val directory = outputFile.absoluteFile.parentFile ?: File(".")
        check(directory.isDirectory || directory.mkdirs()) {
            "No se pudo crear el directorio de salida estéreo."
        }
        val temporary = File.createTempFile("bardo_stereo_voice_", ".wav.tmp", directory)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(header(rate, 2, dataBytes.toInt()))
                cursor = 0L
                while (cursor < outputFrames) {
                    checkCancellation()
                    val count = min(BLOCK_FRAMES.toLong(), outputFrames - cursor).toInt()
                    val buffer = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(count) { offset ->
                        val frame = cursor + offset
                        repeat(2) { channel ->
                            val value = (render(frame, channel) * scale)
                                .coerceIn(-TARGET_PEAK, TARGET_PEAK)
                            buffer.putShort((value * 32768.0).toInt()
                                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                        }
                    }
                    stream.write(buffer.array(), 0, buffer.position())
                    cursor += count
                }
                stream.fd.sync()
            }
            checkCancellation()
            require(temporary.isFile && temporary.length() == 44L + dataBytes) {
                "El WAV estéreo temporal está incompleto."
            }
            val tempHeader = BardoWavHeaderValidator.inspect(temporary)
            require(tempHeader.channels == 2 && tempHeader.sampleRate == rate &&
                tempHeader.bitsPerSample == 16 && tempHeader.dataBytes == dataBytes
            ) { "El WAV estéreo temporal no superó la validación PCM." }
            try {
                Files.move(temporary.toPath(), outputFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), outputFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING)
            }
            val finalHeader = BardoWavHeaderValidator.inspect(outputFile)
            require(outputFile.length() == 44L + dataBytes &&
                finalHeader.channels == 2 && finalHeader.sampleRate == rate &&
                finalHeader.bitsPerSample == 16 && finalHeader.dataBytes == dataBytes
            ) { "El WAV estéreo publicado no superó la validación final." }
        } finally {
            if (temporary.exists()) temporary.delete()
        }

        fun db(value: Double) = if (value <= 1e-12) -120.0 else 20.0 * ln(value) / ln(10.0)
        return Result(outputFile, rate, outputFrames, db(peak), db(peak * scale),
            if (scale < 1.0) -20.0 * ln(scale) / ln(10.0) else 0.0)
    }

    private fun readPcm(file: File, checkCancellation: () -> Unit): Pcm {
        require(file.isFile && file.length() >= 44L) { "WAV ausente o demasiado pequeño: ${file.name}" }
        require(file.length() <= MAX_INPUT_BYTES) { "El WAV supera el límite de seguridad de 120 MiB: ${file.name}" }
        val header = BardoWavHeaderValidator.inspect(file)
        require(header.channels in 1..2) { "La voz y la instrumental deben ser mono o estéreo: ${file.name}" }
        require(header.sampleRate in 8_000..192_000 && header.bitsPerSample == 16) {
            "Formato PCM16/frecuencia no compatible: ${file.name}"
        }
        val align = header.channels * 2
        require(header.dataBytes > 0L && header.dataBytes % align == 0L) {
            "El WAV termina en un marco PCM incompleto: ${file.name}"
        }
        val frames = header.dataBytes / align
        require(frames in 1L..MAX_FRAMES && frames * header.channels <= Int.MAX_VALUE.toLong()) {
            "El WAV supera el máximo de muestras admitido: ${file.name}"
        }
        val samples = ShortArray((frames * header.channels).toInt())
        RandomAccessFile(file, "r").use { input ->
            input.seek(header.dataOffset)
            val framesPerBlock = 8_192
            val block = ByteArray(framesPerBlock * align)
            var offset = 0
            while (offset < frames.toInt()) {
                checkCancellation()
                val count = min(framesPerBlock, frames.toInt() - offset)
                input.readFully(block, 0, count * align)
                var byteOffset = 0
                repeat(count * header.channels) { index ->
                    val low = block[byteOffset++].toInt() and 0xff
                    val high = block[byteOffset++].toInt()
                    samples[offset * header.channels + index] = (low or (high shl 8)).toShort()
                }
                offset += count
            }
        }
        return Pcm(samples, header.channels, header.sampleRate, frames.toInt())
    }

    private fun header(rate: Int, channels: Int, dataBytes: Int): ByteArray {
        val align = channels * 2
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1.toShort())
            putShort(channels.toShort())
            putInt(rate)
            putInt(rate * align)
            putShort(align.toShort())
            putShort(16.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }.array()
    }
}
