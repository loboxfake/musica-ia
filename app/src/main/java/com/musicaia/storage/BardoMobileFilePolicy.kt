package com.musicaia.storage

/**
 * Política de autorización para operaciones de BARDO sobre el árbol SAF
 * que el usuario haya concedido. No concede acceso por sí misma ni sustituye
 * la comprobación de permisos del proveedor DocumentProvider.
 */
enum class BardoMobileFileOperation {
    LIST,
    READ,
    CREATE_FILE,
    CREATE_DIRECTORY,
    MOVE_TO_TRASH,
    PERMANENT_DELETE
}

data class BardoMobileFileApproval(
    val operation: BardoMobileFileOperation,
    val relativePath: String,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    val nonce: String
)

data class BardoMobileFileDecision(
    val allowed: Boolean,
    val requiresUserApproval: Boolean,
    val normalizedPath: String?,
    val reason: String
)

object BardoMobileFilePolicy {
    const val ROOT_LABEL = "Descargas/BARDO"
    const val MAX_RELATIVE_PATH_CHARS = 512
    const val MAX_TEXT_READ_BYTES = 1_048_576L
    const val APPROVAL_VALIDITY_MILLIS = 120_000L

    private val protectedDeletePaths = setOf(
        "Modelos/gemma-4-E2B-it.litertlm"
    )

    fun normalizeRelativePath(rawPath: String): String? {
        if (rawPath == "" || rawPath == ".") return ""
        if (rawPath.length > MAX_RELATIVE_PATH_CHARS ||
            rawPath != rawPath.trim() || rawPath.startsWith("/") ||
            rawPath.contains('\\') || rawPath.contains(':') ||
            rawPath.any { it.isISOControl() }
        ) return null

        val parts = rawPath.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return null
        return parts.joinToString("/")
    }

    fun authorize(
        operation: BardoMobileFileOperation,
        rawPath: String,
        approval: BardoMobileFileApproval? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): BardoMobileFileDecision {
        val path = normalizeRelativePath(rawPath)
            ?: return denied("Ruta relativa no válida; se rechazan traversal, rutas absolutas y separadores alternativos.")

        if (path.isEmpty() && operation != BardoMobileFileOperation.LIST) {
            return denied("La raíz BARDO solo puede listarse; indica una ruta relativa para modificar archivos.")
        }
        if (operation == BardoMobileFileOperation.READ && path.length > MAX_RELATIVE_PATH_CHARS) {
            return denied("Ruta demasiado larga.")
        }

        if (operation == BardoMobileFileOperation.MOVE_TO_TRASH ||
            operation == BardoMobileFileOperation.PERMANENT_DELETE
        ) {
            if (path in protectedDeletePaths) {
                return denied("El modelo original protegido no se puede eliminar desde BARDO.")
            }
            if (!validApproval(approval, operation, path, nowMillis)) {
                return BardoMobileFileDecision(
                    allowed = false,
                    requiresUserApproval = true,
                    normalizedPath = path,
                    reason = if (operation == BardoMobileFileOperation.MOVE_TO_TRASH)
                        "Se requiere confirmación del usuario para mover el elemento a la papelera BARDO."
                    else
                        "Se requiere una confirmación expresa y reciente para la eliminación permanente."
                )
            }
        }

        return BardoMobileFileDecision(
            allowed = true,
            requiresUserApproval = false,
            normalizedPath = path,
            reason = "Operación permitida dentro del árbol BARDO concedido por el usuario."
        )
    }

    private fun validApproval(
        approval: BardoMobileFileApproval?,
        operation: BardoMobileFileOperation,
        path: String,
        nowMillis: Long
    ): Boolean {
        if (approval == null) return false
        return approval.operation == operation &&
            approval.relativePath == path &&
            approval.nonce.matches(Regex("^[A-Za-z0-9_-]{16,128}$")) &&
            approval.issuedAtMillis <= nowMillis &&
            approval.expiresAtMillis >= nowMillis &&
            approval.expiresAtMillis - approval.issuedAtMillis in 1..APPROVAL_VALIDITY_MILLIS
    }

    private fun denied(reason: String) = BardoMobileFileDecision(
        allowed = false,
        requiresUserApproval = false,
        normalizedPath = null,
        reason = reason
    )
}
