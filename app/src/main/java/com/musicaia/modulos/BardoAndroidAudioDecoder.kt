package com.musicaia.modulos

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** Decodifica formatos admitidos por Android a WAV PCM16 local, sin nube. */
object BardoAndroidAudioDecoder {
    private const val MAX_INPUT_BYTES = 100L * 1024L * 1024L
    private const val MAX_OUTPUT_BYTES = 300L * 1024L * 1024L
    private const val MAX_DURATION_US = 30L * 60L * 1_000_000L
    private const val IDLE_TIMEOUT_NANOS = 20L * 1_000_000_000L

    fun decodeToWav(
        source: File,
        destination: File,
        cancelled: AtomicBoolean? = null,
        onProgress: (Int) -> Unit = {}
    ) {
        require(source.isFile && source.length() in 1..MAX_INPUT_BYTES) {
            "El archivo no existe, está vacío o supera el límite de 100 MB."
        }
        require(source.absolutePath != destination.absolutePath) {
            "El WAV de análisis debe ser un archivo distinto del original."
        }
        val raw = File(destination.parentFile ?: source.parentFile, destination.name + ".pcm.tmp")
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var codecStarted = false
        var primaryFailure: Throwable? = null
        try {
            destination.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
            val mediaExtractor = MediaExtractor()
            extractor = mediaExtractor
            mediaExtractor.setDataSource(source.absolutePath)
            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (index in 0 until mediaExtractor.trackCount) {
                val candidate = mediaExtractor.getTrackFormat(index)
                if (candidate.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    trackIndex = index
                    inputFormat = candidate
                    break
                }
            }
            require(trackIndex >= 0 && inputFormat != null) {
                "Android no ha encontrado una pista de audio decodificable."
            }
            val format = inputFormat ?: error("Formato de audio no disponible.")
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Códec sin MIME.")
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            require(durationUs <= 0L || durationUs <= MAX_DURATION_US) {
                "El audio supera el límite de 30 minutos."
            }
            mediaExtractor.selectTrack(trackIndex)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            codecStarted = true

            var sampleRate = 0
            var inputChannels = 0
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var outputChannels = 0
            fun applyOutputFormat(output: MediaFormat) {
                val rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    output.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else AudioFormat.ENCODING_PCM_16BIT
                require(rate in 8_000..192_000 && channels in 1..8) {
                    "Formato PCM decodificado no compatible."
                }
                require(encoding in setOf(
                    AudioFormat.ENCODING_PCM_8BIT,
                    AudioFormat.ENCODING_PCM_16BIT,
                    AudioFormat.ENCODING_PCM_24BIT_PACKED,
                    AudioFormat.ENCODING_PCM_32BIT,
                    AudioFormat.ENCODING_PCM_FLOAT
                )) { "El decodificador devolvió un formato PCM no admitido." }
                if (sampleRate == 0) {
                    sampleRate = rate
                    inputChannels = channels
                    pcmEncoding = encoding
                    outputChannels = if (channels == 2) 2 else 1
                } else {
                    require(sampleRate == rate && inputChannels == channels && pcmEncoding == encoding) {
                        "El formato PCM cambió durante la decodificación; se cancela para no mezclar formatos."
                    }
                }
            }

            var inputDone = false
            var outputDone = false
            var decodedBytes = 0L
            var lastProgressNanos = System.nanoTime()
            var lastReportedProgress = -1
            val info = MediaCodec.BufferInfo()
            raw.outputStream().buffered().use { rawOutput ->
                while (!outputDone) {
                    if (cancelled?.get() == true) throw InterruptedException("Análisis cancelado.")
                    check(System.nanoTime() - lastProgressNanos <= IDLE_TIMEOUT_NANOS) {
                        "El códec de Android no avanzó durante 20 segundos."
                    }
                    if (!inputDone) {
                        val inputIndex = decoder.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val input = decoder.getInputBuffer(inputIndex)
                                ?: error("Búfer de entrada del códec no disponible.")
                            input.clear()
                            val size = mediaExtractor.readSampleData(input, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val pts = mediaExtractor.sampleTime
                                require(pts < 0L || pts <= MAX_DURATION_US) { "El audio supera 30 minutos." }
                                decoder.queueInputBuffer(inputIndex, 0, size, pts.coerceAtLeast(0L), mediaExtractor.sampleFlags)
                                mediaExtractor.advance()
                                lastProgressNanos = System.nanoTime()
                            }
                        }
                    }

                    val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
                    when {
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            applyOutputFormat(decoder.outputFormat)
                            lastProgressNanos = System.nanoTime()
                        }
                        outputIndex >= 0 -> {
                            if (info.size > 0) {
                                if (sampleRate == 0) applyOutputFormat(decoder.outputFormat)
                                val output = decoder.getOutputBuffer(outputIndex)
                                    ?: error("Búfer PCM de salida no disponible.")
                                val chunk = output.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
                                    position(info.offset)
                                    limit(info.offset + info.size)
                                }.slice().order(ByteOrder.LITTLE_ENDIAN)
                                val pcm = convertToPcm16(chunk, inputChannels, outputChannels, pcmEncoding)
                                decodedBytes += pcm.size
                                require(decodedBytes <= MAX_OUTPUT_BYTES) {
                                    "El audio decodificado supera el límite de 300 MB."
                                }
                                rawOutput.write(pcm)
                                if (durationUs > 0L && info.presentationTimeUs > 0L) {
                                    val progress = ((info.presentationTimeUs * 100L) / durationUs).toInt().coerceIn(0, 99)
                                    if (progress != lastReportedProgress) {
                                        lastReportedProgress = progress
                                        runCatching { onProgress(progress) }
                                    }
                                }
                            }
                            val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            decoder.releaseOutputBuffer(outputIndex, false)
                            if (eos) outputDone = true
                            if (info.size > 0 || eos) lastProgressNanos = System.nanoTime()
                        }
                    }
                }
            }
            require(sampleRate > 0 && decodedBytes > 0L && decodedBytes % (outputChannels * 2L) == 0L) {
                "Android no produjo muestras PCM completas."
            }
            writeWav(raw, destination, sampleRate, outputChannels, decodedBytes)
            runCatching { onProgress(100) }
        } catch (error: Throwable) {
            primaryFailure = error
            destination.delete()
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try {
                    action()
                } catch (error: Throwable) {
                    if (primaryFailure != null) primaryFailure.addSuppressed(error)
                    else if (cleanupFailure == null) cleanupFailure = error
                    else cleanupFailure?.addSuppressed(error)
                }
            }
            if (codecStarted) cleanup { codec?.stop() }
            cleanup { codec?.release() }
            cleanup { extractor?.release() }
            raw.delete()
            cleanupFailure?.let { throw it }
        }
    }

    private fun convertToPcm16(
        input: ByteBuffer,
        inputChannels: Int,
        outputChannels: Int,
        encoding: Int
    ): ByteArray {
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> error("Formato PCM no admitido.")
        }
        val frameBytes = bytesPerSample * inputChannels
        require(input.remaining() % frameBytes == 0) { "El búfer PCM termina en mitad de una muestra." }
        val frames = input.remaining() / frameBytes
        val source = input.slice().order(ByteOrder.LITTLE_ENDIAN)
        val result = ByteBuffer.allocate(frames * outputChannels * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) {
            val values = IntArray(inputChannels)
            repeat(inputChannels) { channel -> values[channel] = readSample(source, encoding) }
            if (outputChannels == 2) {
                result.putShort(values[0].coerceIn(-32768, 32767).toShort())
                result.putShort(values[1].coerceIn(-32768, 32767).toShort())
            } else {
                val selected = if (inputChannels == 1) values[0] else {
                    // Downmix sencillo para multicanal; se evita fingir que es una mezcla espacial.
                    values.take( minOf(inputChannels, 8) ).average().toInt()
                }
                result.putShort(selected.coerceIn(-32768, 32767).toShort())
            }
        }
        return result.array()
    }

    private fun readSample(source: ByteBuffer, encoding: Int): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> ((source.get().toInt() and 0xff) - 128) shl 8
        AudioFormat.ENCODING_PCM_16BIT -> source.short.toInt()
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
            val raw = (source.get().toInt() and 0xff) or
                ((source.get().toInt() and 0xff) shl 8) or ((source.get().toInt() and 0xff) shl 16)
            (if ((raw and 0x800000) != 0) raw or -0x1000000 else raw) shr 8
        }
        AudioFormat.ENCODING_PCM_32BIT -> source.int shr 16
        AudioFormat.ENCODING_PCM_FLOAT -> {
            val sample = source.float.coerceIn(-1.0f, 1.0f)
            if (sample <= -1.0f) -32768 else (sample * 32767.0f).toInt()
        }
        else -> error("Formato PCM no admitido.")
    }

    private fun writeWav(raw: File, destination: File, sampleRate: Int, channels: Int, dataBytes: Long) {
        require(dataBytes <= Int.MAX_VALUE.toLong() - 36L) { "El WAV resultante supera el límite RIFF." }
        val blockAlign = channels * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataBytes.toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * blockAlign)
        header.putShort(blockAlign.toShort())
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes.toInt())
        destination.outputStream().buffered().use { output ->
            output.write(header.array())
            raw.inputStream().buffered().use { it.copyTo(output, 64 * 1024) }
        }
        RandomAccessFile(destination, "r").use { check(it.length() == 44L + dataBytes) }
    }
}
