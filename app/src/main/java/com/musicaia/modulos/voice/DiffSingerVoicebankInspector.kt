package com.musicaia.modulos.voice

import java.io.File
import kotlin.math.abs

/**
 * Inspecciona paquetes DiffSinger antes de cargar modelos.
 * No descarga modelos, no ejecuta código del paquete y no afirma que una voz
 * esté licenciada: la autorización debe comprobarse por separado.
 */
object DiffSingerVoicebankInspector {
    enum class Status {
        READY_FOR_RUNTIME_PROBE,
        MISSING_CONFIG,
        INVALID_CONFIG,
        MISSING_ACOUSTIC_MODEL,
        MISSING_PHONEMES,
        MISSING_PHONEMIZER_DICTIONARY,
        MISSING_LANGUAGE_TABLE,
        MISSING_AUXILIARY_MODEL,
        MISSING_VOCODER,
        MISSING_VOCODER_MODEL,
        INCOMPATIBLE_VOCODER
    }

    data class Inspection(
        val status: Status,
        val voicebankRoot: File,
        val acousticModel: File? = null,
        val phonemesFile: File? = null,
        val phonemizerDictionary: File? = null,
        val vocoderConfig: File? = null,
        val vocoderModel: File? = null,
        val sampleRate: Int? = null,
        val hopSize: Int? = null,
        val errors: List<String> = emptyList()
    ) {
        val readyForRuntimeProbe: Boolean get() = status == Status.READY_FOR_RUNTIME_PROBE
    }

    private val scalarLine = Regex("^\\s*([A-Za-z0-9_]+)\\s*:\\s*(.*?)\\s*$")
    private val packageId = Regex("^[A-Za-z0-9._-]{1,128}$")
    private const val MAX_CONFIG_BYTES = 1024L * 1024L

