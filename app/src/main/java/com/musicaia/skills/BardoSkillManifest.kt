package com.musicaia.skills

enum class BardoSkillPermission {
    READ_BARDO_DATA,
    STUDY_LOCAL,
    WRITE_BARDO_WORKSPACE,
    NETWORK_ACCESS,
    GITHUB_ACTIONS,
    AUDIO_RECORDING,
    AUDIO_FILE_ACCESS,
    MANAGE_BARDO_WORKSPACE
}

enum class BardoSkillPackageKind {
    DECLARATIVE_TOOLCHAIN,
    EXECUTABLE_CODE
}

data class BardoSkillDependency(
    val skillId: String,
    val minimumVersion: String
)

data class BardoSkillManifest(
    val id: String,
    val name: String,
    val version: String,
    val apiMinimum: Int,
    val apiMaximum: Int,
    val description: String,
    val source: String,
    val license: String,
    val packageSha256: String,
    val packageSizeBytes: Long,
    val kind: BardoSkillPackageKind,
    val actions: Set<String>,
    val permissions: Set<BardoSkillPermission>,
    val dependencies: List<BardoSkillDependency> = emptyList(),
    val licenseAcceptedByUser: Boolean = false
)

data class BardoSkillValidation(
    val accepted: Boolean,
    val errors: List<String>,
    val requiredPermissions: Set<BardoSkillPermission>
)

/**
 * Validador declarativo. No descarga, instala ni ejecuta código.
 * Una skill solo puede invocar acciones tipadas que ya estén registradas en BARDO.
 */
object BardoSkillManifestValidator {
    const val CURRENT_API = 1
    const val MAX_PACKAGE_BYTES = 10L * 1024L * 1024L
    private val idPattern = Regex("^[a-z][a-z0-9._-]{2,63}$")
    private val versionPattern = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-[0-9A-Za-z.-]+)?$")
    private val hashPattern = Regex("^[0-9a-f]{64}$")

    private val actionPermissions = mapOf(
        "self_inventory.read" to setOf(BardoSkillPermission.READ_BARDO_DATA),
        "knowledge.search" to setOf(BardoSkillPermission.READ_BARDO_DATA),
        "developer.audit_snapshot" to setOf(BardoSkillPermission.STUDY_LOCAL),
        "developer.ci_diagnostics" to setOf(BardoSkillPermission.READ_BARDO_DATA),
        "documents.read_imported" to setOf(BardoSkillPermission.READ_BARDO_DATA),
        "music.create_plan" to setOf(BardoSkillPermission.READ_BARDO_DATA),
        "music.generate_wav" to setOf(BardoSkillPermission.WRITE_BARDO_WORKSPACE),
        "audio.mix_user_voice" to setOf(
            BardoSkillPermission.READ_BARDO_DATA,
            BardoSkillPermission.WRITE_BARDO_WORKSPACE,
            BardoSkillPermission.AUDIO_FILE_ACCESS
        ),
        "audio.record_user_voice" to setOf(
            BardoSkillPermission.AUDIO_RECORDING,
            BardoSkillPermission.WRITE_BARDO_WORKSPACE
        ),
        "github.validate_candidate" to setOf(BardoSkillPermission.GITHUB_ACTIONS),
        "github.create_draft_pr" to setOf(BardoSkillPermission.GITHUB_ACTIONS),
        "web.research" to setOf(BardoSkillPermission.NETWORK_ACCESS),
        "mobile_files.list" to setOf(BardoSkillPermission.READ_BARDO_DATA),
        "mobile_files.read" to setOf(BardoSkillPermission.READ_BARDO_DATA),
        "mobile_files.create_directory" to setOf(BardoSkillPermission.MANAGE_BARDO_WORKSPACE),
        "mobile_files.create_text_file" to setOf(BardoSkillPermission.MANAGE_BARDO_WORKSPACE),
        "mobile_files.request_delete" to setOf(BardoSkillPermission.MANAGE_BARDO_WORKSPACE),
        "mobile_files.pending_deletions" to setOf(BardoSkillPermission.READ_BARDO_DATA)
    )

    fun supportedActionIds(): Set<String> = actionPermissions.keys.toSet()

    fun validate(
        manifest: BardoSkillManifest,
        registeredActionIds: Set<String> = supportedActionIds()
    ): BardoSkillValidation {
        val errors = mutableListOf<String>()
        val required = mutableSetOf<BardoSkillPermission>()

        if (!idPattern.matches(manifest.id)) errors += "ID de skill no válido."
        if (manifest.name.isBlank() || manifest.name.length > 100) errors += "Nombre no válido."
        if (!versionPattern.matches(manifest.version)) errors += "La versión debe seguir SemVer."
        if (manifest.apiMinimum < 1 || manifest.apiMaximum < manifest.apiMinimum ||
            CURRENT_API !in manifest.apiMinimum..manifest.apiMaximum
        ) errors += "La skill no es compatible con la API actual de BARDO."
        if (manifest.description.isBlank() || manifest.description.length > 2000) {
            errors += "Descripción ausente o demasiado larga."
        }
        val sourceIsHttps = manifest.source.startsWith("https://")
        val sourceIsLocal = manifest.source.startsWith("local://")
        if ((!sourceIsHttps && !sourceIsLocal) || manifest.source.length > 1000) {
            errors += "El origen debe ser HTTPS o un identificador local."
        }
        if (manifest.license.isBlank() || manifest.license.equals("unknown", true)) {
            errors += "La licencia debe identificarse antes de instalar."
        }
        if (!hashPattern.matches(manifest.packageSha256)) errors += "SHA-256 de paquete no válido."
        if (manifest.packageSizeBytes !in 1..MAX_PACKAGE_BYTES) {
            errors += "El paquete supera el límite de 10 MiB o está vacío."
        }
        if (!manifest.licenseAcceptedByUser) errors += "El usuario aún no ha aceptado la licencia."
        if (manifest.kind != BardoSkillPackageKind.DECLARATIVE_TOOLCHAIN) {
            errors += "No se admite código ejecutable arbitrario como skill."
        }
        if (manifest.actions.isEmpty() || manifest.actions.size > 20) {
            errors += "La skill debe declarar entre 1 y 20 acciones."
        }
        if (manifest.dependencies.size > 20) errors += "Demasiadas dependencias."
        if (manifest.dependencies.map { it.skillId }.distinct().size != manifest.dependencies.size) {
            errors += "La lista de dependencias contiene IDs duplicados."
        }

        manifest.actions.forEach { action ->
            val permissionsForAction = actionPermissions[action]
            if (permissionsForAction == null || action !in registeredActionIds) {
                errors += "Acción no registrada o no permitida: $action"
            } else {
                required += permissionsForAction
            }
        }
        if (!manifest.permissions.containsAll(required)) {
            errors += "Faltan permisos requeridos por las acciones declaradas."
        }
        if (manifest.permissions.size > BardoSkillPermission.entries.size) {
            errors += "La skill solicita permisos desconocidos."
        }
        return BardoSkillValidation(errors.isEmpty(), errors, required)
    }
}
