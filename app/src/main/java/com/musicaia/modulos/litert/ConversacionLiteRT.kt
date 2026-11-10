package com.musicaia.modulos.litert

/** Generación de letras con el modelo local LiteRT-LM; no genera audio. */
object ConversacionLiteRT {
    fun solicitarLetra(solicitud: String, modelPath: String, cacheDir: String): String {
        val prompt = """
            Actúa como compositor profesional. El campo Language/Idioma especifica el idioma obligatorio: español o inglés.
            Escribe toda la letra y los encabezados de sección en el idioma elegido; no mezcles español e inglés salvo que el usuario lo solicite explícitamente.
            Escribe una letra completa, coherente, natural y cantable basada en todos los datos del usuario.
            No copies canciones existentes ni imites a artistas concretos.
            Devuelve solo la letra, sin explicar tu proceso ni repetir la solicitud.
            Si el idioma es English, utiliza encabezados como [Intro], [Verse 1], [Pre-Chorus], [Chorus], [Verse 2], [Bridge] y [Final Chorus].
            Si el idioma es Español, utiliza [Intro], [Verso 1], [Pre-coro], [Coro], [Verso 2], [Puente] y [Coro final].
            Cada sección debe tener líneas originales. El coro debe ser memorable y fácil de cantar.
            Evita frases de relleno, marcadores de posición y repetir literalmente los campos del formulario.
            Datos de la canción:
            $solicitud
        """.trimIndent()
        val respuesta = EngineLiteRT.withSession {
            if (!estaCargado(modelPath)) {
                check(inicializar(modelPath, cacheDir)) {
                    "LiteRT-LM no pudo cargar el modelo para generar la letra."
                }
            }
            generar(prompt, reiniciarConversacion = true)
        }.trim()
        val limpia = respuesta.substringAfter("</think>", respuesta).trim()
        if (limpia.isBlank()) throw IllegalStateException("El modelo local devolvió una respuesta vacía.")
        return limpia
    }
}
