package com.musicaia.desarrollo

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID

/**
 * Crea y controla copias candidatas privadas sin ejecutar su contenido.
 * Rechaza enlaces simbólicos, traversal y archivos fuera de límites.
 */
class BardoWorkspaceManager(workspaceDirectory: File) {
    private val lock = Any()
    private val workspaceRoot: File

    init {
        if (!workspaceDirectory.exists()) {
            check(workspaceDirectory.mkdirs()) { "No se pudo crear el workspace privado." }
        }
        require(workspaceDirectory.isDirectory &&
            !Files.isSymbolicLink(workspaceDirectory.toPath())) {
            "El workspace debe ser una carpeta real, no un enlace simbólico."
        }
        workspaceRoot = workspaceDirectory.canonicalFile
    }

    fun createCandidateCopy(sourceDirectory: File): File = synchronized(lock) {
        require(sourceDirectory.isDirectory &&
            !Files.isSymbolicLink(sourceDirectory.toPath())) {
            "El snapshot de origen no es una carpeta normal."
        }
        val sourceRoot = sourceDirectory.canonicalFile
        require(sourceRoot != workspaceRoot && !isSameOrChild(sourceRoot, workspaceRoot)) {
            "El origen no puede contener la carpeta raíz del workspace."
        }

        val candidate = File(
            workspaceRoot,
            "candidate-" + UUID.randomUUID().toString()
        )
        check(candidate.mkdir()) { "No se pudo crear la copia candidata aislada." }

        var fileCount = 0
        var totalBytes = 0L
        try {
            fun copyDirectory(source: File, relativeDirectory: String) {
                require(!Files.isSymbolicLink(source.toPath())) {
                    "El snapshot contiene un enlace simbólico: $relativeDirectory"
                }
                val canonicalSource = source.canonicalFile
                require(canonicalSource == sourceRoot ||
                    isSameOrChild(sourceRoot, canonicalSource)) {
                    "El snapshot intenta salir de su carpeta de origen."
                }

                val children = source.listFiles()
                    ?: throw IllegalStateException(
                        "No se pudo enumerar el snapshot: $relativeDirectory"
                    )
                children.sortedBy { it.name }.forEach { child ->
                    require(child.name.isNotBlank() &&
                        child.name != "." && child.name != "..") {
                        "Nombre de archivo no válido en el snapshot."
                    }
                    require(!Files.isSymbolicLink(child.toPath())) {
                        "No se permiten enlaces simbólicos: ${child.name}"
                    }

                    val relative = if (relativeDirectory.isBlank()) child.name
                    else "$relativeDirectory/${child.name}"
                    val destination = resolveInside(candidate, relative)

                    when {
                        child.isDirectory -> {
                            check(destination.mkdir()) {
                                "No se pudo crear una carpeta candidata."
                            }
                            copyDirectory(child, relative)
                        }
                        child.isFile -> {
                            fileCount++
                            require(fileCount <= MAX_FILES) {
                                "El snapshot supera el límite de $MAX_FILES archivos."
                            }
                            require(child.length() <= MAX_FILE_BYTES) {
                                "Archivo superior a 2 MiB: $relative"
                            }
                            destination.parentFile?.let {
                                if (!it.exists()) check(it.mkdirs()) {
                                    "No se pudo crear la carpeta destino."
                                }
                            }
                            FileInputStream(child).use { input ->
                                FileOutputStream(destination, false).use { output ->
                                    val buffer = ByteArray(8192)
                                    var copiedForFile = 0L
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        copiedForFile += read
                                        totalBytes += read
                                        require(
                                            copiedForFile <= MAX_FILE_BYTES &&
                                                totalBytes <= MAX_TOTAL_BYTES
                                        ) {
                                            "El snapshot supera los límites de tamaño."
                                        }
                                        output.write(buffer, 0, read)
                                    }
                                    output.fd.sync()
                                }
                            }
                        }
                        else -> throw IllegalArgumentException(
                            "Entrada no regular en el snapshot: $relative"
                        )
                    }
                }
            }

            copyDirectory(sourceRoot, "")
            candidate
        } catch (error: Throwable) {
            candidate.deleteRecursively()
            throw error
        }
    }

    /** Resuelve una ruta relativa y verifica que no atraviese enlaces ni salga de la base. */
    fun resolveInside(baseDirectory: File, relativePath: String): File {
        val normalized = BardoProtectedPathPolicy.normalize(relativePath)
            ?: throw IllegalArgumentException("Ruta relativa no segura: $relativePath")
        require(baseDirectory.isDirectory &&
            !Files.isSymbolicLink(baseDirectory.toPath())) {
            "La carpeta base no es una carpeta normal."
        }
        val canonicalBase = baseDirectory.canonicalFile
        var cursor = canonicalBase
        normalized.split('/').forEach { segment ->
            val next = File(cursor, segment)
            require(!Files.isSymbolicLink(next.toPath())) {
                "No se permiten enlaces simbólicos: $normalized"
            }
            val canonicalNext = next.canonicalFile
            require(isSameOrChild(canonicalBase, canonicalNext)) {
                "La ruta intenta escapar de la carpeta base: $normalized"
            }
            cursor = canonicalNext
        }
        return File(canonicalBase, normalized)
    }

    /** Solo elimina candidatos directos del workspace con identificador UUID. */
    fun deleteCandidate(candidateDirectory: File) = synchronized(lock) {
        require(!Files.isSymbolicLink(candidateDirectory.toPath())) {
            "No se puede eliminar una carpeta candidata enlazada."
        }
        val canonical = candidateDirectory.canonicalFile
        require(canonical.parentFile == workspaceRoot &&
            candidateDirectory.name.matches(
                Regex("^candidate-[0-9a-fA-F-]{36}$")
            )) {
            "Solo se pueden eliminar candidatos directos del workspace."
        }
        if (canonical.exists()) check(canonical.deleteRecursively()) {
            "No se pudo limpiar la copia candidata incompleta."
        }
    }

    private fun isSameOrChild(parent: File, candidate: File): Boolean {
        val parentPath = parent.canonicalPath.trimEnd(File.separatorChar)
        val candidatePath = candidate.canonicalPath
        return candidatePath == parentPath ||
            candidatePath.startsWith(parentPath + File.separator)
    }

    companion object {
        const val MAX_FILES = 3000
        const val MAX_FILE_BYTES = 2L * 1024L * 1024L
        const val MAX_TOTAL_BYTES = 60L * 1024L * 1024L
    }
}
