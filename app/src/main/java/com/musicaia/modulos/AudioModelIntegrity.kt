package com.musicaia.modulos

import java.io.File
import java.security.MessageDigest

/** Verificación de los cuatro pesos locales de Stable Audio frente al manifiesto empaquetado. */
internal object AudioModelIntegrity {
    fun parseManifest(content: String, requiredFiles: Set<String>): Map<String, String> {
        require(requiredFiles.isNotEmpty()) { "La lista de pesos requeridos está vacía." }
        val result = linkedMapOf<String, String>()
        content.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
            val match = Regex("""^([0-9a-fA-F]{64}) {2}([A-Za-z0-9._-]+)$""").matchEntire(line)
                ?: throw IllegalArgumentException("Línea de SHA256SUMS no válida.")
            val name = match.groupValues[2]
            require(name !in setOf(".", "..") && '/' !in name && '\\' !in name) {
                "Nombre de archivo no permitido en SHA256SUMS."
            }
            require(result.put(name, match.groupValues[1].lowercase()) == null) {
                "SHA-256 duplicado para $name."
            }
        }
        require(result.keys == requiredFiles) {
            "SHA256SUMS no contiene exactamente los pesos requeridos."
        }
        return result
    }

    fun sha256(file: File): String {
        require(file.isFile) { "No existe el archivo que se va a verificar: ${file.name}" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun matches(file: File, expectedSha256: String): Boolean {
        if (!file.isFile || !Regex("""^[0-9a-fA-F]{64}$""").matches(expectedSha256)) return false
        return sha256(file).equals(expectedSha256, ignoreCase = true)
    }
}
