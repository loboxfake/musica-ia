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
        val fields = root.optJSONObject("fields") ?: return null
        val updates = linkedMapOf<String, String>()
        specs.forEach { spec ->
            val item = fields.optJSONObject(spec.jsonName) ?: return@forEach
            val valueRaw = item.optString("value").trim()
            val evidence = item.optString("evidence").trim()
            if (valueRaw.isBlank() || evidence.isBlank() || !containsEvidence(latestUserMessage, evidence)) {
                return@forEach
            }
            val value = normalizeValue(spec.jsonName, valueRaw) ?: return@forEach
            val existing = existingFields[spec.appName].orEmpty().trim()
            val isChange = item.optBoolean("change", false)
            if (existing.isNotBlank() && !existing.equals(value, ignoreCase = true) && !isChange) {
                return@forEach
            }
            if (value.length > spec.maxLength) return@forEach
            updates[spec.appName] = value
        }
        return ParsedResponse(updates, reply)
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
