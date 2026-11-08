package com.musicaia.modulos

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

/** Reconocimiento local de voz en español para adjuntos decodificables por Android. */
object BardoOfflineAsr {
    const val MODEL_ARCHIVE_SIZE_BYTES = 39_817_833L
    const val MODEL_ARCHIVE_SHA256 = "09b239888f633ef2f0b4e09736e3d9936acfd810bc65d53fad45261762c6511f"
    const val MODEL_ARCHIVE_URL = "https://github.com/BartekReterski/VoskModels/releases/download/v1/vosk-model-small-es-0.42.zip"
    private const val MODEL_DIRECTORY_NAME = "vosk-model-small-es-0.42"
    private const val MAX_ARCHIVE_ENTRIES = 3000
    private const val MAX_UNPACKED_BYTES = 240L * 1024L * 1024L
    private const val MAX_MEDIA_BYTES = 100L * 1024L * 1024L
    private const val MAX_DURATION_US = 30L * 60L * 1_000_000L
    private const val MAX_DECODED_PCM_BYTES = 512L * 1024L * 1024L
    private const val MAX_TRANSCRIPT_CHARS = 240_000

    fun modelDirectory(context: Context): File =
        File(File(context.filesDir, "bardo_models"), MODEL_DIRECTORY_NAME)

    fun isModelInstalled(context: Context): Boolean = hasModelLayout(modelDirectory(context))

    private fun hasModelLayout(directory: File): Boolean =
        File(directory, "am/final.mdl").isFile &&
            File(directory, "conf/model.conf").isFile &&
            File(directory, "graph").isDirectory &&
            File(directory, "graph").listFiles()?.any { it.isFile && it.extension == "fst" } == true

