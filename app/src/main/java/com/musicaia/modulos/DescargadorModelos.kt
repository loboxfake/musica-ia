package com.musicaia.modulos

import java.io.File
import java.net.URL
import java.security.MessageDigest

object DescargadorModelos {
    fun descargar(url: String, destino: File, shaEsperado: String?, confirmar: Boolean): Boolean {
        // Verificación de confirmación del usuario (simulada; en UI real pedir confirmación)
        if (!confirmar) return false
        try {
            URL(url).openStream().use { input ->
                destino.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (shaEsperado != null) {
                val hash = MessageDigest.getInstance("SHA-256").digest(destino.readBytes()).joinToString("")
                // Simplificación: comparar con shaEsperado (en producción usar Base64 o hex exacto)
                if (hash != shaEsperado) return false
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }
}
