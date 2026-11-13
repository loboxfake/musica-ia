package com.musicaia.importacion

import java.util.Locale

/**
 * Minimum guaranteed OCR language coverage for the bundled Latin recognizer.
 * Spanish and English share the Latin script; OCR does not infer which language
 * a recognized sentence belongs to.
 */
object BardoOcrLanguageSupport {
    val minimumSupportedLanguages: Set<String> = setOf("es", "en")

    fun supportsMinimumLanguage(languageTag: String): Boolean {
        val normalized = languageTag.trim().lowercase(Locale.ROOT)
            .substringBefore('-').substringBefore('_')
        return normalized in minimumSupportedLanguages
    }
}
