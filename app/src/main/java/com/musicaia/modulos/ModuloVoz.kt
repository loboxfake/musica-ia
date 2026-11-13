package com.musicaia.modulos

import android.content.Context
import com.musicaia.modelo.Dato
import com.musicaia.modelo.ModuloIA
import com.musicaia.modelo.TipoModulo

/**
 * Voz cantada todavía no implementada.
 *
 * Este módulo falla de forma explícita para impedir que un tono de prueba,
 * un archivo inexistente o una ruta vacía se presenten como voz generada.
 * La capacidad permanece NOT_IMPLEMENTED hasta integrar y validar un motor
 * de síntesis vocal real con consentimiento y WAV reproducible.
 */
class ModuloVoz(context: Context) : ModuloIA {
    override val id = "voz"
    override val tipo = TipoModulo.VOZ

    @Suppress("unused")
    private val appContext = context.applicationContext

    override fun cargar(): Boolean = false

    override fun ejecutar(entrada: Dato): Dato {
        throw UnsupportedOperationException(
            "La generación de voz cantada todavía no está implementada. " +
                "No se ha generado ningún audio."
        )
    }

    override fun descargar() = Unit
}
