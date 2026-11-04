package com.musicaia.modulos.litert

/** Generación de letras con el modelo local LiteRT-LM; no genera audio. */
object ConversacionLiteRT {
    fun solicitarLetra(solicitud: String): String {
        val prompt = """
            Actúa como compositor profesional de canciones originales en español o en el idioma solicitado.
            Escribe una letra completa, coherente, natural y cantable basada en todos los datos del usuario.
            No copies canciones existentes ni imites a artistas concretos.
            Devuelve solo la letra, sin explicar tu proceso ni repetir la solicitud.
            Usa esta estructura:
            [Intro]
            [Verso 1]
            [Pre-coro]
            [Coro]
            [Verso 2]
            [Puente]
            [Coro final]
            Cada sección debe tener líneas originales. El coro debe ser memorable y fácil de cantar.
            Evita frases de relleno, marcadores de posición y repetir literalmente los campos del formulario.
            Datos de la canción:
            $solicitud
        """.trimIndent()
        val respuesta = EngineLiteRT.generar(prompt).trim()
        val limpia = respuesta.substringAfter("</think>", respuesta).trim()
        if (limpia.isBlank()) throw IllegalStateException("El modelo local devolvió una respuesta vacía.")
        return limpia
    }
}
