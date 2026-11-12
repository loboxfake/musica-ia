package com.musicaia.servicios

/**
 * Evita confundir un booleano de disponibilidad con una grabación real.
 * La cabecera PCM se valida después en el worker, antes de mezclar.
 */
object BardoUserVoiceMixPolicy {
    fun blockReason(
        instrumentalOnly: Boolean,
        advertisedAvailable: Boolean,
        voicePath: String?,
        fileExists: Boolean,
        fileLength: Long,
        maxFileBytes: Long = 120L * 1024L * 1024L
    ): String? {
        if (instrumentalOnly) return null
        if (advertisedAvailable && voicePath.isNullOrBlank()) {
            return "Se indicó que había una voz propia, pero no se recibió la ruta del WAV. No se iniciará una canción vocal sin el archivo real."
        }
        if (!voicePath.isNullOrBlank()) {
            if (!fileExists) return "El archivo WAV de voz propia ya no existe o no está disponible."
            if (fileLength < 44L) return "El archivo de voz es demasiado pequeño para ser un WAV válido."
            if (fileLength > maxFileBytes) return "El WAV de voz supera el límite de seguridad de 120 MiB."
        }
        return null
    }

    fun shouldMix(instrumentalOnly: Boolean, voicePath: String?): Boolean =
        !instrumentalOnly && !voicePath.isNullOrBlank()
}
