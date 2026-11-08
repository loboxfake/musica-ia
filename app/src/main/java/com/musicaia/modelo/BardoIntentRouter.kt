package com.musicaia.modelo

import java.text.Normalizer
import java.util.Locale

/** Clasifica la intención antes de activar una skill; la música no secuestra el chat general. */
object BardoIntentRouter {
    enum class Intent {
        GENERAL_CHAT,
        MUSIC_CREATION,
        OWN_LYRICS,
        CONFIRM_CREATION,
        CHANGE_CREATION,
        END_CREATION
    }

    private val requiredCreationFields = listOf("tema", "estilo", "ambiente", "ritmo", "idioma", "tipo")

    /**
     * Extrae solo campos indicados explícitamente. No deduce estilo, emoción o ritmo
     * a partir de palabras aisladas; los datos ambiguos permanecen para la pregunta guiada.
     */
    fun extractProvidedFields(message: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val aliases = listOf(
            "fuente\\s+de\\s+voz", "voz\\s+fuente", "tema", "estilo", "g[ée]nero",
            "ambiente", "emoci[oó]n", "tono", "ritmo", "tempo", "idioma", "lengua", "tipo"
        ).joinToString("|")
        val labelled = Regex(
            """(?i)\b($aliases)\b\s*(?::|=|\b(?:es|de|del)\b)?\s+(.+?)(?=(?:[,;.!?\n]|\s+\b(?:$aliases)\b\s*(?:[:=]|\s+))|$)"""
        )
        labelled.findAll(message).forEach { match ->
            val alias = normalize(match.groupValues[1]).replace(Regex("\\s+"), " ")
            val key = when (alias) {
                "genero" -> "estilo"
                "emocion", "tono" -> "ambiente"
                "tempo" -> "ritmo"
                "lengua" -> "idioma"
                "fuente de voz", "voz fuente" -> "vozFuente"
                else -> alias
            }
            val raw = match.groupValues[2].trim().trimEnd('.', '!', '?')
                .replace(Regex("^(es|de|del|sobre|en|el|la|los|las|musical)\\s+", RegexOption.IGNORE_CASE), "")
                .trim()
            if (raw.isNotBlank()) result[key] = normalizeFieldValue(key, raw)
        }

        if ("tema" !in result) {
            val theme = Regex(
                """(?i)^\s*(?:(?:quiero|necesito|hazme|crea)\s+)?(?:una\s+)?canci[oó]n\s+(?:sobre|de)\s+(.+?)(?=[,;.!?\n]|$)"""
            ).find(message)?.groupValues?.get(1)?.trim()
            if (!theme.isNullOrBlank()) result["tema"] = theme
        }

        if ("idioma" !in result) {
            val language = Regex(
                """(?i)\b(?:en|idioma\s*[:=]?)\s*(español|castellano|inglés|ingles|francés|frances|italiano|portugués|portugues|alemán|aleman)\b"""
            ).find(message)?.groupValues?.get(1)
            if (!language.isNullOrBlank()) result["idioma"] = language
        }

        if ("tipo" !in result) {
            val normalized = normalize(message)
            val detectedType = when {
                Regex("""\b(ambas|voz e instrumental|voz e instrumentales)\b""").containsMatchIn(normalized) ->
                    "ambas (voz e instrumental)"
                Regex("""\b(solo instrumental|instrumental sin voz|sin voz|sin canto)\b""").containsMatchIn(normalized) ->
                    "solo instrumental"
                Regex("""\bcon voz\b""").containsMatchIn(normalized) -> "voz"
                else -> null
            }
            if (detectedType != null) result["tipo"] = detectedType
        }

        if ("tipo" in result && isInstrumentalOnlyType(result["tipo"].orEmpty())) {
            result["vozFuente"] = "ninguna"
        } else if ("vozFuente" !in result) {
            val normalized = normalize(message)
            val source = when {
                Regex("""\b(voz grabada|voz propia grabada|grabare mi voz|grabacion propia)\b""").containsMatchIn(normalized) -> "grabada"
                Regex("""\b(archivo de voz|voz en un archivo|fichero de voz|importar voz)\b""").containsMatchIn(normalized) -> "archivo"
                Regex("""\b(sin voz|ninguna voz|no quiero voz)\b""").containsMatchIn(normalized) -> "ninguna"
                else -> null
            }
            if (source != null) result["vozFuente"] = source
        }
        return result
    }

