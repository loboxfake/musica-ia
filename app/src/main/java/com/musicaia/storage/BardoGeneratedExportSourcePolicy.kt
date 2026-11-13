package com.musicaia.storage

import java.io.File
import java.nio.file.Files

/**
 * Permite exportar solo archivos regulares situados bajo directorios privados
 * de la propia aplicación. Canonicalización y rechazo de symlinks evitan escapes.
 */
object BardoGeneratedExportSourcePolicy {
    fun allows(allowedRoots: Collection<File>, source: File): Boolean = runCatching {
        if (!source.isFile || Files.isSymbolicLink(source.toPath())) return@runCatching false
        val candidate = source.canonicalFile
        allowedRoots.any { rawRoot ->
            if (!rawRoot.isDirectory || Files.isSymbolicLink(rawRoot.toPath())) {
                false
            } else {
                val root = rawRoot.canonicalFile
                candidate.path.startsWith(root.path + File.separator)
            }
        }
    }.getOrDefault(false)
}

data class BardoExportReceipt(
    val relativePath: String,
    val bytes: Long,
    val sha256: String
)
