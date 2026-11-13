package com.musicaia.modulos

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Publica un archivo ya terminado desde un temporal del mismo directorio.
 * El destino anterior permanece intacto si la preparación o validación falla.
 */
internal object BardoAtomicFilePublisher {
    fun publish(staged: File, destination: File) {
        require(staged.isFile && staged.length() > 0L) {
            "El archivo temporal no existe o está vacío."
        }
        require(staged.canonicalFile != destination.canonicalFile) {
            "El archivo temporal y el destino deben ser distintos."
        }
        val stagedParent = staged.absoluteFile.parentFile?.canonicalFile
            ?: error("No se pudo resolver el directorio temporal.")
        val destinationParent = destination.absoluteFile.parentFile?.canonicalFile
            ?: error("No se pudo resolver el directorio de destino.")
        require(stagedParent == destinationParent) {
            "La publicación atómica requiere temporales en el mismo directorio."
        }

        try {
            Files.move(
                staged.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                staged.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }

        check(destination.isFile && destination.length() > 0L) {
            "El archivo publicado no está disponible."
        }
    }
}
