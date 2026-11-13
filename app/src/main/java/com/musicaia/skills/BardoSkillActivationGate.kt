package com.musicaia.skills

import java.io.File
import java.nio.file.Files

/**
 * Preflight adicional de activación para paquetes declarativos.
 *
 * La importación ya valida el archivo .bskill y el ejecutor vuelve a validar
 * el workflow completo antes de ejecutar la primera acción. Este gate también
 * valida el workflow almacenado inmediatamente antes de habilitar la skill.
 * No sustituye la aprobación explícita ni los smoke-tests de confianza del
 * BardoSkillManager.
 */
class BardoSkillActivationGate(
    private val manager: BardoSkillManager,
    private val registeredActionIds: Set<String>
) {
    fun enable(
        skillId: String,
        version: String,
        explicitlyApproved: Boolean,
        approvedPermissions: Set<BardoSkillPermission>,
        nowMillis: Long = System.currentTimeMillis()
    ): BardoSkillOperationResult {
        val record = manager.list().firstOrNull {
            it.manifest.id == skillId && it.manifest.version == version
        } ?: return rejected(null, "La versión de skill no está registrada.")

        if (record.lifecycle !in setOf(
                BardoSkillLifecycle.INSTALLED_DISABLED,
                BardoSkillLifecycle.DISABLED
            )
        ) {
            return rejected(record, "Solo se puede habilitar una skill instalada y deshabilitada.")
        }
        if (!explicitlyApproved) {
            return rejected(record, "La activación requiere aprobación explícita.")
        }

        val path = record.installedPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: return rejected(record, "No hay un workflow instalado que se pueda validar.")

        if (!path.isFile || Files.isSymbolicLink(path.toPath())) {
            return rejected(record, "El workflow instalado no es un archivo regular.")
        }

        val validation = BardoSkillWorkflowPackageValidator.validate(
            file = path,
            declaredActions = record.manifest.actions,
            registeredActions = registeredActionIds
        )
        if (!validation.accepted) {
            return rejected(
                record,
                "Preflight del workflow rechazado: " + validation.errors.joinToString(" ").take(1200)
            )
        }

        // El gestor vuelve a comprobar integridad, permisos, dependencias y
        // smoke-tests confiables bajo su propio lock antes de cambiar lifecycle.
        return manager.enable(
            skillId = skillId,
            version = version,
            explicitlyApproved = true,
            approvedPermissions = approvedPermissions,
            nowMillis = nowMillis
        )
    }

    private fun rejected(
        record: BardoSkillRecord?,
        message: String
    ) = BardoSkillOperationResult(
        succeeded = false,
        record = record,
        message = message.take(1600)
    )
}
