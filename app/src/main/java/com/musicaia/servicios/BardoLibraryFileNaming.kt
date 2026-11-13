package com.musicaia.servicios

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Names generated library files without overwriting earlier creations from the same minute. */
internal object BardoLibraryFileNaming {
    private val timestampFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd_HHmmss_SSS")
        .withZone(ZoneId.systemDefault())

    fun createBaseName(
        timestampMillis: Long,
        style: String,
        uniqueToken: String = UUID.randomUUID().toString()
    ): String {
        require(timestampMillis >= 0L) { "La fecha de creación no puede ser negativa." }
        require(uniqueToken.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"))) {
            "El identificador único de archivo no es válido."
        }
        val normalized = Normalizer.normalize(style, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        val safeStyle = Regex("[^A-Za-z0-9]+")
            .replace(normalized, "_")
            .trim('_')
            .take(28)
            .ifBlank { "instrumental" }
        val timestamp = timestampFormatter.format(Instant.ofEpochMilli(timestampMillis))
        return "BARDO_${timestamp}_${safeStyle}_${uniqueToken.lowercase()}"
    }
}
