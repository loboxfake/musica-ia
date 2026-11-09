package com.musicaia.actualizaciones

/**
 * Contrato de validación para actualizaciones incrementales de BARDO.
 * Valida metadatos y límites; no instala código, no cambia permisos y no ejecuta migraciones.
 * La instalación requiere un aplicador transaccional separado y aprobación explícita del usuario.
 */
enum class BardoUpdateLevel { CONFIGURATION, MODULE, SHARED_CONTRACT, STRUCTURAL }
enum class BardoUpdateOperation { ADD, MODIFY }

data class BardoUpdateFile(
    val path: String,
    val operation: BardoUpdateOperation,
    val previousSha256: String?,
    val targetSha256: String,
    val sizeBytes: Long
)

data class BardoIncrementalUpdateManifest(
    val updateId: String,
    val fromVersion: String,
    val toVersion: String,
    val baseCommit: String,
    val targetCommit: String,
    val level: BardoUpdateLevel,
    val files: List<BardoUpdateFile>,
    val requiredPermissions: Set<String>,
    val approvedPermissions: Set<String>,
    val evidenceRefs: List<String>,
    val rollbackPlanRef: String,
    val dataMigrationId: String? = null,
    val migrationIsReversible: Boolean = true
)

data class BardoUpdateValidation(
    val accepted: Boolean,
    val requiresHumanReview: Boolean,
    val requiresFullApk: Boolean,
    val errors: List<String>
)

/** Política fail-closed para paquetes de actualización incremental. */
object BardoIncrementalUpdatePolicy {
    const val MAX_FILES = 30
    const val MAX_TOTAL_BYTES = 100L * 1024L * 1024L
    private val versionPattern = Regex("^[0-9]+\\\\.[0-9]+\\\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$")
    private val idPattern = Regex("^[A-Za-z0-9._-]{8,80}$")
    private val sha256Pattern = Regex("^[a-fA-F0-9]{64}$")
    private val commitPattern = Regex("^(?:[a-fA-F0-9]{40}|[a-fA-F0-9]{64})$")
    private val githubEvidencePattern = Regex("^https://github\\\\.com/loboxfake/musica-ia/actions/runs/[0-9]{6,20}$")
    private val localEvidencePattern = Regex("^bardo-audit://[A-Za-z0-9._/-]{3,200}$")

    private val forbiddenExact = setOf(
        "app/src/main/java/com/musicaia/MainActivity.kt",
        "app/src/main/java/com/musicaia/modulos/Modo3MezclaVozUsuario.kt",
        "app/src/main/AndroidManifest.xml", "app/build.gradle.kts",
        "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
        "gradle/wrapper/gradle-wrapper.properties"
    )
    private val forbiddenPrefixes = listOf(
        ".github/", ".git/", "gradle/", "app/src/main/assets/",
        "app/src/main/jniLibs/", "keystore/", "signing/"
    )

