package com.musicaia.modelo

import org.json.JSONObject
import java.util.Locale

/** Parses only model-extracted fields grounded by an exact quote from the latest user message. */
object BardoGuidedResponseParser {
    data class ParsedResponse(val updates: Map<String, String>, val reply: String)

    private data class FieldSpec(
        val jsonName: String,
        val appName: String,
        val maxLength: Int
    )

    private val specs = listOf(
        FieldSpec("theme", "tema", 500),
        FieldSpec("style", "estilo", 100),
        FieldSpec("mood", "ambiente", 100),
        FieldSpec("rhythm", "ritmo", 100),
        FieldSpec("language", "idioma", 12),
        FieldSpec("type", "tipo", 40),
        FieldSpec("voice_source", "vozFuente", 24)
    )

    fun parse(
        raw: String,
        latestUserMessage: String,
        existingFields: Map<String, String>
    ): ParsedResponse? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull() ?: return null
        val reply = root.optString("reply").trim().take(1500)
        if (reply.isBlank()) return null
        // La respuesta conversacional es independiente de la extracción de campos.
        // Un JSON válido con reply pero sin fields sigue siendo útil y no actualiza datos.
        val fields = root.optJSONObject("fields") ?: return ParsedResponse(emptyMap(), reply)
        val updates = linkedMapOf<String, String>()
        specs.forEach { spec ->
            val item = fields.optJSONObject(spec.jsonName) ?: return@forEach
            val valueRaw = item.optString("value").trim()
            val evidence = item.optString("evidence").trim()
            if (valueRaw.isBlank() || evidence.isBlank() || !containsEvidence(latestUserMessage, evidence)) {
                return@forEach
            }
            val value = normalizeValue(spec.jsonName, valueRaw) ?: return@forEach
            if (isNegatedPreference(latestUserMessage, evidence, spec.appName)) return@forEach
            val existing = existingFields[spec.appName].orEmpty().trim()
            val isChange = item.optBoolean("change", false)
            if (existing.isNotBlank() && !existing.equals(value, ignoreCase = true)) {
                if (!isChange || !containsExplicitCorrection(latestUserMessage)) return@forEach
            }
            if (value.length > spec.maxLength) return@forEach
            updates[spec.appName] = value
        }
        return ParsedResponse(updates, reply)
    }

    private fun isNegatedPreference(message: String, evidence: String, field: String): Boolean {
        // A quoted value is not an affirmative preference merely because it appears
        // verbatim in the message. Keep negative creative preferences out of state.
        if (field !in setOf("tema", "estilo", "ambiente", "ritmo", "idioma")) return false
        val normalizedMessage = java.text.Normalizer.normalize(
            message.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD
        ).replace(Regex("""\\p{Mn}+"""), "")
        val normalizedEvidence = java.text.Normalizer.normalize(
            evidence.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD
        ).replace(Regex("""\\p{Mn}+"""), "").trim()
        if (normalizedEvidence.isBlank()) return false
        val valuePattern = Regex.escape(normalizedEvidence)
        return Regex(
            """\\b(?:no\\s+(?:quiero|deseo|me\\s+gusta|prefiero)|no\\s+quiero\\s+que\\s+sea|i\\s+(?:do\\s+not|don't|dont)\\s+(?:want|like|prefer)|not\\s+looking\\s+for)\\b[^.!?]{0,100}\\b$valuePattern\\b""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(normalizedMessage)
    }

    private fun containsExplicitCorrection(message: String): Boolean {
        if (BardoIntentRouter.isNegatedCreativeChange(message)) return false
        val normalized = java.text.Normalizer.normalize(
            message.lowercase(Locale.ROOT),
            java.text.Normalizer.Form.NFD
        ).replace(Regex("""\p{Mn}+"""), "")
        return Regex(
            """\b(?:change|switch|modify|replace|instead|rather|actually|prefer|i prefer|cambiar|cambia|modificar|modifica|reemplazar|sustituir|mejor|prefiero|en vez de|en lugar de|mas bien)\b"""
        ).containsMatchIn(normalized)
    }
    /** Recupera texto libre si el modelo no respeta JSON; no expone JSON malformado. */
    fun extractFreeformReply(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank() || value.startsWith("{")) return null
        return value.take(1500)
    }

    private fun containsEvidence(message: String, evidence: String): Boolean {
        fun normalize(value: String) = Regex("\\s+")
            .replace(value.lowercase(Locale.ROOT).trim(), " ")
        val source = normalize(message)
        val quote = normalize(evidence)
        return quote.length >= 2 && source.contains(quote)
    }

    private fun normalizeValue(field: String, raw: String): String? {
        val value = raw.trim()
        val lower = value.lowercase(Locale.ROOT)
        return when (field) {
            "language" -> when {
                lower == "es" || lower.startsWith("es-") || lower in setOf("spanish", "español", "castellano") -> "Español"
                lower == "en" || lower.startsWith("en-") || lower in setOf("english", "inglés", "ingles") -> "English"
                else -> null
            }
            "type" -> when (lower) {
                "instrumental", "instrumental only", "no vocals" -> "solo instrumental"
                "voice", "vocals", "vocal", "singing", "voz", "con voz" -> "voz"
                "both", "both vocals and instrumental", "vocals and instrumental", "voice and instrumental", "singing and instrumental", "ambas", "voz e instrumental" -> "ambas (voz e instrumental)"
                else -> null
            }
            "voice_source" -> when (lower) {
                "own", "own voice", "recording", "my voice", "propia", "mi voz", "grabación" -> "grabada"
                "file", "voice file", "audio file", "archivo", "fichero" -> "archivo"
                "none", "no voice", "no vocals", "ninguna", "sin voz" -> "ninguna"
                else -> null
            }
            else -> value
        }
    }
}
