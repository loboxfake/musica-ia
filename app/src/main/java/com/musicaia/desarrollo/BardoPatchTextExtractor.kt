package com.musicaia.desarrollo

/**
 * Extrae únicamente el diff unificado de la respuesta de un modelo.
 *
 * Las respuestas de BARDO pueden añadir después del diff un bloque
 * BARDO_SOURCE_EVIDENCE_V1 para verificar extractos del snapshot. Ese bloque
 * es evidencia de procedencia, no forma parte del parche que recibe git apply.
 */
object BardoPatchTextExtractor {
    private const val SOURCE_EVIDENCE_MARKER = "BARDO_SOURCE_EVIDENCE_V1"

    fun extract(modelResponse: String): String {
        val start = modelResponse.indexOf("--- a/")
        require(start >= 0) { "La respuesta no contiene el inicio de un diff unificado." }

        val tail = modelResponse.substring(start)
        val evidenceStart = Regex("(?m)^" + SOURCE_EVIDENCE_MARKER + "\\s*$")
            .find(tail)?.range?.first
        val closingFence = Regex("(?m)^\\s*```\\s*$")
            .find(tail)?.range?.first
        val end = listOfNotNull(evidenceStart, closingFence).minOrNull() ?: tail.length
        val patch = tail.substring(0, end).trimEnd()

        require(patch.startsWith("--- a/") && Regex("(?m)^\\+\\+\\+ b/").containsMatchIn(patch)) {
            "La respuesta no contiene las cabeceras de origen y destino del diff."
        }
        require(patch.lineSequence().any { it.startsWith("@@ ") }) {
            "El diff no contiene bloques de cambios."
        }
        return patch
    }
}