    private fun normalizeFieldValue(key: String, value: String): String {
        if (key == "vozFuente") {
            val normalized = normalize(value)
            return when {
                normalized.contains("ningun") || normalized.contains("sin voz") || normalized == "no" -> "ninguna"
                normalized.contains("archivo") || normalized.contains("fichero") || normalized.contains("importar") -> "archivo"
                normalized.contains("grabad") || normalized.contains("grabar") -> "grabada"
                else -> value
            }
        }
        if (key == "tipo") {
            return when {
                isInstrumentalOnlyType(value) -> "solo instrumental"
                normalize(value).contains("ambas") || normalize(value).contains("voz e instrumental") ->
                    "ambas (voz e instrumental)"
                else -> value
            }
        }
        return value
    }

    /** Devuelve el primer dato obligatorio pendiente; voz solo se solicita cuando se eligió voz. */
    fun nextRequiredField(values: Map<String, String>): String? {
        requiredCreationFields.firstOrNull { values[it].isNullOrBlank() }?.let { return it }
        return if (requiresUserVoice(values["tipo"].orEmpty()) && values["vozFuente"].isNullOrBlank()) {
            "vozFuente"
        } else null
    }

    /** "Instrumental" means no generated lyric/vocal module is selected. */
    fun isInstrumentalOnlyType(value: String): Boolean {
        val text = normalize(value)
        return text.contains("instrumental") && !text.contains("ambas") &&
            (!text.contains("voz") || text.contains("sin voz") || text.contains("sin canto"))
    }

    /** Voice/duet choices require an actual user recording until vocal synthesis exists. */
    fun requiresUserVoice(value: String): Boolean {
        val text = normalize(value)
        return text.contains("ambas") || (text.contains("voz") &&
            !text.contains("sin voz") && !text.contains("sin canto"))
    }

    fun classify(message: String, creationInProgress: Boolean): Intent {
        val text = normalize(message)
        if (text.isBlank()) return Intent.GENERAL_CHAT

        if (listOf(
                "salir de la creacion", "cancelar creacion", "cancelar la cancion",
                "volver al chat general", "volver a conversacion general",
                "dejar el modo musical", "terminar modo musical"
            ).any { text.contains(it) }) return Intent.END_CREATION

        if (listOf("mi letra", "texto propio", "pegar mi letra", "tengo mi letra")
                .any { text.contains(it) }) return Intent.OWN_LYRICS

        if (creationInProgress && Regex("^(cambiar|cambia|modificar|modifica)\\s+(?:(?:el|la)\\s+)?(tema|estilo|ambiente|ritmo|idioma|tipo|voz)\\b")
                .containsMatchIn(text)) return Intent.CHANGE_CREATION

        if (creationInProgress && text in setOf("crear", "crear cancion", "crear musica", "si", "continuar", "adelante"))
            return Intent.CONFIRM_CREATION

        val explicitMusicRequest = listOf(
            "quiero crear una cancion", "quiero hacer una cancion", "quiero componer",
            "hazme una cancion", "crea una cancion", "compon una cancion",
            "necesito una cancion", "vamos a crear una cancion", "vamos a hacer una cancion",
            "una cancion sobre", "cancion sobre", "crear instrumental", "quiero un instrumental",
            "crear musica", "quiero crear musica", "quiero hacer musica", "reanudar creacion",
            "continuar creacion"
        )
        if (explicitMusicRequest.any { text.contains(it) }) return Intent.MUSIC_CREATION

        if (creationInProgress) return Intent.MUSIC_CREATION
        return Intent.GENERAL_CHAT
    }

    private fun normalize(value: String): String = Normalizer
        .normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
