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

    /**
     * Copia un archivo completo a un temporal junto al destino y solo entonces lo publica.
     * Si la copia falla, el destino anterior no se toca.
     */
    fun copyAndPublish(source: File, destination: File) {
        require(source.isFile && source.length() > 0L) {
            "El archivo de origen no existe o está vacío."
        }
        require(source.canonicalFile != destination.canonicalFile) {
            "El origen y el destino deben ser distintos."
        }
        val parent = destination.absoluteFile.parentFile
            ?: error("No se pudo resolver el directorio de destino.")
        require(parent.isDirectory) {
            "El directorio de destino no existe."
        }

        val staged = Files.createTempFile(parent.toPath(), ".bardo-copy-", ".tmp").toFile()
        try {
            source.inputStream().buffered().use { input ->
                staged.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            check(staged.length() == source.length()) {
                "La copia temporal no coincide con el tamaño del archivo de origen."
            }
            publish(staged, destination)
        } finally {
            if (staged.exists()) staged.delete()
        }
    }
}
