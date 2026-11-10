package com.musicaia.modulos

import android.content.Context
import com.musicaia.modelo.Dato
import com.musicaia.modelo.ModuloIA
import com.musicaia.modelo.TipoModulo
import com.musicaia.modulos.litert.ConversacionLiteRT
import com.musicaia.modulos.litert.EngineLiteRT
import com.musicaia.modulos.litert.LiteRTLMModule
import java.io.File

class ModuloLetra(context: Context, private val modoManual: Boolean = false) : ModuloIA {
    override val id = "letra"
    override val tipo = TipoModulo.LETRA
    private val ctx = context.applicationContext

    override fun cargar(): Boolean {
        if (modoManual) return true
        val modelo = LiteRTLMModule.modelFile(ctx)
        if (!LiteRTLMModule.verificarModelo(modelo)) return false
        return EngineLiteRT.withSession {
            if (estaCargado(modelo.absolutePath)) true
            else inicializar(
                modelo.absolutePath,
                File(ctx.cacheDir, "litertlm-cache").absolutePath
            )
        }
    }

    override fun ejecutar(entrada: Dato): Dato {
        val solicitud = (entrada as? Dato.Texto)?.texto
            ?: throw IllegalArgumentException("La generación de letra necesita datos de texto.")
        if (modoManual && solicitud.startsWith("[LETRA_USUARIO]")) {
            return Dato.Texto(solicitud.substringAfter("[LETRA_USUARIO]").substringBefore("\n\nTema:").trim())
        }
        val modelo = LiteRTLMModule.modelFile(ctx)
        require(LiteRTLMModule.verificarModelo(modelo)) {
            "El SHA-256 de Gemma no coincide; se conserva el archivo original."
        }
        return Dato.Texto(
            ConversacionLiteRT.solicitarLetra(
                solicitud,
                modelo.absolutePath,
                File(ctx.cacheDir, "litertlm-cache").absolutePath
            )
        )
    }

    override fun descargar() {
        EngineLiteRT.cerrar()
    }
}
