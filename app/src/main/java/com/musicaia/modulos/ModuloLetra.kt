package com.musicaia.modulos

import android.content.Context
import com.musicaia.modelo.Dato
import com.musicaia.modelo.ModuloIA
import com.musicaia.modelo.TipoModulo
import com.musicaia.modelos.BardoGemmaLiteRtProvider
import com.musicaia.modelos.BardoModelRegistry
import com.musicaia.modelos.BardoModelRequest
import com.musicaia.modelos.BardoModelState
import com.musicaia.modelos.BardoQwen2LiteRtProvider

/**
 * Generación de letra con modelos locales exclusivamente.
 *
 * Gemma es el proveedor preferente y Qwen2 es la alternativa cuando Gemma no
 * está instalado, no supera el presupuesto real de recursos o falla al cargar.
 * El modo manual conserva la letra del usuario sin solicitar inferencia.
 */
class ModuloLetra(context: Context, private val modoManual: Boolean = false) : ModuloIA {
    override val id = "letra"
    override val tipo = TipoModulo.LETRA
    private val ctx = context.applicationContext

    private val registry = BardoModelRegistry(
        listOf(BardoGemmaLiteRtProvider(), BardoQwen2LiteRtProvider())
    )

    override fun cargar(): Boolean {
        if (modoManual) return true
        return registry.orderedCandidates("lyrics", allowRemoteModels = false).any { provider ->
            val state = runCatching { provider.inspect(ctx).state }.getOrNull()
            state == BardoModelState.READY || state == BardoModelState.PRESENT_UNVERIFIED
        }
    }

    override fun ejecutar(entrada: Dato): Dato {
        val solicitud = (entrada as? Dato.Texto)?.texto
            ?: throw IllegalArgumentException("La generación de letra necesita datos de texto.")

        if (modoManual && solicitud.startsWith("[LETRA_USUARIO]")) {
            return Dato.Texto(extractManualLyrics(solicitud))
        }

        check(!modoManual) {
            "El modo «Mi letra» necesita el marcador de letra manual; no se sustituirá por texto generado."
        }

        val result = registry.generate(
            context = ctx,
            capability = "lyrics",
            request = BardoModelRequest(solicitud, resetConversation = true),
            allowRemoteModels = false
        )
        val letra = result.text.substringAfter("</think>").trim()
        check(letra.isNotBlank()) {
            "El proveedor local " + result.providerId + " devolvió una letra vacía."
        }
        return Dato.Texto(letra)
    }

    override fun descargar() {
        if (!modoManual) registry.unloadAll()
    }

    companion object {
        /**
         * El marcador contextual separa los versos del briefing en cualquier idioma.
         * Se conservan los delimitadores antiguos como compatibilidad con entradas guardadas.
         */
        internal fun extractManualLyrics(input: String): String {
            require(input.startsWith("[LETRA_USUARIO]")) {
                "Falta el marcador de letra manual."
            }
            val payload = input.substringAfter("[LETRA_USUARIO]")
            val delimiters = listOf(
                "\n\n[CONTEXTO_CREACION]",
                "\n\nTema:",
                "\n\nTheme:"
            )
            val cut = delimiters.map { payload.indexOf(it) }
                .filter { it >= 0 }
                .minOrNull() ?: payload.length
            return payload.substring(0, cut).trim()
        }
    }
}
