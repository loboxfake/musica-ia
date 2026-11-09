package com.musicaia.actualizaciones

import com.musicaia.desarrollo.BardoProtectedPathPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.security.MessageDigest

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
    val targetFingerprintSha256: String,
    val level: BardoUpdateLevel,
    val files: List<BardoUpdateFile>,
    val requiredPermissions: Set<String>,
    val approvedPermissions: Set<String>,
    val evidenceRefs: List<String>,
    val rollbackPlanRef: String,
    val dataMigrationId: String? = null,
    val migrationIsReversible: Boolean = true
) {
    /** Serialización estable para conservar el manifiesto junto al candidato. */
    fun toJson(): String {
        val fileArray = JSONArray()
        files.forEach { item ->
            fileArray.put(JSONObject()
                .put("path", item.path)
                .put("operation", item.operation.name)
                .put("previousSha256", item.previousSha256)
                .put("targetSha256", item.targetSha256)
                .put("sizeBytes", item.sizeBytes))
        }
        return JSONObject()
            .put("updateId", updateId)
            .put("fromVersion", fromVersion)
            .put("toVersion", toVersion)
            .put("baseCommit", baseCommit)
            .put("targetFingerprintSha256", targetFingerprintSha256)
            .put("level", level.name)
            .put("files", fileArray)
            .put("requiredPermissions", JSONArray(requiredPermissions.sorted()))
            .put("approvedPermissions", JSONArray(approvedPermissions.sorted()))
            .put("evidenceRefs", JSONArray(evidenceRefs))
            .put("rollbackPlanRef", rollbackPlanRef)
            .put("dataMigrationId", dataMigrationId)
            .put("migrationIsReversible", migrationIsReversible)
            .toString()
    }
}

data class BardoUpdateValidation(
    val accepted: Boolean,
    val requiresHumanReview: Boolean,
    val requiresArchitectureReview: Boolean,
    val requiresFullApk: Boolean,
    val errors: List<String>
)

/** Política fail-closed para paquetes de actualización incremental. */
object BardoIncrementalUpdatePolicy {
    const val MAX_FILES = 30
    const val MAX_TOTAL_BYTES = 100L * 1024L * 1024L
    private val versionPattern = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$")
    private val idPattern = Regex("^[A-Za-z0-9._-]{8,80}$")
    private val sha256Pattern = Regex("^[a-fA-F0-9]{64}$")
    private val commitPattern = Regex("^(?:[a-fA-F0-9]{40}|[a-fA-F0-9]{64})$")
    private val githubEvidencePattern = Regex("^https://github\\.com/loboxfake/musica-ia/actions/runs/[0-9]{6,20}$")
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
        if (!commitPattern.matches(manifest.baseCommit)) errors += "BASE_COMMIT_INVALID"
        if (!sha256Pattern.matches(manifest.targetFingerprintSha256)) errors += "TARGET_FINGERPRINT_INVALID"
        if (manifest.files.isEmpty() || manifest.files.size > MAX_FILES) errors += "FILE_COUNT_OUT_OF_RANGE"
        if (manifest.files.map { it.path }.distinct().size != manifest.files.size) errors += "DUPLICATE_PATHS"
        var totalBytes = 0L
        manifest.files.forEach { item ->
            val path = normalizePath(item.path)
            if (path == null) { errors += "UNSAFE_PATH:" + item.path; return@forEach }
            if (path in forbiddenExact || forbiddenPrefixes.any { path.startsWith(it) } || path.endsWith("Modo3MezclaVozUsuario.kt", true) || !BardoProtectedPathPolicy.canModifyExisting(path)) errors += "PROTECTED_PATH:" + path
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
        if (manifest.level == BardoUpdateLevel.MODULE) {
            if (manifest.files.any { it.path.startsWith("app/src/main/res/") }) errors += "MODULE_LEVEL_CONTAINS_SHARED_RESOURCES"
            val packages = manifest.files.map { it.path.substringBeforeLast('/') }.distinct()
            if (packages.size > 1) errors += "MODULE_UPDATE_CROSSES_PACKAGES"
        }
        if (manifest.rollbackPlanRef.isNotBlank() && !manifest.rollbackPlanRef.startsWith("bardo-rollback://")) errors += "ROLLBACK_PLAN_REFERENCE_INVALID"
        return BardoUpdateValidation(errors.isEmpty(), true, humanReview, fullApk, errors.distinct())
    }

