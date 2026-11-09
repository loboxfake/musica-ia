package com.musicaia.storage

import java.util.Locale

data class BardoMobileDeletionRequestArguments(
    val relativePath: String,
    val permanent: Boolean,
    val reason: String
)

/**
 * Parser estricto para propuestas de retirada creadas por BARDO.
 * La aprobación y la operación real siguen perteneciendo a la UI del usuario.
 */
object BardoMobileDeletionRequestArgumentsParser {
    fun parse(arguments: Map<String, String>): BardoMobileDeletionRequestArguments {
        val expected = setOf("path", "permanent", "reason")
        val missing = expected - arguments.keys
        val extra = arguments.keys - expected
        require(missing.isEmpty() && extra.isEmpty()) {
            "Argumentos incorrectos. Faltan=" + missing.sorted().joinToString() +
                "; adicionales=" + extra.sorted().joinToString()
        }

        val path = arguments.getValue("path").trim()
        require(path.isNotBlank() && path.length <= 512) {
            "La ruta está vacía o supera 512 caracteres."
        }

        val permanentText = arguments.getValue("permanent").trim().lowercase(Locale.ROOT)
        require(permanentText == "true" || permanentText == "false") {
            "permanent debe ser exactamente true o false."
        }

        val reason = arguments.getValue("reason").trim()
        require(reason.isNotBlank() && reason.length <= 500) {
            "El motivo es obligatorio y no puede superar 500 caracteres."
        }

        return BardoMobileDeletionRequestArguments(
            relativePath = path,
            permanent = permanentText == "true",
            reason = reason
        )
    }
}
