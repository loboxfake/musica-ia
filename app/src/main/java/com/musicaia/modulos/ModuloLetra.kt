package com.musicaia.modulos

import android.content.Context
import com.musicaia.modelo.Dato
import com.musicaia.modelo.ModuloIA
import com.musicaia.modelo.TipoModulo

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
}