    private fun normalizePath(raw: String): String? {
        if (raw.isBlank() || raw != raw.trim() || raw.startsWith("/") || raw.contains('\\') || raw.contains(':') || raw.startsWith("~") || raw.any { it.isISOControl() }) return null
        val parts = raw.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return null
        return raw
    }
    private fun isAllowedPath(path: String): Boolean = path.startsWith("app/src/main/java/") || path.startsWith("app/src/main/res/") || path.startsWith("docs/")
}


/**
 * Construye el manifiesto a partir de los bytes reales del snapshot y del
 * candidato. Solo admite modificaciones de archivos existentes; el aplicador
 * actual no instala archivos nuevos ni elimina archivos.
 */
object BardoIncrementalUpdateManifestFactory {
    fun create(
        snapshotRoot: File,
        candidateRoot: File,
        changedFiles: List<String>,
        baseCommit: String,
        fromVersion: String,
        toVersion: String,
        patchText: String,
        evidenceRefs: List<String>,
        rollbackPlanRef: String
    ): BardoIncrementalUpdateManifest {
        require(changedFiles.isNotEmpty() && changedFiles.size <= BardoIncrementalUpdatePolicy.MAX_FILES)
        require(changedFiles.distinct().size == changedFiles.size)
        require(snapshotRoot.isDirectory && candidateRoot.isDirectory)
        require(!Files.isSymbolicLink(snapshotRoot.toPath()) && !Files.isSymbolicLink(candidateRoot.toPath()))
        val entries = changedFiles.sorted().map { path ->
            require(BardoProtectedPathPolicy.canModifyExisting(path)) {
                "La ruta no está permitida para una actualización incremental: " + path
            }
            val source = resolveRegularFile(snapshotRoot, path)
            val target = resolveRegularFile(candidateRoot, path)
            BardoUpdateFile(
                path = path,
                operation = BardoUpdateOperation.MODIFY,
                previousSha256 = sha256(source),
                targetSha256 = sha256(target),
                sizeBytes = target.length()
            )
        }
        val packageDirs = changedFiles.map { it.substringBeforeLast('/') }.distinct()
        val level = when {
            changedFiles.all { it.startsWith("docs/") } -> BardoUpdateLevel.CONFIGURATION
            changedFiles.all { it.startsWith("app/src/main/java/") } &&
                packageDirs.size == 1 -> BardoUpdateLevel.MODULE
            else -> BardoUpdateLevel.SHARED_CONTRACT
        }
        val patchBytes = patchText.toByteArray(Charsets.UTF_8)
        require(patchBytes.isNotEmpty()) { "El diff de actualización está vacío." }
        val manifest = BardoIncrementalUpdateManifest(
            updateId = "update-" + System.currentTimeMillis().toString() + "-" +
                sha256(patchBytes).take(12),
            fromVersion = fromVersion,
            toVersion = toVersion,
            baseCommit = baseCommit,
            targetFingerprintSha256 = sha256(patchBytes),
            level = level,
            files = entries,
            requiredPermissions = emptySet(),
            approvedPermissions = emptySet(),
            evidenceRefs = evidenceRefs.distinct(),
            rollbackPlanRef = rollbackPlanRef
        )
        val validation = BardoIncrementalUpdatePolicy.validate(manifest)
        require(validation.accepted) {
            "Manifiesto incremental rechazado: " + validation.errors.joinToString(", ")
        }
        return manifest
    }

    private fun resolveRegularFile(root: File, relativePath: String): File {
        val canonicalRoot = root.canonicalFile
        var current = canonicalRoot
        relativePath.split('/').forEach { segment ->
            current = File(current, segment)
            require(!Files.isSymbolicLink(current.toPath())) {
                "El candidato contiene un enlace simbólico: " + relativePath
            }
        }
        val canonical = current.canonicalFile
        require(canonical.path.startsWith(canonicalRoot.path + File.separator))
        require(canonical.isFile) { "Falta el archivo de actualización: " + relativePath }
        require(canonical.length() in 1L..BardoWorkspaceFileLimit.MAX_BYTES) {
            "El archivo supera el límite de tamaño o está vacío: " + relativePath
        }
        return canonical
    }

    private fun sha256(file: File): String = FileInputStream(file).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

/** Límite conservador para el manifiesto, independiente del límite del workspace. */
private object BardoWorkspaceFileLimit {
    const val MAX_BYTES = 2L * 1024L * 1024L
}
