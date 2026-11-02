package com.musicaia.modulos

import android.content.Context
import com.musicaia.modelo.Dato
import com.musicaia.modelo.ModuloIA
import com.musicaia.modelo.TipoModulo
<<<<<<< HEAD
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
=======

class ModuloLetra(context: Context) : ModuloIA {
 override val id = "letra"
 override val tipo = TipoModulo.LETRA

 override fun cargar(): Boolean = true

 override fun ejecutar(entrada: Dato): Dato {
 val bruto = if (entrada is Dato.Texto) entrada.texto else ""
 val tema = Regex("Tema:\\s*(.*?)\\.\\s*Estilo:", RegexOption.IGNORE_CASE)
 .find(bruto)?.groupValues?.get(1)?.trim().orEmpty().ifEmpty { "pop alegre" }
 val estilo = Regex("Estilo:\\s*(.*)", RegexOption.IGNORE_CASE)
 .find(bruto)?.groupValues?.get(1)?.trim().orEmpty().ifEmpty { "verano" }
 val semilla = (tema + estilo).hashCode() and 0x7fffffff
 val versos = listOf(
 "Camino despacio bajo un cielo nuevo",
 "Se enciende la tarde y todo se mueve",
 "Llevo en el bolsillo un monton de ilusiones",
 "Las calles me cantan sin saber mi nombre",
 "Hoy el mundo suena distinto a ayer",
 "Un paso adelante, un paso sin miedo",
 "Creo en lo que siento aunque nadie lo crea"
 )
 val letra = versos.joinToString("\\n\\n")
 return Dato.Texto(letra)
 }

 override fun descargar() {}
>>>>>>> base-verde
}