    fun validate(manifest: BardoIncrementalUpdateManifest): BardoUpdateValidation {
        val errors = mutableListOf<String>()
        if (!idPattern.matches(manifest.updateId)) errors += "UPDATE_ID_INVALID"
        if (!versionPattern.matches(manifest.fromVersion) || !versionPattern.matches(manifest.toVersion)) errors += "VERSION_FORMAT_INVALID"
        if (manifest.fromVersion == manifest.toVersion) errors += "VERSION_NOT_CHANGED"
        if (!commitPattern.matches(manifest.baseCommit) || !commitPattern.matches(manifest.targetCommit)) errors += "COMMIT_REFERENCE_INVALID"
        if (manifest.baseCommit.equals(manifest.targetCommit, ignoreCase = true)) errors += "SOURCE_COMMIT_NOT_CHANGED"
        if (manifest.files.isEmpty() || manifest.files.size > MAX_FILES) errors += "FILE_COUNT_OUT_OF_RANGE"
        if (manifest.files.map { it.path }.distinct().size != manifest.files.size) errors += "DUPLICATE_PATHS"
        var totalBytes = 0L
        manifest.files.forEach { item ->
            val path = normalizePath(item.path)
            if (path == null) { errors += "UNSAFE_PATH:" + item.path; return@forEach }
            if (path in forbiddenExact || forbiddenPrefixes.any { path.startsWith(it) } || path.endsWith("Modo3MezclaVozUsuario.kt", true)) errors += "PROTECTED_PATH:" + path
            if (!isAllowedPath(path)) errors += "PATH_OUTSIDE_UPDATE_ALLOWLIST:" + path
            if (!sha256Pattern.matches(item.targetSha256)) errors += "TARGET_HASH_INVALID:" + path
            when (item.operation) {
                BardoUpdateOperation.ADD -> if (item.previousSha256 != null) errors += "NEW_FILE_HAS_PREVIOUS_HASH:" + path
                BardoUpdateOperation.MODIFY -> if (item.previousSha256 == null || !sha256Pattern.matches(item.previousSha256)) errors += "PREVIOUS_HASH_MISSING_OR_INVALID:" + path
            }
            if (item.sizeBytes <= 0L) errors += "FILE_SIZE_INVALID:" + path
            else if (totalBytes <= MAX_TOTAL_BYTES) totalBytes = if (Long.MAX_VALUE - totalBytes < item.sizeBytes) Long.MAX_VALUE else totalBytes + item.sizeBytes
        }
        if (totalBytes > MAX_TOTAL_BYTES) errors += "UPDATE_SIZE_LIMIT_EXCEEDED"
        if (!manifest.approvedPermissions.containsAll(manifest.requiredPermissions)) errors += "PERMISSION_NOT_EXPLICITLY_APPROVED"
        if (manifest.evidenceRefs.isEmpty() || manifest.evidenceRefs.size > 20 || manifest.evidenceRefs.any { !githubEvidencePattern.matches(it) && !localEvidencePattern.matches(it) }) errors += "EVIDENCE_REFERENCE_INVALID"
        if (manifest.rollbackPlanRef.isBlank() || manifest.rollbackPlanRef.length > 500 || manifest.rollbackPlanRef.any { it.isWhitespace() }) errors += "ROLLBACK_PLAN_MISSING"
        if (manifest.dataMigrationId != null && (!idPattern.matches(manifest.dataMigrationId) || !manifest.migrationIsReversible)) errors += "DATA_MIGRATION_NOT_REVERSIBLE"
        val humanReview = manifest.level == BardoUpdateLevel.SHARED_CONTRACT || manifest.level == BardoUpdateLevel.STRUCTURAL
        val fullApk = manifest.files.any { it.path.startsWith("app/src/main/java/") || it.path.startsWith("app/src/main/res/") } || manifest.level == BardoUpdateLevel.STRUCTURAL
        if (manifest.level == BardoUpdateLevel.CONFIGURATION && manifest.files.any { it.path.startsWith("app/src/main/java/") }) errors += "CONFIGURATION_LEVEL_CONTAINS_CODE"
        if (manifest.level == BardoUpdateLevel.MODULE && manifest.files.any { it.path.startsWith("app/src/main/res/") }) errors += "MODULE_LEVEL_CONTAINS_SHARED_RESOURCES"
        return BardoUpdateValidation(errors.isEmpty(), humanReview, fullApk, errors.distinct())
    }

    private fun normalizePath(raw: String): String? {
        if (raw.isBlank() || raw != raw.trim() || raw.startsWith("/") || raw.contains('\\\\') || raw.contains(':') || raw.startsWith("~") || raw.any { it.isISOControl() }) return null
        val parts = raw.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return null
        return raw
    }
    private fun isAllowedPath(path: String): Boolean = path.startsWith("app/src/main/java/") || path.startsWith("app/src/main/res/") || path.startsWith("docs/")
}
