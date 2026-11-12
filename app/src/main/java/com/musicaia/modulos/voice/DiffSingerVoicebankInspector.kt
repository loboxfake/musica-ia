package com.musicaia.modulos.voice

import java.io.File

/**
 * Inspecciona un paquete DiffSinger antes de permitir cargar modelos.
 * No descarga modelos, no ejecuta código del paquete y no afirma que una voz
 * esté licenciada: la autorización debe comprobarse por separado.
 */
object DiffSingerVoicebankInspector {
    enum class Status {
        READY_FOR_RUNTIME_PROBE, MISSING_CONFIG, INVALID_CONFIG,
        MISSING_ACOUSTIC_MODEL, MISSING_PHONEMES, MISSING_LANGUAGE_TABLE, MISSING_VOCODER
    }

    data class Inspection(
        val status: Status,
        val voicebankRoot: File,
        val acousticModel: File? = null,
        val phonemesFile: File? = null,
        val vocoderConfig: File? = null,
        val sampleRate: Int? = null,
        val hopSize: Int? = null,
        val errors: List<String> = emptyList()
    ) {
        val readyForRuntimeProbe: Boolean get() = status == Status.READY_FOR_RUNTIME_PROBE
    }

    private val scalarLine = Regex("^\\s*([A-Za-z0-9_]+)\\s*:\\s*(.*?)\\s*$")
    private val packageId = Regex("^[A-Za-z0-9._-]{1,128}$")

    fun inspect(voicebankRoot: File, dependencyRoot: File? = null): Inspection {
        val root = runCatching { voicebankRoot.canonicalFile }.getOrElse {
            return Inspection(Status.INVALID_CONFIG, voicebankRoot, errors = listOf("No se pudo resolver la carpeta de la voz."))
        }
        if (!root.isDirectory) return Inspection(Status.MISSING_CONFIG, root, errors = listOf("La carpeta de la voz no existe."))
        val config = File(root, "dsconfig.yaml")
        if (!config.isFile || config.length() <= 0L) {
            return Inspection(Status.MISSING_CONFIG, root, errors = listOf("Falta dsconfig.yaml."))
        }

        val values = linkedMapOf<String, String>()
        config.useLines { lines ->
            lines.forEach { raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty() || line.startsWith("---")) return@forEach
                val match = scalarLine.matchEntire(line) ?: return@forEach
                val value = match.groupValues[2].trim().removeSurrounding("\"").removeSurrounding("'")
                if (value.isNotEmpty() && value != "null" && value != "~") values[match.groupValues[1]] = value
            }
        }

        val acousticName = values["acoustic"]
        val phonemesName = values["phonemes"] ?: "phonemes.txt"
        val vocoderName = values["vocoder"]
        val sampleRate = values["sample_rate"]?.toIntOrNull() ?: 44100
        val hopSize = values["hop_size"]?.toIntOrNull() ?: 512
        if (acousticName.isNullOrBlank() || vocoderName.isNullOrBlank() ||
            !packageId.matches(vocoderName) || sampleRate !in 8000..192000 || hopSize !in 1..8192) {
            return Inspection(Status.INVALID_CONFIG, root, sampleRate = sampleRate, hopSize = hopSize,
                errors = listOf("dsconfig.yaml debe declarar acoustic, vocoder y valores válidos de sample_rate/hop_size."))
        }

        fun safeChild(base: File, relative: String): File? {
            if (relative.isBlank()) return null
            val canonicalBase = runCatching { base.canonicalFile }.getOrNull() ?: return null
            val candidate = runCatching { File(canonicalBase, relative).canonicalFile }.getOrNull() ?: return null
            val prefix = canonicalBase.path + File.separator
            return candidate.takeIf { it.path.startsWith(prefix) && it.isFile && it.length() > 0L }
        }

        val acoustic = safeChild(root, acousticName)
            ?: return Inspection(Status.MISSING_ACOUSTIC_MODEL, root, sampleRate = sampleRate, hopSize = hopSize,
                errors = listOf("No existe el modelo acústico ONNX indicado o la ruta sale de la carpeta de la voz."))
        val phonemes = safeChild(root, phonemesName)
            ?: return Inspection(Status.MISSING_PHONEMES, root, acoustic, sampleRate = sampleRate, hopSize = hopSize,
                errors = listOf("No existe el inventario de fonemas indicado o está vacío."))

        if (values["use_lang_id"]?.equals("true", ignoreCase = true) == true) {
            val languageFile = values["languages"]?.let { safeChild(root, it) }
            if (languageFile == null) {
                return Inspection(Status.MISSING_LANGUAGE_TABLE, root, acoustic, phonemes,
                    sampleRate = sampleRate, hopSize = hopSize,
                    errors = listOf("La voz usa identificadores de idioma pero falta el archivo languages."))
            }
        }

        val embeddedVocoder = File(root, "dsvocoder/vocoder.yaml")
        val externalVocoder = dependencyRoot?.takeIf { packageId.matches(vocoderName) }
            ?.let { File(it, vocoderName).resolve("vocoder.yaml") }
        val vocoder = listOfNotNull(embeddedVocoder, externalVocoder)
            .firstOrNull { it.isFile && it.length() > 0L }
        if (vocoder == null) {
            return Inspection(Status.MISSING_VOCODER, root, acoustic, phonemes,
                sampleRate = sampleRate, hopSize = hopSize,
                errors = listOf("No está instalado el vocoder '$vocoderName'; se requiere su configuración y modelo."))
        }

        return Inspection(Status.READY_FOR_RUNTIME_PROBE, root, acoustic, phonemes, vocoder, sampleRate, hopSize)
    }
}
