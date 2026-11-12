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
            // Mentioning English/Spanish is not enough: the question must ask
            // which language the user's own song should use.
            val asksWhichLanguage = Regex(
                """\b(?:que|cual)\s+idioma\b|\b(?:which|what)\s+language\b|\b(?:idioma|language)\b"""
            ).containsMatchIn(normalized)
            val specifiesSongLanguage = Regex(
                """\b(?:en|in)\s+(?:espanol|spanish|english|ingles)\b|\b(?:espanol|spanish|english|ingles)\s+(?:o|or)\s+(?:espanol|spanish|english|ingles)\b"""
            ).containsMatchIn(normalized)
            return asksWhichLanguage || specifiesSongLanguage
        }
        val terms = when (field) {
            "tema" -> listOf("tema", "theme")
            "estilo" -> listOf("estilo", "style", "genre", "genero")
            "ambiente" -> listOf("ambiente", "mood", "emotion", "emocion", "feeling")
            "ritmo" -> listOf("ritmo", "rhythm", "tempo")
            "vozFuente" -> listOf("archivo", "file", "grabada", "grabado", "grabar", "recording", "record", "microfono", "microphone", "importar", "upload", "subir")
            else -> return false
        }
        val padded = " $normalized "
        return terms.any { term -> padded.contains(" $term ") }
    }
}
