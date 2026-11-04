package com.musicaia.modulos.litert

/**
 * Conversación contextual con LiteRT-LM.
 * Usa EngineLiteRT (mismo executor); interpreta respuestas con datosConver
 * para evitar confundir "ambas" / "ninguna" con el tema de la canción.
 * Generación de texto (letras), NO música/instrumentales/vocals (Hito 1 límite).
 */
object ConversacionLiteRT {
    fun interpretarContextual(respuestaModelo: String, datosConver: String): String {
        // Integración futura con datosConver; por ahora scaffold para evitar
        // confusión entre pronombres/referrentes y tema de canción.
        return respuestaModelo.replace(Regex("(?i)\\b(ambas|ninguna)\\b")) {
            // Lógica contextual pendiente tras verificación de modelo cargado
            it.groupValues[1]
        }
    }

    fun solicitarLetra(solicitud: String): String {
        // Solo texto; no generación de audio instrumental ni canto.
        if (!LiteRTLMModule.canLoad()) return "[MODELO NO CARGADO: verificación SHA/conversación pendiente]"
        return "[GENERACIÓN DE LETRA — HITO 1 — texto solo] Solicitud: $solicitud"
    }
}
