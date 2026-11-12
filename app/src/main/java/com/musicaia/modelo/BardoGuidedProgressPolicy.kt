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
            ).containsMatchIn(normalized)
        }
        val terms = when (field) {
            "tema" -> listOf("tema", "theme")
            "estilo" -> listOf("estilo", "style", "genre", "genero")
            "ambiente" -> listOf("ambiente", "mood", "emotion", "emocion", "feeling")
            "ritmo" -> listOf("ritmo", "rhythm", "tempo")
            "idioma" -> listOf("idioma", "language", "espanol", "spanish", "english")
            "tipo" -> listOf("tipo", "type", "voz", "voice", "vocal", "vocals", "instrumental")
            "vozFuente" -> listOf("archivo", "file", "grabada", "grabado", "grabar", "recording", "record", "microfono", "microphone", "importar", "upload", "subir")
            else -> return false
        }
        val padded = " $normalized "
        return terms.any { term -> padded.contains(" $term ") }
    }
}
