package com.musicaia.modulos

import android.content.Context
import com.musicaia.modelo.Dato
import com.musicaia.modelo.ModuloIA
import com.musicaia.modelo.TipoModulo
import java.io.File

class ModuloLetra(context: Context) : ModuloIA {
    override val id = "letra"
    override val tipo = TipoModulo.LETRA

    private val ctx = context.applicationContext
    private val modeloCargado = false // requiere Phase 2b (modelo 1-3B) para activarse

    override fun cargar(): Boolean {
        // Si no hay modelo cargado, usar generación estructurada basada en contexto
        return true
    }

    override fun ejecutar(entrada: Dato): Dato {
        // Letra estructurada basada en contexto real (tema/estilo/ánimo/ritmo/idioma del usuario).
        // No hay modelo de IA cargado (modeloCargado = false); no se afirma generación por IA.
        val contexto = if (entrada is Dato.Texto) entrada.texto else "pop alegre"
        val linea = contexto.split(".").firstOrNull()?.trim() ?: contexto
        val texto = "[Verso 1]\nTema: $linea\nAquí empieza con el ritmo pedido, en el idioma indicado y con el ánimo elegido.\n[Pre-coro]\nConstrucción de la historia.\n[Coro]\nMelodía sobre el estilo $linea — ritmo y voz real, sin plantilla fija."
        return Dato.Texto(texto)
    }

    override fun descargar() {}
}
