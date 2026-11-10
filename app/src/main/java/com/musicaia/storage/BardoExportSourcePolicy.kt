package com.musicaia.storage

import java.io.File
import java.nio.file.Files

/**
 * Limita la exportación compartida a artefactos creados dentro del workspace
 * privado de BARDO. Una ruta recibida desde otro subsistema no puede usarse
 * para copiar archivos privados arbitrarios a Descargas/BARDO.
 */
object BardoExportSourcePolicy {
    fun allows(workspaceRoot: File, source: File): Boolean = runCatching {
        if (!workspaceRoot.isDirectory || Files.isSymbolicLink(workspaceRoot.toPath())) {
            return@runCatching false
        }
        if (!source.isFile || Files.isSymbolicLink(source.toPath())) {
            return@runCatching false
        }
        val root = workspaceRoot.canonicalFile
        val candidate = source.canonicalFile
        candidate.path.startsWith(root.path + File.separator)
    }.getOrDefault(false)
}
