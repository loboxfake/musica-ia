package com.musicaia.modulos

import android.content.Context
import com.musicaia.modelo.Dato
import com.musicaia.modelo.ModuloIA
import com.musicaia.modelo.TipoModulo
import com.musicaia.modulos.litert.ConversacionLiteRT
import com.musicaia.modulos.litert.EngineLiteRT
import com.musicaia.modulos.litert.LiteRTLMModule
import java.io.File

class ModuloLetra(context: Context) : ModuloIA {
    override val id = "letra"
    override val tipo = TipoModulo.LETRA
    private val ctx = context.applicationContext

    override fun cargar(): Boolean {
        val modelo = LiteRTLMModule.modelFile(ctx)
        if (!LiteRTLMModule.verificarModelo(modelo)) return false
        return EngineLiteRT.inicializar(
            modelo.absolutePath,
            File(ctx.cacheDir, "litertlm-cache").absolutePath
        )
    }

    override fun ejecutar(entrada: Dato): Dato {
        val solicitud = (entrada as? Dato.Texto)?.texto
            ?: throw IllegalArgumentException("La generación de letra necesita datos de texto.")
        return Dato.Texto(ConversacionLiteRT.solicitarLetra(solicitud))
    }

    override fun descargar() {}
}
