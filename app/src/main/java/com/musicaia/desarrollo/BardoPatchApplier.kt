package com.musicaia.desarrollo

import java.io.File

/** Aplica diffs unificados a una copia candidata; nunca toca el snapshot original. */
object BardoPatchApplier {
    data class Result(val directory: File, val changedFiles: List<String>)

    fun aplicar(root: File, patchText: String, workspace: File): Result {
        require(root.isDirectory) { "No existe el snapshot de origen." }
        val lines = patchText.lines()
        val first = lines.indexOfFirst { it.startsWith("--- a/") }
        require(first >= 0) { "No se encontró un diff unificado." }
        val workspaceManager = BardoWorkspaceManager(workspace)
        val candidate = workspaceManager.createCandidateCopy(root)
        val changed = mutableListOf<String>()
        try {
            var i = first
            while (i < lines.size) {
                if (!lines[i].startsWith("--- a/")) {
                    i++
                    continue
                }
                val oldPath = lines[i].removePrefix("--- a/").substringBefore("\t").trim()
                i++
                require(i < lines.size && lines[i].startsWith("+++ b/")) { "Falta la cabecera de destino del diff." }
                val newPath = lines[i].removePrefix("+++ b/").substringBefore("\t").trim()
                i++
                require(oldPath == newPath && oldPath.isNotBlank() && !oldPath.startsWith("/") &&
                    !oldPath.contains(':') && oldPath.split('/').none { it.isBlank() || it == "." || it == ".." }) {
                    "Solo se permiten cambios en archivos existentes con rutas seguras."
                }
                require(BardoProtectedPathPolicy.canModifyExisting(oldPath)) {
                    "Protección activa: la política central bloquea la modificación automática de " +
                        oldPath + " (" + BardoProtectedPathPolicy.classify(oldPath) + ")."
                }
                require(oldPath !in changed) { "El diff repite una ruta de archivo: " + oldPath }
                val sourceFile = workspaceManager.resolveInside(root, oldPath)
                require(sourceFile.isFile) {
                    "El archivo no existe en el snapshot: " + oldPath
                }
                val source = sourceFile.readLines(Charsets.UTF_8)
                val output = mutableListOf<String>()
                var cursor = 0
                var hunks = 0
                while (i < lines.size && !lines[i].startsWith("--- a/")) {
                    val header = lines[i]
                    if (!header.startsWith("@@ ")) {
                        i++
                        continue
                    }
                    val match = Regex("""@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*""").matchEntire(header)
                        ?: throw IllegalArgumentException("Cabecera de bloque inválida: " + header)
                    val oldStart = match.groupValues[1].toInt()
                    val expectedOld = match.groupValues[2].ifBlank { "1" }.toInt()
                    val expectedNew = match.groupValues[4].ifBlank { "1" }.toInt()
                    val target = if (oldStart == 0) 0 else oldStart - 1
                    require(target >= cursor && target <= source.size) { "El diff no coincide con el archivo " + oldPath }
                    while (cursor < target) output += source[cursor++]
                    i++
                    var usedOld = 0
                    var usedNew = 0
                    while (i < lines.size && !lines[i].startsWith("@@ ") && !lines[i].startsWith("--- a/")) {
                        val line = lines[i]
                        when {
                            line.startsWith(" ") -> {
                                require(cursor < source.size && source[cursor] == line.substring(1)) { "El contexto no coincide en " + oldPath }
                                output += source[cursor++]
                                usedOld++
                                usedNew++
                            }
                            line.startsWith("-") -> {
                                require(cursor < source.size && source[cursor] == line.substring(1)) { "La línea eliminada no coincide en " + oldPath }
                                cursor++
                                usedOld++
                            }
                            line.startsWith("+") -> {
                                output += line.substring(1)
                                usedNew++
                            }
                            line.startsWith("\\") -> Unit
                            else -> break
                        }
                        i++
                    }
                    require(usedOld == expectedOld && usedNew == expectedNew) {
                        "El recuento de líneas del bloque no coincide en " + oldPath
                    }
                    hunks++
                }
                require(hunks > 0) { "El archivo no contiene bloques de cambios: " + oldPath }
                while (cursor < source.size) output += source[cursor++]
                val destination = workspaceManager.resolveInside(candidate, oldPath)
                destination.parentFile?.mkdirs()
                destination.writeText(output.joinToString("\n", postfix = if (output.isEmpty()) "" else "\n"), Charsets.UTF_8)
                changed += oldPath
            }
            require(changed.isNotEmpty()) { "No se aplicó ningún cambio." }
            return Result(candidate, changed)
        } catch (error: Throwable) {
            workspaceManager.deleteCandidate(candidate)
            throw error
        }
    }
}
