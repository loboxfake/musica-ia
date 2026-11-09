package com.musicaia.skills

import com.musicaia.herramientas.BardoToolDescriptor
import com.musicaia.herramientas.BardoToolHandler
import com.musicaia.herramientas.BardoToolOutput
import com.musicaia.seguridad.BardoPermissionScope
import java.io.File

/** Permite al planificador crear paquetes declarativos en borradores privados, nunca instalarlos. */
class BardoSkillDraftToolHandler(
    private val builder: BardoSkillDraftBuilder
) : BardoToolHandler {
    override val descriptor = BardoToolDescriptor(
        id = TOOL_ID,
        description = "Crea un borrador privado de skill declarativa. Argumentos: id, name, version, description, license, workflow_json. Solo combina acciones tipadas registradas; no instala, no habilita ni ejecuta.",
        requiredScope = BardoPermissionScope.CREATE_LOCAL_CANDIDATE,
        maxArguments = 6,
        maxValueChars = BardoSkillDraftBuilder.MAX_WORKFLOW_CHARS + 1_000
    )

    override fun execute(arguments: Map<String, String>): BardoToolOutput {
        val expected = setOf("id", "name", "version", "description", "license", "workflow_json")
        val missing = expected - arguments.keys
        val extra = arguments.keys - expected
        if (missing.isNotEmpty() || extra.isNotEmpty()) {
            return BardoToolOutput(
                false, "",
                "Argumentos incorrectos. Faltan=" + missing.sorted().joinToString() +
                    "; adicionales=" + extra.sorted().joinToString()
            )
        }
        val result = builder.createDraft(
            id = arguments.getValue("id"),
            name = arguments.getValue("name"),
            version = arguments.getValue("version"),
            description = arguments.getValue("description"),
            license = arguments.getValue("license"),
            workflowJson = arguments.getValue("workflow_json")
        )
        if (!result.succeeded) return BardoToolOutput(false, "", result.message)
        val fileName = result.draftFile?.name ?: return BardoToolOutput(
            false, "", "El builder no devolvió un archivo de borrador."
        )
        val permissions = result.requiredPermissions.map { it.name }.sorted().joinToString()
        return BardoToolOutput(
            true,
            "BORRADOR SKILL CREADO: " + fileName +
                "\nSkill: " + result.skillId + "@" + result.version +
                "\nAcciones: " + result.actions.sorted().joinToString() +
                "\nPermisos requeridos: " + permissions +
                "\nSHA-256 del paquete: " + result.sha256 +
                "\nNo se ha instalado, habilitado ni ejecutado. Revisa el borrador en BARDO Developer."
        )
    }

    companion object {
        const val TOOL_ID = "skills.create_draft"
    }
}
