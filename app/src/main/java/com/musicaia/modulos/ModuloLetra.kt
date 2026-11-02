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
        // Generación basada en tema/estilo del contexto; requiere modelo real para calidad completa
        val tema = if (entrada is Dato.Texto) entrada.texto else "pop alegre"
        val texto = "[Canta] $tema\n[Verso] Aquí empieza la historia...\n[Coro] Ritmo y luz, con voz y pasión."
        return Dato.Texto(texto)
    }

    override fun descargar() {}
}
