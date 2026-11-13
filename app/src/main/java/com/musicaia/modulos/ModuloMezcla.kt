package com.musicaia.modulos

import android.content.Context
import com.musicaia.modelo.Dato
import com.musicaia.modelo.ModuloIA
import com.musicaia.modelo.TipoModulo

/**
 * Mezcla/masterización profesional todavía no integrada.
 * Nunca fabricar un WAV de prueba y presentarlo como una mezcla musical.
 */
class ModuloMezcla(context: Context) : ModuloIA {
    override val id = "mezcla"
    override val tipo = TipoModulo.MEZCLA

    @Suppress("unused")
    private val appContext = context.applicationContext

    override fun cargar(): Boolean = false

    override fun ejecutar(entrada: Dato): Dato {
        throw UnsupportedOperationException(
            "La mezcla/masterización profesional todavía no está implementada. " +
                "No se ha creado ningún audio."
        )
    }

    override fun descargar() = Unit
}