    fun inspect(
        voicebankRoot: File,
        dependencyRoot: File? = null,
        language: String = "es"
    ): Inspection {
        val root = runCatching { voicebankRoot.canonicalFile }.getOrElse {
            return Inspection(Status.INVALID_CONFIG, voicebankRoot, errors = listOf("No se pudo resolver la carpeta de la voz."))
        }
        if (!root.isDirectory) return Inspection(Status.MISSING_CONFIG, root, errors = listOf("La carpeta de la voz no existe."))
        val config = File(root, "dsconfig.yaml")
        if (!config.isFile || config.length() <= 0L) {
            return Inspection(Status.MISSING_CONFIG, root, errors = listOf("Falta dsconfig.yaml."))
        }
        if (config.length() > MAX_CONFIG_BYTES) {
            return Inspection(Status.INVALID_CONFIG, root, errors = listOf("dsconfig.yaml supera el límite de tamaño seguro."))
        }

        fun parseScalars(file: File): Map<String, String> {
            val values = linkedMapOf<String, String>()
            file.useLines { lines ->
                lines.forEach { raw ->
                    val line = raw.substringBefore('#').trim()
                    if (line.isEmpty() || line.startsWith("---")) return@forEach
                    val match = scalarLine.matchEntire(line) ?: return@forEach
                    val value = match.groupValues[2].trim().removeSurrounding("\"").removeSurrounding("'")
                    if (value.isNotEmpty() && value != "null" && value != "~") values[match.groupValues[1]] = value
                }
            }
            return values
        }

        fun safeChild(base: File, relative: String): File? {
            if (relative.isBlank()) return null
            val canonicalBase = runCatching { base.canonicalFile }.getOrNull() ?: return null
            val candidate = runCatching { File(canonicalBase, relative).canonicalFile }.getOrNull() ?: return null
            val prefix = canonicalBase.path + File.separator
            return candidate.takeIf { it.path.startsWith(prefix) && it.isFile && it.length() > 0L }
        }

        val values = runCatching { parseScalars(config) }.getOrElse {
            return Inspection(Status.INVALID_CONFIG, root, errors = listOf("No se pudo interpretar dsconfig.yaml."))
        }
        val acousticName = values["acoustic"]
        val phonemesName = values["phonemes"] ?: "phonemes.txt"
        val vocoderName = values["vocoder"]
        val sampleRate = values["sample_rate"]?.toIntOrNull() ?: if ("sample_rate" in values) 0 else 44100
        val hopSize = values["hop_size"]?.toIntOrNull() ?: if ("hop_size" in values) 0 else 512
        val winSize = values["win_size"]?.toIntOrNull() ?: if ("win_size" in values) 0 else 2048
        val fftSize = values["fft_size"]?.toIntOrNull() ?: if ("fft_size" in values) 0 else 2048
        val melBins = values["num_mel_bins"]?.toIntOrNull() ?: if ("num_mel_bins" in values) 0 else 128
        val melMin = values["mel_fmin"]?.toDoubleOrNull() ?: if ("mel_fmin" in values) Double.NaN else 40.0
        val melMax = values["mel_fmax"]?.toDoubleOrNull() ?: if ("mel_fmax" in values) Double.NaN else 16000.0
        val melBase = values["mel_base"] ?: "10"
        val melScale = values["mel_scale"] ?: "slaney"

        if (acousticName.isNullOrBlank() || vocoderName.isNullOrBlank() ||
            !packageId.matches(vocoderName) || !acousticName.endsWith(".onnx", true) ||
            sampleRate !in 8000..192000 || hopSize !in 1..8192 || winSize !in 1..16384 ||
            fftSize !in 1..16384 || melBins !in 1..255 || !melMin.isFinite() || !melMax.isFinite() ||
            melMin < 0 || melMax <= melMin || melBase !in setOf("10", "e") || melScale !in setOf("slaney", "htk")) {
            return Inspection(Status.INVALID_CONFIG, root, sampleRate = sampleRate, hopSize = hopSize,
                errors = listOf("dsconfig.yaml contiene rutas o parámetros acústicos inválidos/incompletos."))
        }

        val acoustic = safeChild(root, acousticName)
            ?.takeIf { it.extension.equals("onnx", ignoreCase = true) }
            ?: return Inspection(Status.MISSING_ACOUSTIC_MODEL, root, sampleRate = sampleRate, hopSize = hopSize,
                errors = listOf("No existe el modelo acústico ONNX indicado o la ruta sale de la carpeta de la voz."))

        val phonemes = safeChild(root, phonemesName)
            ?: return Inspection(Status.MISSING_PHONEMES, root, acoustic, sampleRate = sampleRate, hopSize = hopSize,
                errors = listOf("No existe el inventario de fonemas indicado o está vacío."))

        val normalizedLanguage = language.lowercase().filter { it.isLetterOrDigit() }
        val dictionary = listOf("dsdict-$normalizedLanguage.yaml", "dsdict.yaml")
            .firstNotNullOfOrNull { safeChild(root, it) }
        if (dictionary == null) {
            return Inspection(Status.MISSING_PHONEMIZER_DICTIONARY, root, acoustic, phonemes,
                sampleRate = sampleRate, hopSize = hopSize,
                errors = listOf("Falta dsdict-$normalizedLanguage.yaml o dsdict.yaml para convertir la letra en fonemas."))
        }

        if (values["use_lang_id"]?.equals("true", ignoreCase = true) == true) {
            val languageFile = values["languages"]?.let { safeChild(root, it) }
            if (languageFile == null) {
                return Inspection(Status.MISSING_LANGUAGE_TABLE, root, acoustic, phonemes, dictionary,
                    sampleRate = sampleRate, hopSize = hopSize,
                    errors = listOf("La voz usa identificadores de idioma pero falta el archivo languages."))
            }
        }

        listOf("dur", "linguistic", "pitch", "variance").forEach { key ->
            val relative = values[key] ?: return@forEach
            if (safeChild(root, relative) == null) {
                return Inspection(Status.MISSING_AUXILIARY_MODEL, root, acoustic, phonemes, dictionary,
                    sampleRate = sampleRate, hopSize = hopSize,
                    errors = listOf("Falta el modelo auxiliar '$key' indicado en dsconfig.yaml."))
            }
        }

        val embeddedVocoder = safeChild(root, "dsvocoder/vocoder.yaml")
        val externalBase = dependencyRoot?.let { runCatching { it.canonicalFile }.getOrNull() }
        val externalVocoder = externalBase?.takeIf { packageId.matches(vocoderName) }
            ?.let { safeChild(it, "$vocoderName/vocoder.yaml") }
        val vocoderConfig = embeddedVocoder ?: externalVocoder
            ?: return Inspection(Status.MISSING_VOCODER, root, acoustic, phonemes, dictionary,
                sampleRate = sampleRate, hopSize = hopSize,
                errors = listOf("No está instalado el vocoder '$vocoderName' o falta su vocoder.yaml."))

        if (vocoderConfig.length() > MAX_CONFIG_BYTES) {
            return Inspection(Status.INVALID_CONFIG, root, acoustic, phonemes, dictionary, vocoderConfig,
                sampleRate = sampleRate, hopSize = hopSize,
                errors = listOf("vocoder.yaml supera el límite de tamaño seguro."))
        }
        val vocoderValues = runCatching { parseScalars(vocoderConfig) }.getOrElse {
            return Inspection(Status.INVALID_CONFIG, root, acoustic, phonemes, dictionary, vocoderConfig,
                sampleRate = sampleRate, hopSize = hopSize, errors = listOf("No se pudo interpretar vocoder.yaml."))
        }
        val vocoderModelName = vocoderValues["model"] ?: "model.onnx"
        val vocoderModel = safeChild(vocoderConfig.parentFile, vocoderModelName)
            ?.takeIf { it.extension.equals("onnx", ignoreCase = true) }
            ?: return Inspection(Status.MISSING_VOCODER_MODEL, root, acoustic, phonemes, dictionary, vocoderConfig,
                sampleRate = sampleRate, hopSize = hopSize,
                errors = listOf("No existe el modelo ONNX del vocoder o su ruta no es segura."))

        fun intConfig(map: Map<String, String>, key: String, default: Int): Int =
            map[key]?.toIntOrNull() ?: if (key in map) Int.MIN_VALUE else default
        fun doubleConfig(map: Map<String, String>, key: String, default: Double): Double =
            map[key]?.toDoubleOrNull() ?: if (key in map) Double.NaN else default

        val compatible =
            intConfig(vocoderValues, "sample_rate", 44100) == sampleRate &&
            intConfig(vocoderValues, "hop_size", 512) == hopSize &&
            intConfig(vocoderValues, "win_size", 2048) == winSize &&
            intConfig(vocoderValues, "fft_size", 2048) == fftSize &&
            intConfig(vocoderValues, "num_mel_bins", 128) == melBins &&
            abs(doubleConfig(vocoderValues, "mel_fmin", 40.0) - melMin) <= 1e-5 &&
            abs(doubleConfig(vocoderValues, "mel_fmax", 16000.0) - melMax) <= 1e-5 &&
            (vocoderValues["mel_base"] ?: "10") == melBase &&
            (vocoderValues["mel_scale"] ?: "slaney") == melScale
        if (!compatible) {
            return Inspection(Status.INCOMPATIBLE_VOCODER, root, acoustic, phonemes, dictionary,
                vocoderConfig, vocoderModel, sampleRate, hopSize,
                errors = listOf("El vocoder y el modelo acústico no coinciden en sample_rate/hop_size/FFT/mel; no se deben mezclar."))
        }

        return Inspection(Status.READY_FOR_RUNTIME_PROBE, root, acoustic, phonemes, dictionary,
            vocoderConfig, vocoderModel, sampleRate, hopSize)
    }
}
