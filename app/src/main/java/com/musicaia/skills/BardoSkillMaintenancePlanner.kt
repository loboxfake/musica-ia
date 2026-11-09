package com.musicaia.skills

/**
 * Analiza el estado de las skills y propone mantenimiento.
 * Nunca desinstala, deshabilita ni modifica paquetes: produce recomendaciones
 * que la capa de autorización debe presentar al usuario antes de aplicarlas.
 */
enum class BardoSkillMaintenanceAction {
    KEEP,
    REVIEW_COMPATIBILITY,
    RETEST_REQUIRED,
    PROPOSE_DISABLE,
    PROPOSE_REMOVE
}

data class BardoSkillMaintenanceFinding(
    val skillId: String,
    val version: String,
    val action: BardoSkillMaintenanceAction,
    val reasons: List<String>
)

object BardoSkillMaintenancePlanner {
    fun evaluate(
        records: List<BardoSkillRecord>,
        registeredActionIds: Set<String>,
        currentApi: Int = BardoSkillManifestValidator.CURRENT_API
    ): List<BardoSkillMaintenanceFinding> {
        val newestBySkill = records.groupBy { it.manifest.id }.mapValues { (_, versions) ->
            versions.maxWithOrNull { first, second ->
                compareVersions(first.manifest.version, second.manifest.version)
            }
        }

        return records.map { record ->
            val reasons = mutableListOf<String>()
            val manifest = record.manifest
            val incompatible = currentApi !in manifest.apiMinimum..manifest.apiMaximum
            val missingActions = manifest.actions.filterNot { it in registeredActionIds }
            val latest = newestBySkill[manifest.id]
            val superseded = latest != null && latest.manifest.version != manifest.version &&
                latest.lifecycle == BardoSkillLifecycle.ENABLED

            if (incompatible) reasons += "La API de BARDO ya no es compatible con esta versión."
            if (missingActions.isNotEmpty()) reasons += "Acciones ausentes: " + missingActions.joinToString()
            if (superseded) reasons += "Existe una versión posterior habilitada."
            if (record.lifecycle == BardoSkillLifecycle.REJECTED) reasons += "La skill está rechazada."
            if (record.lifecycle == BardoSkillLifecycle.ENABLED &&
                (!record.lastTestPassed || record.lastTestVerifierId.isNullOrBlank() ||
                    record.lastTestEvidenceRefs.isEmpty())
            ) reasons += "No conserva evidencia de smoke-test independiente."

            val action = when {
                record.lifecycle == BardoSkillLifecycle.REJECTED || superseded ->
                    BardoSkillMaintenanceAction.PROPOSE_REMOVE
                incompatible || missingActions.isNotEmpty() ->
                    BardoSkillMaintenanceAction.PROPOSE_DISABLE
                record.lifecycle == BardoSkillLifecycle.ENABLED &&
                    (!record.lastTestPassed || record.lastTestVerifierId.isNullOrBlank() ||
                        record.lastTestEvidenceRefs.isEmpty()) ->
                    BardoSkillMaintenanceAction.RETEST_REQUIRED
                record.lifecycle == BardoSkillLifecycle.QUARANTINED ||
                    record.lifecycle == BardoSkillLifecycle.INSTALLED_DISABLED ->
                    BardoSkillMaintenanceAction.REVIEW_COMPATIBILITY
                else -> BardoSkillMaintenanceAction.KEEP
            }

            BardoSkillMaintenanceFinding(manifest.id, manifest.version, action, reasons)
        }.sortedWith(compareBy<BardoSkillMaintenanceFinding> { it.skillId }.thenBy { it.version })
    }

    private fun compareVersions(first: String, second: String): Int {
        val left = first.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val right = second.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (index in 0..2) {
            val comparison = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        val leftPreRelease = first.substringAfter('-', "")
        val rightPreRelease = second.substringAfter('-', "")
        return when {
            leftPreRelease.isEmpty() && rightPreRelease.isNotEmpty() -> 1
            leftPreRelease.isNotEmpty() && rightPreRelease.isEmpty() -> -1
            else -> leftPreRelease.compareTo(rightPreRelease)
        }
    }
}
