package com.musicaia.modelo

/**
 * Comprueba si la configuración conversacional puede iniciar una creación.
 * No valida la disponibilidad del motor/modelo: esa limitación se informa
 * en la etapa de ejecución para no confundirla con datos incompletos.
 */
object BardoCreationReadinessPolicy {
    private val requiredFields = listOf("tema", "estilo", "ambiente", "ritmo", "idioma", "tipo")

    fun blockReason(values: Map<String, String>, hasVoiceWav: Boolean): String? {
        val missing = requiredFields.filter { values[it].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            return "Completa estos datos antes de crear: ${missing.joinToString(", ")}."
        }
        if (!BardoIntentRouter.isSupportedSongLanguage(values["idioma"].orEmpty())) {
            return "Elige español o inglés como idioma de la canción."
        }
        if (BardoIntentRouter.requiresUserVoice(values["tipo"].orEmpty())) {
            val source = values["vozFuente"].orEmpty()
            when (source.trim().lowercase()) {
                "grabada", "archivo" -> Unit
                else -> return "Indica una fuente de voz válida (fuente válida): grabación propia o archivo de voz."
            }
            if (!hasVoiceWav) {
                return "Has elegido una canción con voz propia. Importa o graba primero un archivo WAV."
            }
        }
        return null
    }
}
