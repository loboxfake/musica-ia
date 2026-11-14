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
    fun isNegatedCreativeChange(message: String): Boolean {
        val normalized = Normalizer.normalize(
            message.lowercase(Locale.ROOT),
            Normalizer.Form.NFD
        ).replace(Regex("""\p{Mn}+"""), "")
        return Regex(
            """\b(?:no\s+(?:quiero|deseo|necesito)\s+(?:cambiar|modificar|reemplazar|sustituir)\w*|no\s+(?:hace\s+falta|es\s+necesario)\s+(?:cambiar|modificar|reemplazar|sustituir)\w*|(?:i\s+)?(?:do\s+not|don't|dont)\s+(?:want|need)\s+to\s+(?:change|switch|modify|replace)|not\s+necessary\s+to\s+(?:change|switch|modify|replace))\b"""
        ).containsMatchIn(normalized)
    }

    fun extractProvidedFields(message: String): Map<String, String> {
        // No convertir una frase de rechazo en nuevos valores de preferencia.
        // El parser generativo aplica la misma regla a sus campos estructurados.
        if (isNegatedCreativeChange(message)) return emptyMap()
        val result = linkedMapOf<String, String>()
        val aliases = listOf(
            "fuente\\s+de\\s+voz", "voz\\s+fuente", "tema", "estilo", "g[ée]nero",
            "ambiente", "emoci[oó]n", "tono", "ritmo", "tempo", "idioma", "lengua", "tipo"
        ).joinToString("|")
        val labelled = Regex(
            """(?i)\b($aliases)\b\s*(?::|=|\b(?:es|de|del)\b)?\s+(.+?)(?=(?:[,;.!?\n]|\s+\by\s+(?=(?:con\s+)?(?:ritmo|tempo|idioma|voz|estilo|ambiente)\b)|\s+\b(?:$aliases)\b\s*(?:[:=]|\s+))|$)"""
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
                .replace(Regex("^(es|de|del|sobre|en|el|la|los|las|musical|a)\\s+", RegexOption.IGNORE_CASE), "")
                .trim()
            if (raw.isNotBlank()) result[key] = normalizeFieldValue(key, raw)
        }

        if ("tema" !in result) {
            val genres = "rock\\s+alternativo|rock|pop|rap|hip[- ]?hop|trap|reggaeton|metal|indie|electr[oó]nica|house|techno|jazz|blues|soul|funk|reggae|folk|country|flamenco|salsa|bachata|cumbia|merengue|synthwave|lo[- ]?fi|l[oó]fi|ambient|punk|disco|trance|dnb|orquestal|cinem[aá]tico"
            val themeAfterGenre = Regex(
                """(?i)\b(?:una\s+)?canci[oó]n\s+de\s+(?:$genres)\s+(?:sobre|acerca\s+de|que\s+trate\s+de|que\s+hable\s+de)\s+(.+?)(?=[,;.!?\n]|\s+(?:que\s+(?:transmita|sea|tenga|vaya)|con\s+(?:un\s+)?(?:ritmo|ambiente|voz)|en\s+(?:espa[nñ]ol|castellano|ingl[eé]s)|(?:estilo|ambiente|ritmo|idioma|tipo)\s*[:=])|$)"""
            ).find(message)?.groupValues?.get(1)?.trim()
            val theme = themeAfterGenre ?: Regex(
                """(?i)^\s*(?:(?:quiero|necesito|hazme|crea)\s+)?(?:una\s+)?canci[oó]n\s+(?:sobre|acerca\s+de|que\s+trate\s+de|que\s+hable\s+de|de)\s+(.+?)(?=[,;.!?\n]|\s+(?:que\s+(?:transmita|sea|tenga|vaya)|con\s+(?:un\s+)?(?:ritmo|ambiente|voz)|en\s+(?:espa[nñ]ol|castellano|ingl[eé]s)|(?:estilo|ambiente|ritmo|idioma|tipo)\s*[:=])|$)"""
            ).find(message)?.groupValues?.get(1)?.trim()
            if (!theme.isNullOrBlank()) result["tema"] = theme
        }

        if ("idioma" !in result) {
            val language = Regex(
                """(?i)\b(?:en|idioma\s*[:=]?)\s*(español|castellano|inglés|ingles|francés|frances|italiano|portugués|portugues|alemán|aleman)\b"""
            ).find(message)?.groupValues?.get(1)
            if (!language.isNullOrBlank()) result["idioma"] = normalizeFieldValue("idioma", language)
        }

        if ("estilo" !in result) {
            val genrePattern = Regex(
                """(?i)\b(?:de|estilo|g[eé]nero|sonido)\s+(rock\s+alternativo|rock|pop|rap|hip[- ]?hop|trap|reggaeton|metal|indie|electr[oó]nica|house|techno|jazz|blues|soul|funk|reggae|folk|country|flamenco|salsa|bachata|cumbia|merengue|synthwave|lo[- ]?fi|l[oó]fi|ambient|punk|disco|trance|dnb|orquestal|cinem[aá]tico)\b"""
            )
            genrePattern.find(message)?.groupValues?.get(1)?.let { result["estilo"] = it }
        }

        if ("ambiente" !in result) {
            val moodPattern = Regex(
                """(?i)\b(?:que\s+(?:sea|transmita)|que\s+tenga\s+(?:(?:un|una)\s+)?(?:ambiente|tono|aire|sensaci[oó]n)(?:\s+de)?|con\s+(?:(?:un|una)\s+)?(?:ambiente|tono|aire|sensaci[oó]n)(?:\s+de)?)\s+(?:muy\s+|bastante\s+|algo\s+)?(alegre|esperanzador[ao]?|esperanza|inspirador[ao]?|inspiraci[oó]n|melancol[ií]a|nostalgia|optimista|positivo|triste|melanc[oó]lic[ao]|[eé]pic[ao]|motivador[ao]|rom[aá]ntic[ao]|oscur[ao]|energ[eé]tic[ao]|tranquil[ao]|nost[aá]lgic[ao]|agresiv[ao]|suave|intens[ao])\b"""
            )
            moodPattern.find(message)?.groupValues?.get(1)?.let { result["ambiente"] = it }
        }

        if ("ritmo" !in result) {
            val tempo = Regex("""(?i)\b(?:que\s+vaya|con\s+(?:un\s+)?ritmo)\s+(r[aá]pido|lento|medio|moderado|pausado|bailable|marcado)\b""")
                .find(message)?.groupValues?.get(1)
            val bpm = Regex("""(?i)\b(\d{2,3})\s*(?:bpm|pulsaciones\s+por\s+minuto)\b""")
                .find(message)?.groupValues?.get(1)?.let { "$it BPM" }
            (tempo ?: bpm)?.let { result["ritmo"] = it }
        }

        if ("tipo" !in result) {
            val normalized = normalize(message)
            val detectedType = when {
                Regex("""\b(ambas|voz e instrumental|voz e instrumentales)\b""").containsMatchIn(normalized) ->
                    "ambas (voz e instrumental)"
                Regex("""\b(solo instrumental|instrumental sin voz|sin voz|sin canto)\b""").containsMatchIn(normalized) ->
                    "solo instrumental"
                Regex("""\b(?:con voz|cantad[ao]|canto|vocal)\b""").containsMatchIn(normalized) -> "voz"
                else -> null
            }
            if (detectedType != null) result["tipo"] = detectedType
        }

        if ("tipo" in result && isInstrumentalOnlyType(result["tipo"].orEmpty())) {
            result["vozFuente"] = "ninguna"
        } else if ("vozFuente" !in result) {
            val normalized = normalize(message)
            val source = when {
                Regex("""\b(voz grabada|voz propia grabada|grabar(?:e|é) mi (?:propia )?voz|mi propia voz grabada|grabacion propia|mi propia grabacion|mi grabacion)\b""")
                    .containsMatchIn(normalized) -> "grabada"
                Regex("""\b(archivo de voz|voz en un archivo|fichero de voz|importar voz|subir(?:e|é) (?:un )?archivo de voz)\b""")
                    .containsMatchIn(normalized) -> "archivo"
                Regex("""\b(sin voz|ninguna voz|no quiero voz)\b""").containsMatchIn(normalized) -> "ninguna"
                else -> null
            }
            if (source != null) {
                result["vozFuente"] = source
                // Elegir una fuente de voz implica que la creación llevará voz,
                // salvo que el usuario haya indicado explícitamente otro tipo.
                if (source != "ninguna" && "tipo" !in result) result["tipo"] = "voz"
            }
        }
        val englishLabels = Regex(
            """(?i)\b(theme|style|genre|mood|rhythm|language|type|voice source)\s*[:=]\s*(.+?)(?=[,;.!?\n]|$)"""
        )
        englishLabels.findAll(message).forEach { match ->
            val key = when (match.groupValues[1].lowercase(Locale.ROOT)) {
                "theme" -> "tema"
                "style", "genre" -> "estilo"
                "mood" -> "ambiente"
                "rhythm" -> "ritmo"
                "language" -> "idioma"
                "type" -> "tipo"
                "voice source" -> "vozFuente"
                else -> return@forEach
            }
            if (key !in result) result[key] = normalizeFieldValue(key, match.groupValues[2].trim())
        }

        if ("tema" !in result) {
            val theme = Regex("""(?i)^\s*(?:(?:write|create|make|compose)\s+(?:me\s+)?(?:a\s+)?|i\s+want\s+(?:to\s+)?(?:write|create|make)\s+(?:a\s+)?)?(?:song|track)\s+(?:about|on)\s+(.+?)(?=[,;.!?\n]|$)""")
                .find(message)?.groupValues?.get(1)?.trim()
            if (!theme.isNullOrBlank()) result["tema"] = theme
        }
        if ("estilo" !in result) {
            val genre = Regex("""(?i)\b(alternative\s+rock|rock|pop|rap|hip[- ]?hop|trap|metal|indie|electronic|house|techno|jazz|blues|soul|funk|reggae|folk|country|synthwave|lo[- ]?fi|ambient|punk|disco|trance)\s*(?=[,;.]|\s+(?:song|track)\b)""")
                .find(message)?.groupValues?.get(1)
            if (!genre.isNullOrBlank()) result["estilo"] = genre
        }
        if ("ambiente" !in result) {
            val mood = Regex("""(?i)\b(hopeful|happy|sad|melancholic|epic|motivational|romantic|dark|energetic|calm|nostalgic|aggressive|soft|intense)\s+(?:mood|feeling|atmosphere)\b""")
                .find(message)?.groupValues?.get(1)
            if (!mood.isNullOrBlank()) result["ambiente"] = mood
        }
        if ("ritmo" !in result) {
            val tempo = Regex("""(?i)\b(fast|slow|medium|steady|danceable)\s+(?:tempo|rhythm)\b""")
                .find(message)?.groupValues?.get(1)
                ?: Regex("""(?i)\b(\d{2,3})\s*(?:bpm|beats\s+per\s+minute)\b""")
                    .find(message)?.groupValues?.get(1)?.let { "$it BPM" }
            if (!tempo.isNullOrBlank()) result["ritmo"] = tempo
        }
        if ("idioma" !in result) {
            val language = Regex("""(?i)\b(?:in|language)\s+(english|spanish)\b""")
                .find(message)?.groupValues?.get(1)
            if (!language.isNullOrBlank()) result["idioma"] = normalizeFieldValue("idioma", language)
        }
        if ("tipo" !in result) {
            val type = when {
                Regex("""(?i)\b(instrumental\s+only|no\s+vocals|without\s+vocals)\b""").containsMatchIn(message) -> "solo instrumental"
                Regex("""(?i)\b(both\s+vocals\s+and\s+instrumental)\b""").containsMatchIn(message) -> "ambas (voz e instrumental)"
                Regex("""(?i)\b(with\s+vocals)\b""").containsMatchIn(message) -> "voz"
                else -> null
            }
            if (type != null) result["tipo"] = type
        }
        return result
    }

    private fun normalizeFieldValue(key: String, value: String): String {
        if (key == "vozFuente") {
            val normalized = normalize(value)
            return when {
                normalized.contains("ningun") || normalized.contains("sin voz") || normalized.contains("no voice") || normalized == "none" || normalized == "no" -> "ninguna"
                normalized.contains("archivo") || normalized.contains("fichero") || normalized.contains("importar") || normalized.contains("file") -> "archivo"
                normalized.contains("grabad") || normalized.contains("grabar") || normalized.contains("record") || normalized.contains("microphone") -> "grabada"
                else -> value
            }
        }
        if (key == "tipo") {
            val normalized = normalize(value)
            return when {
                isInstrumentalOnlyType(value) || normalized.contains("no vocals") ||
                    normalized == "instrumental only" || normalized == "without vocals" -> "solo instrumental"
                normalized.contains("both vocals and instrumental") -> "ambas (voz e instrumental)"
                normalized.contains("ambas") || normalized.contains("voz e instrumental") -> "ambas (voz e instrumental)"
                normalized == "voice" || normalized == "vocals" || normalized == "with vocals" ||
                    normalized == "cantada" || normalized == "cantado" || normalized == "con canto" ||
                    normalized == "cancion cantada" || normalized == "vocal" -> "voz"
                else -> value
            }
        }
        if (key == "idioma") return canonicalSongLanguage(value) ?: value
        return value
    }

    /** Detecta español o inglés para adaptar el diálogo guiado; los mensajes ambiguos no cambian el idioma. */
    fun detectConversationLanguage(message: String): String? {
        val tokens = normalize(message).split(' ').filter { it.isNotBlank() }.toSet()
        val englishMarkers = setOf(
            "the", "and", "with", "about", "song", "track", "write", "create", "make",
            "compose", "style", "genre", "mood", "rhythm", "tempo", "language", "type",
            "please", "want", "need", "english", "yes"
        )
        val spanishMarkers = setOf(
            "la", "el", "una", "un", "y", "con", "sobre", "cancion", "tema", "quiero",
            "crear", "hazme", "estilo", "genero", "ambiente", "ritmo", "idioma", "tipo",
            "espanol", "ingles", "si", "necesito"
        )
        val englishScore = tokens.count { it in englishMarkers }
        val spanishScore = tokens.count { it in spanishMarkers }
        return when {
            englishScore >= 2 && englishScore > spanishScore -> "en"
            spanishScore >= 2 && spanishScore > englishScore -> "es"
            tokens.contains("english") -> "en"
            tokens.contains("espanol") || tokens.contains("castellano") -> "es"
            else -> null
        }
    }

    fun detectGuidedLanguage(message: String): String? {
        val text = normalize(message)
        if (text in setOf("english", "en", "en us", "en gb")) return "en"
        if (text in setOf("espanol", "castellano", "spanish", "es")) return "es"
        val words = text.split(' ').filter { it.isNotBlank() }.toSet()
        val englishSignals = setOf("write", "create", "compose", "make", "song", "track", "about", "theme", "style", "genre", "mood", "rhythm", "tempo", "language", "type", "with", "hopeful", "happy", "sad", "fast", "slow", "medium", "instrumental", "vocals", "change", "please")
        val spanishSignals = setOf("quiero", "hazme", "crea", "componer", "cancion", "musica", "sobre", "tema", "estilo", "genero", "ambiente", "emocion", "ritmo", "tempo", "idioma", "tipo", "voz", "instrumental", "alegre", "triste", "esperanzador", "rapido", "lento", "medio", "cambiar", "para")
        val englishScore = words.count { it in englishSignals }
        val spanishScore = words.count { it in spanishSignals }
        return when {
            englishScore >= 2 && englishScore > spanishScore -> "en"
            spanishScore >= 2 && spanishScore > englishScore -> "es"
            else -> null
        }
    }

    fun canonicalSongLanguage(value: String): String? = when (normalize(value)) {
        "es", "spanish", "espanol", "castellano" -> "Español"
        "en", "en us", "en gb", "english", "ingles" -> "English"
        else -> null
    }

    fun isSupportedSongLanguage(value: String): Boolean = canonicalSongLanguage(value) != null

    /** Devuelve el primer dato obligatorio pendiente; voz solo se solicita cuando se eligió voz. */
    fun nextRequiredField(values: Map<String, String>): String? {
        requiredCreationFields.firstOrNull {
            if (it == "idioma") !isSupportedSongLanguage(values[it].orEmpty())
            else values[it].isNullOrBlank()
        }?.let { return it }
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

    /**
     * Returns the field requested for change when the user has not supplied
     * its new value yet. Voice here means the vocal/instrumental type.
     */
    fun requestedFieldChangeWithoutValue(message: String): String? {
        val text = normalize(message)
        val match = Regex(
            "^(?:cambiar|cambia|modificar|modifica|change|switch)\\s+(?:(?:el|la|the)\\s+)?(tema|theme|estilo|style|genre|ambiente|mood|ritmo|rhythm|idioma|language|tipo|type|voz|voice)$"
        ).matchEntire(text) ?: return null
        return when (match.groupValues[1]) {
            "tema", "theme" -> "tema"
            "estilo", "style", "genre" -> "estilo"
            "ambiente", "mood" -> "ambiente"
            "ritmo", "rhythm" -> "ritmo"
            "idioma", "language" -> "idioma"
            "tipo", "type", "voz", "voice" -> "tipo"
            else -> null
        }
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

        if (creationInProgress && Regex("^(cambiar|cambia|modificar|modifica|change|switch)\\s+(?:(?:el|la|the)\\s+)?(tema|theme|estilo|style|genre|ambiente|mood|ritmo|rhythm|idioma|language|tipo|type|voz|voice)\\b")
                .containsMatchIn(text)) return Intent.CHANGE_CREATION

        if (creationInProgress && text in setOf("crear", "crear cancion", "crear musica", "si", "continuar", "adelante", "create", "continue", "yes"))
            return Intent.CONFIRM_CREATION

        val explicitMusicRequest = listOf(
            "quiero crear una cancion", "quiero hacer una cancion", "quiero componer",
            "hazme una cancion", "crea una cancion", "compon una cancion",
            "necesito una cancion", "vamos a crear una cancion", "vamos a hacer una cancion",
            "una cancion sobre", "cancion sobre", "crear instrumental", "quiero un instrumental",
            "crear musica", "quiero crear musica", "quiero hacer musica", "reanudar creacion",
            "continuar creacion", "write a song", "create a song", "make a song", "make me a song",
            "compose a song", "compose me a song", "write me a song", "write a track", "i want a song",
            "create an instrumental", "make an instrumental", "song about", "track about"
        )
        if (explicitMusicRequest.any { text.contains(it) }) return Intent.MUSIC_CREATION

        // A user may ask an unrelated question without abandoning the active
        // creation. Do not consume that question as the value of the next field.
        if (creationInProgress && isStandaloneQuestion(message, text)) return Intent.GENERAL_CHAT
        if (creationInProgress) return Intent.MUSIC_CREATION
        return Intent.GENERAL_CHAT
    }

    private fun isStandaloneQuestion(original: String, normalized: String): Boolean {
        if ('?' !in original) return false
        // Phrases such as "que sea alegre" are creative answers, not questions.
        if (Regex("^que\\s+(?:sea|vaya|tenga|suene|resulte)\\b").containsMatchIn(normalized)) return false
        return Regex(
            "^(?:que|cual|como|por que|para que|cuando|donde|quien|which|what|how|why|when|where|who|can|could|would|should|is|are|do|does|did)\\b"
        ).containsMatchIn(normalized)
    }

    private fun normalize(value: String): String = Normalizer
        .normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
