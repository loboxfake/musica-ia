package com.musicaia.seguridad

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Ledger privado persistente para impedir repetición de peticiones y revocar
 * delegaciones. El directorio debe estar dentro del almacenamiento privado de BARDO.
 */
class BardoFileAuthorityLedger(private val directory: File) :
    BardoNonceLedger,
    BardoGrantRevocationStore {

    init {
        synchronized(processLock) {
            if (!directory.exists()) {
                check(directory.mkdirs()) { "No se pudo crear el ledger de autoridad." }
            }
            require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) {
                "El ledger de autoridad debe estar en una carpeta normal."
            }
        }
    }

    override fun consumeOnce(
        requestId: String,
        expiresAtMillis: Long,
        nowMillis: Long
    ): Boolean = synchronized(processLock) {
        require(requestId.matches(Regex("^[A-Za-z0-9_-]{16,100}$"))) {
            "Identificador de petición no válido."
        }
        require(expiresAtMillis > nowMillis) { "No se registra un nonce ya caducado." }
        val file = safeFile(NONCE_FILE)
        val entries = readLinesStrict(file).mapNotNull { line ->
            val fields = line.split('\t')
            require(fields.size == 2) { "El ledger de nonces está corrupto." }
            val expiry = fields[1].toLongOrNull()
                ?: throw IllegalStateException("El ledger de nonces contiene una caducidad inválida.")
            if (expiry > nowMillis) fields[0] to expiry else null
        }.toMutableList()

        if (entries.any { it.first == requestId }) return@synchronized false
        entries += requestId to expiresAtMillis
        require(entries.size <= MAX_ACTIVE_NONCES) {
            "El ledger alcanzó el límite de peticiones activas; se bloquea por seguridad."
        }
        writeAtomically(file, entries.map { it.first + "\t" + it.second })
        true
    }

    override fun isRevoked(grantId: String): Boolean = synchronized(processLock) {
        require(grantId.matches(Regex("^[A-Za-z0-9_-]{16,80}$"))) {
            "Identificador de delegación no válido."
        }
        readLinesStrict(safeFile(REVOCATION_FILE)).contains(grantId)
    }

    fun revokeGrant(grantId: String) = synchronized(processLock) {
        require(grantId.matches(Regex("^[A-Za-z0-9_-]{16,80}$"))) {
            "Identificador de delegación no válido."
        }
        val file = safeFile(REVOCATION_FILE)
        val existing = readLinesStrict(file).toMutableSet()
        if (existing.add(grantId)) {
            require(existing.size <= MAX_REVOKED_GRANTS) {
                "El registro de revocaciones alcanzó su límite."
            }
            writeAtomically(file, existing.sorted())
        }
    }

    private fun safeFile(name: String): File {
        require(name.matches(Regex("^[a-z0-9-]+\\.txt$"))) { "Nombre interno no válido." }
        val file = File(directory.canonicalFile, name)
        require(file.canonicalFile.parentFile == directory.canonicalFile &&
            !Files.isSymbolicLink(file.toPath())) {
            "El archivo del ledger intenta salir de su carpeta o es un enlace simbólico."
        }
        return file
    }

    private fun readLinesStrict(file: File): List<String> {
        if (!file.exists()) return emptyList()
        require(file.isFile && !Files.isSymbolicLink(file.toPath())) {
            "El ledger no es un archivo regular."
        }
        return file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
    }

    private fun writeAtomically(file: File, lines: List<String>) {
        val temporary = File(directory, file.name + "." + UUID.randomUUID() + ".tmp")
        try {
            FileOutputStream(temporary, false).use { output ->
                lines.forEach { line ->
                    output.write(line.toByteArray(Charsets.UTF_8))
                    output.write('\n'.code)
                }
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            temporary.delete()
        }
    }

    companion object {
        private val processLock = Any()
        private const val NONCE_FILE = "nonce-ledger.txt"
        private const val REVOCATION_FILE = "revoked-grants.txt"
        const val MAX_ACTIVE_NONCES = 50_000
        const val MAX_REVOKED_GRANTS = 50_000
    }
}
