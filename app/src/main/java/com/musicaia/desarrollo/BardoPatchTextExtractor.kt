package com.musicaia.desarrollo

/**
 * Extrae únicamente el diff unificado de la respuesta de un modelo.
 *
 * Los marcadores de evidencia y las explicaciones posteriores al diff no se
 * envían a git apply. Los límites se calculan a partir de los recuentos reales
 * de cada hunk para no depender del formato opcional usado por el modelo.
 */
object BardoPatchTextExtractor {
    private val hunkHeader = Regex("""^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$""")

    fun extract(modelResponse: String): String {
        val start = modelResponse.indexOf("--- a/")
        require(start >= 0) { "La respuesta no contiene el inicio de un diff unificado." }

        val lines = modelResponse.substring(start).lines()
        val patchLines = mutableListOf<String>()
        var index = 0
        var totalHunks = 0

        while (index < lines.size && lines[index].startsWith("--- a/")) {
            patchLines += lines[index++]
            require(index < lines.size && lines[index].startsWith("+++ b/")) {
                "Falta la cabecera de destino del diff."
            }
            patchLines += lines[index++]

            var fileHunks = 0
            while (index < lines.size && lines[index].startsWith("@@ ")) {
                val match = hunkHeader.matchEntire(lines[index])
                    ?: throw IllegalArgumentException("Cabecera de bloque inválida: " + lines[index])
                patchLines += lines[index++]

                val expectedOld = match.groupValues[2].ifBlank { "1" }.toInt()
                val expectedNew = match.groupValues[4].ifBlank { "1" }.toInt()
                var usedOld = 0
                var usedNew = 0

                while (usedOld < expectedOld || usedNew < expectedNew) {
                    require(index < lines.size) { "El diff termina antes de completar un bloque." }
                    val line = lines[index]
                    when {
                        line.startsWith(" ") -> {
                            usedOld++
                            usedNew++
                        }
                        line.startsWith("-") -> usedOld++
                        line.startsWith("+") -> usedNew++
                        else -> throw IllegalArgumentException(
                            "Línea de diff inválida o incompleta dentro de un bloque."
                        )
                    }
                    require(usedOld <= expectedOld && usedNew <= expectedNew) {
                        "El bloque contiene más líneas de las declaradas en su cabecera."
                    }
                    patchLines += line
                    index++
                }

                while (index < lines.size && lines[index].startsWith("\\\\")) {
                    patchLines += lines[index++]
                }
                fileHunks++
                totalHunks++
            }

            require(fileHunks > 0) { "El archivo del diff no contiene bloques de cambios." }
            // A non-header line after a complete hunk is explanation/evidence, not patch data.
            if (index >= lines.size || !lines[index].startsWith("--- a/")) break
        }

        require(totalHunks > 0) { "El diff no contiene bloques de cambios." }
        return patchLines.joinToString("\\n").trimEnd()
    }
}
