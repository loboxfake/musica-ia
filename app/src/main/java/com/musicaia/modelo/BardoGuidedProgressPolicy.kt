package com.musicaia.modelo

/**
 * Evita que una respuesta generativa interrumpa silenciosamente el cuestionario.
 * Cuando falta un campo, la respuesta del modelo solo se acepta si formula una
 * pregunta sobre ese campo. Los resúmenes previos a crear siempre son deterministas.
 */
object BardoGuidedProgressPolicy {
    fun selectReply(
        generatedReply: String?,
        deterministicFallback: String,
        requiredNextField: String?,
        requireSummary: Boolean = false
    ): String {
        require(deterministicFallback.isNotBlank()) {
            "La respuesta guiada de seguridad no puede estar vacía."
        }
        val candidate = generatedReply?.trim().orEmpty()
        if (candidate.isBlank() || requireSummary) return deterministicFallback
        if (requiredNextField == null) return candidate
        return if (asksAboutRequiredField(candidate, requiredNextField)) {
            candidate
        } else {
            deterministicFallback
        }
    }

    fun nextRequiredField(values: Map<String, String>, requestedField: String?): String? {
        val requested = requestedField?.takeIf { it.isNotBlank() && values[it].isNullOrBlank() }
        return requested ?: BardoIntentRouter.nextRequiredField(values)
    }

    fun asksAboutRequiredField(reply: String, field: String): Boolean {
        if (reply.isBlank() || reply.count { it == '?' } != 1) return false
        val normalized = java.text.Normalizer.normalize(
            reply.lowercase(java.util.Locale.ROOT),
            java.text.Normalizer.Form.NFD
        ).replace(Regex("""\p{Mn}+"""), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
        if (field == "tema") {
            // "¿Qué canción te gusta?" menciona música, pero no pregunta
            // por el asunto que debe desarrollar la canción del usuario.
            return Regex(
                """\b(?:de que|sobre que|que tema|cual es el tema|what topic|which topic|about what|what is (?:the )?(?:song|track) about|what should (?:the )?(?:song|track) be about|what would you like (?:the )?(?:song|track) to be about)\b"""
            ).containsMatchIn(normalized) || Regex(
                """\b(?:que historia (?:quieres|te gustaria|deseas) contar|que mensaje (?:quieres|te gustaria) transmitir|que quieres (?:contar|transmitir)|what story (?:should (?:the )?(?:song|track) tell|would you like (?:the )?(?:song|track) to tell)|what message (?:should (?:the )?(?:song|track) convey|would you like (?:the )?(?:song|track) to convey)|what do you want to express)\b"""
            ).containsMatchIn(normalized)
        }
        if (field == "tipo") {
            // "¿Qué tipo de voz prefieres?" pregunta por el timbre vocal,
            // no por la decisión obligatoria entre voz/canto e instrumental.
            if (Regex("""\b(?:tipo de voz|type of voice|which voice|que voz)\b""").containsMatchIn(normalized)) {
                return false
            }
            val asksVocalMode = Regex(
                """\b(?:voz|vocal|vocals|cantad[ao]|canto|singing|instrumental|sin voz|no vocals|with vocals)\b"""
            ).containsMatchIn(normalized)
            val offersBothModes = Regex(
                """\b(?:o|u|or)\b.*\b(?:instrumental|voz|vocal|cantad[ao]|singing)\b|\b(?:instrumental|voz|vocal|cantad[ao]|singing)\b.*\b(?:o|u|or)\b"""
            ).containsMatchIn(normalized)
            return asksVocalMode && (offersBothModes || Regex(
                """\b(?:con voz|solo instrumental|sin voz|no vocals|with vocals|instrumental only)\b"""
            ).containsMatchIn(normalized))
        }
        if (field == "idioma") {
            // No basta con mencionar "idioma": la pregunta debe pedir la lengua
            // de la canción o proponer una elección explícita para esa canción.
            val asksWhichLanguage = Regex(
                """\b(?:que|cual)\s+(?:idioma|lengua)\b|\b(?:en\s+que)\s+(?:idioma|lengua)\b|\b(?:which|what)\s+language\b"""
            ).containsMatchIn(normalized)
            val offersSongLanguage = Regex(
                """\b(?:la|el|esta|this|the)\s+(?:quieres|prefieres|imaginas|want|would\s+you\s+like)\b.{0,45}\b(?:en|in)\s+(?:espanol|spanish|english|ingles)\b|\b(?:en|in)\s+(?:espanol|spanish|english|ingles)\b"""
            ).containsMatchIn(normalized)
            return asksWhichLanguage || offersSongLanguage
        }
        if (field == "estilo") {
            return Regex(
                """\b(?:que|cual)\s+(?:estilo|genero|tipo\s+de\s+musica)\b|\b(?:which|what)\s+(?:musical\s+)?(?:style|genre)\b|\bwhat\s+kind\s+of\s+music\b"""
            ).containsMatchIn(normalized)
        }
        if (field == "ambiente") {
            return Regex(
                """\b(?:que|cual)\s+(?:ambiente|emocion|sentimiento|sensacion|tono|energia|atmosfera)\b.{0,55}\b(?:quieres|buscas|te\s+gustaria|deberia|should|would)\b|\bcomo\s+quieres\s+que\s+se\s+sienta\b|\bwhat\s+(?:mood|emotion|feeling|energy|atmosphere)\b|\bhow\s+should\s+(?:the\s+)?(?:song|track)\s+feel\b"""
            ).containsMatchIn(normalized)
        }
        if (field == "ritmo") {
            return Regex(
                """\b(?:que|cual)\s+(?:ritmo|tempo|velocidad)\b|\b(?:what|which)\s+(?:tempo|pace|speed|rhythm)\b|\b(?:la\s+imaginas|la\s+prefieres|la\s+quieres)\s*(?:lenta|rapida|slow|fast)\b|\b(?:would\s+you\s+prefer\s+(?:a\s+)?)?(?:slow\s+or\s+fast|fast\s+or\s+slow)\b|\bhow\s+fast\s+should\s+(?:the\s+)?(?:song|track)\s+be\b"""
            ).containsMatchIn(normalized)
        }
        if (field == "vozFuente") {
            return Regex(
                """\b(?:quieres|prefieres|vas\s+a|usar|grabar|importar|adjuntar|subir)\b.{0,65}\b(?:voz|grabacion|microfono)\b|\b(?:archivo|fichero)\s+de\s+voz\b|\b(?:would\s+you\s+like|will\s+you|do\s+you\s+want)\b.{0,65}\b(?:record|upload|import|attach|voice\s+file|recording)\b"""
            ).containsMatchIn(normalized)
        }
        val terms = when (field) {
            "tema" -> listOf("tema", "theme")
            "estilo" -> listOf("estilo", "style", "genre", "genero", "tipo de musica", "genero musical")
            "ambiente" -> listOf("ambiente", "mood", "emotion", "emocion", "feeling", "sentimiento", "sensacion", "tono")
            "ritmo" -> listOf("ritmo", "rhythm", "tempo", "velocidad", "pulsaciones", "bpm")
            "vozFuente" -> listOf("archivo", "file", "grabada", "grabado", "grabar", "recording", "record", "microfono", "microphone", "importar", "upload", "subir")
            else -> return false
        }
        val padded = " $normalized "
        return terms.any { term -> padded.contains(" $term ") }
    }
}
