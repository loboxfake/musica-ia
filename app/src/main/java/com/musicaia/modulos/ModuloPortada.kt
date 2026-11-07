package com.musicaia.modulos

import android.content.Context
import com.musicaia.modelo.Dato
import com.musicaia.modelo.ModuloIA
import com.musicaia.modelo.TipoModulo

/**
 * Generación de portadas mediante IA todavía no integrada.
 * No presentar una tarjeta tipográfica fija como portada generada por IA.
 */
class ModuloPortada(context: Context) : ModuloIA {
    override val id = "portada"
    override val tipo = TipoModulo.PORTADA

    @Suppress("unused")
    private val appContext = context.applicationContext

    override fun cargar(): Boolean = false

    override fun ejecutar(entrada: Dato): Dato {
        throw UnsupportedOperationException(
            "La generación de portadas con IA todavía no está implementada. " +
                "No se ha generado ninguna portada."
        )
    }

    override fun descargar() = Unit
}