    fun downloadAndInstall(context: Context, onProgress: (Int) -> Unit = {}): File {
        val target = modelDirectory(context)
        if (hasModelLayout(target)) return target
        val parent = target.parentFile ?: error("No se pudo resolver la carpeta del modelo.")
        check(parent.exists() || parent.mkdirs()) { "No se pudo crear la carpeta de modelos." }
        val archive = File(parent, ".vosk-es.zip.part")
        val staging = File(parent, ".vosk-es-stage")
        val installTemp = File(parent, ".vosk-es-install")
        val backup = File(parent, ".vosk-es-previous")
        archive.delete()
        staging.deleteRecursively()
        installTemp.deleteRecursively()
        var connection: HttpURLConnection? = null
        try {
            val url = URL(MODEL_ARCHIVE_URL)
            require(url.protocol.equals("https", true)) { "La descarga del modelo exige HTTPS." }
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "BARDO-Offline-ASR/1.0")
            }
            connection.connect()
            require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "El servidor del modelo respondió HTTP " + connection.responseCode + "."
            }
            require(connection.url.protocol.equals("https", true)) { "La redirección del modelo no conservó HTTPS." }
            val declaredLength = connection.contentLengthLong
            require(declaredLength == -1L || declaredLength == MODEL_ARCHIVE_SIZE_BYTES) {
                "El tamaño anunciado del modelo no coincide con el publicado."
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(archive).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= MODEL_ARCHIVE_SIZE_BYTES) { "El archivo del modelo supera el tamaño esperado." }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        runCatching { onProgress(((copied * 100L) / MODEL_ARCHIVE_SIZE_BYTES).toInt().coerceIn(0, 99)) }
                    }
                    output.fd.sync()
                }
            }
            require(copied == MODEL_ARCHIVE_SIZE_BYTES) { "Descarga incompleta: $copied de $MODEL_ARCHIVE_SIZE_BYTES bytes." }
            val hash = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
            require(hash.equals(MODEL_ARCHIVE_SHA256, true)) { "La verificación SHA-256 del modelo español ha fallado." }

            check(staging.mkdirs()) { "No se pudo preparar la extracción temporal." }
            var entries = 0
            var expanded = 0L
            val rootPath = staging.canonicalPath
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    require(entries <= MAX_ARCHIVE_ENTRIES) { "El modelo contiene demasiadas entradas ZIP." }
                    val normalized = entry.name.replace('\\', '/')
                    val components = normalized.trimEnd('/').split('/')
                    require(normalized.isNotBlank() && !normalized.startsWith("/") &&
                        components.none { it == ".." || it.isBlank() }) { "El ZIP contiene una ruta insegura." }
                    val outputFile = File(staging, normalized).canonicalFile
                    require(outputFile.path.startsWith(rootPath + File.separator)) {
                        "El ZIP intenta salir de la carpeta temporal."
                    }
                    if (entry.isDirectory) {
                        check(outputFile.exists() || outputFile.mkdirs()) { "No se pudo crear una carpeta del modelo." }
                    } else {
                        check(outputFile.parentFile?.exists() == true || outputFile.parentFile?.mkdirs() == true) {
                            "No se pudo crear una carpeta del modelo."
                        }
                        FileOutputStream(outputFile).use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                expanded += count
                                require(expanded <= MAX_UNPACKED_BYTES) {
                                    "El contenido descomprimido del modelo supera el límite de seguridad."
                                }
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                    zip.closeEntry()
                }
            }
            val modelRoot = if (hasModelLayout(staging)) staging else
                staging.listFiles()?.firstOrNull { it.isDirectory && hasModelLayout(it) }
                    ?: error("El ZIP no contiene la estructura esperada del modelo Vosk español.")
            if (modelRoot == staging) {
                check(staging.renameTo(installTemp)) { "No se pudo preparar la instalación atómica del modelo." }
            } else {
                check(modelRoot.renameTo(installTemp)) { "No se pudo preparar la instalación atómica del modelo." }
                staging.deleteRecursively()
            }
            if (backup.exists()) backup.deleteRecursively()
            if (target.exists()) check(target.renameTo(backup)) { "No se pudo apartar la instalación anterior del modelo." }
            if (!installTemp.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                error("No se pudo activar la instalación del modelo.")
            }
            backup.deleteRecursively()
            check(hasModelLayout(target)) { "El modelo instalado no supera la comprobación de estructura." }
            runCatching { onProgress(100) }
            return target
        } finally {
            connection?.disconnect()
            archive.delete()
            staging.deleteRecursively()
            installTemp.deleteRecursively()
        }
    }

    /** Indicates whether a media container exposes at least one audio track. */
    fun hasAudioTrack(mediaFile: File): Boolean {
        if (!mediaFile.isFile || mediaFile.length() !in 1..MAX_MEDIA_BYTES) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(mediaFile.absolutePath)
            (0 until extractor.trackCount).any { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            }
        } catch (_: Exception) {
            false
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** Decodifica el primer flujo de audio usando códecs Android y transmite PCM a Vosk. */
    fun transcribeMediaFile(modelDirectory: File, mediaFile: File, onProgress: (Int) -> Unit = {}): String {
        require(hasModelLayout(modelDirectory)) { "El modelo Vosk español no está instalado o está incompleto." }
        require(mediaFile.isFile && mediaFile.length() in 1..MAX_MEDIA_BYTES) {
            "El audio no existe, está vacío o supera el límite de 100 MB."
        }
        val model = Model(modelDirectory.absolutePath)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false
        var recognizer: Recognizer? = null
        val transcript = StringBuilder()
        var decodedBytes = 0L
        var lastProgress = -1
        try {
            extractor.setDataSource(mediaFile.absolutePath)
            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    trackIndex = index
                    inputFormat = format
                    break
                }
            }
            require(trackIndex >= 0 && inputFormat != null) {
                "Android no ha encontrado una pista de audio decodificable en este archivo."
            }
            val selectedFormat = inputFormat ?: error("Formato de audio no disponible.")
            val mime = selectedFormat.getString(MediaFormat.KEY_MIME) ?: error("Códec de audio sin MIME.")
            val durationUs = if (selectedFormat.containsKey(MediaFormat.KEY_DURATION)) {
                selectedFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            require(durationUs <= 0L || durationUs <= MAX_DURATION_US) {
                "El audio supera el límite de 30 minutos por seguridad térmica y de memoria."
            }
            extractor.selectTrack(trackIndex)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(selectedFormat, null, null, 0)
            codec.start()
            codecStarted = true
            var inputDone = false
            var outputDone = false
            var sampleRate = 0
            var channels = 0
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            fun applyOutputFormat(format: MediaFormat) {
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else AudioFormat.ENCODING_PCM_16BIT
                require(sampleRate in 8_000..96_000) { "Frecuencia decodificada no compatible: " + sampleRate + " Hz." }
                require(channels in 1..8) { "Número de canales decodificados no compatible." }
                if (recognizer == null) recognizer = Recognizer(model, sampleRate.toFloat())
            }
            val info = MediaCodec.BufferInfo()
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex) ?: error("Búfer de entrada no disponible.")
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime
                            require(pts < 0L || pts <= MAX_DURATION_US) { "El audio supera el límite de 30 minutos." }
                            codec.queueInputBuffer(inputIndex, 0, size, pts.coerceAtLeast(0L), extractor.sampleFlags)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> applyOutputFormat(codec.outputFormat)
                    outputIndex >= 0 -> {
                        if (info.size > 0) {
                            if (sampleRate == 0) applyOutputFormat(codec.outputFormat)
                            val output = codec.getOutputBuffer(outputIndex) ?: error("Búfer de salida no disponible.")
                            val chunk = output.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
                                position(info.offset)
                                limit(info.offset + info.size)
                            }
                            val mono = convertToMonoPcm16(chunk.slice().order(ByteOrder.LITTLE_ENDIAN), channels, pcmEncoding)
                            decodedBytes += mono.size
                            require(decodedBytes <= MAX_DECODED_PCM_BYTES) { "Audio decodificado supera límite local." }
                            val active = recognizer ?: error("El reconocedor no pudo inicializarse.")
                            if (mono.isNotEmpty() && active.acceptWaveForm(mono, mono.size)) appendResult(transcript, active.result)
                            if (durationUs > 0L && info.presentationTimeUs > 0L) {
                                val pct = ((info.presentationTimeUs * 100L) / durationUs).toInt().coerceIn(0, 99)
                                if (pct != lastProgress) {
                                    lastProgress = pct
                                    runCatching { onProgress(pct) }
                                }
                            }
                        }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            val active = recognizer ?: error("No se recibió audio PCM del decodificador.")
            appendResult(transcript, active.finalResult)
            runCatching { onProgress(100) }
            return transcript.toString().trim().take(MAX_TRANSCRIPT_CHARS)
        } finally {
            runCatching { recognizer?.close() }
            runCatching { if (codecStarted) codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { model.close() }
        }
    }

    private fun appendResult(target: StringBuilder, json: String) {
        if (target.length >= MAX_TRANSCRIPT_CHARS) return
        val text = runCatching { JSONObject(json).optString("text").trim() }.getOrDefault("")
        if (text.isNotBlank()) {
            val remaining = MAX_TRANSCRIPT_CHARS - target.length
            if (remaining > 0) target.append(' ').append(text.take(remaining))
        }
    }

    internal fun convertToMonoPcm16(input: ByteBuffer, channels: Int, encoding: Int): ByteArray {
        require(channels in 1..8) { "Canales de audio no válidos." }
        val bytes = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> throw IllegalArgumentException("Formato PCM no soportado: $encoding.")
        }
        require(input.remaining() % (bytes * channels) == 0) { "El búfer PCM contiene una muestra incompleta." }
        val source = input.slice().order(ByteOrder.LITTLE_ENDIAN)
        val frames = source.remaining() / (bytes * channels)
        val output = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) {
            var sum = 0L
            repeat(channels) {
                val value = when (encoding) {
                    AudioFormat.ENCODING_PCM_8BIT -> ((source.get().toInt() and 0xff) - 128) shl 8
                    AudioFormat.ENCODING_PCM_16BIT -> source.getShort().toInt()
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                        val raw = (source.get().toInt() and 0xff) or
                            ((source.get().toInt() and 0xff) shl 8) or ((source.get().toInt() and 0xff) shl 16)
                        (if ((raw and 0x800000) != 0) raw or -0x1000000 else raw) shr 8
                    }
                    AudioFormat.ENCODING_PCM_32BIT -> source.getInt() shr 16
                    AudioFormat.ENCODING_PCM_FLOAT -> {
                        val sample = source.getFloat().coerceIn(-1.0f, 1.0f)
                        if (sample <= -1.0f) -32768 else (sample * 32767.0f).toInt()
                    }
                    else -> error("Formato PCM no soportado.")
                }
                sum += value.toLong()
            }
            output.putShort((sum / channels).coerceIn(-32768L, 32767L).toShort())
        }
        return output.array()
    }
}