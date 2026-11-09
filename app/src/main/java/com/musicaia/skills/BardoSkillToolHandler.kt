package com.musicaia.skills

import com.musicaia.herramientas.BardoToolDescriptor
import com.musicaia.herramientas.BardoToolHandler
import com.musicaia.herramientas.BardoToolOutput
import com.musicaia.seguridad.BardoPermissionScope
import org.json.JSONObject

/**
 * Herramienta de puente para el planificador BARDO.
 * Solo puede invocar una acción declarada en una skill instalada/habilitada.
 * La acción real vuelve a pasar por el ToolExecutor base y su política global.
 */
class BardoSkillToolHandler(
    private val skillExecutor: BardoSkillExecutor
) : BardoToolHandler {
    override val descriptor = BardoToolDescriptor(
        id = TOOL_ID,
        description = "Ejecuta una acción tipada de una skill aprobada y habilitada.",
        requiredScope = BardoPermissionScope.READ_BARDO_DATA,
        maxArguments = 4,
        maxValueChars = 8_000
    )

    override fun execute(arguments: Map<String, String>): BardoToolOutput {
        val skillId = arguments["skill_id"].orEmpty()
        val version = arguments["version"].orEmpty()
        val actionId = arguments["action_id"].orEmpty()
        val rawArgs = arguments["action_arguments_json"] ?: "{}"
        if (skillId.isBlank() || version.isBlank() || actionId.isBlank()) {
            return BardoToolOutput(false, "", "Faltan skill_id, version o action_id.")
        }
        if (rawArgs.length > MAX_ARGUMENT_JSON_CHARS) {
            return BardoToolOutput(false, "", "Los argumentos JSON superan el límite.")
        }

        val parsed = try {
            JSONObject(rawArgs)
        } catch (error: Exception) {
            return BardoToolOutput(false, "", "action_arguments_json no contiene un objeto JSON válido.")
        }
        if (parsed.length() > MAX_ACTION_ARGUMENTS) {
            return BardoToolOutput(false, "", "Demasiados argumentos para una acción de skill.")
        }
        val values = linkedMapOf<String, String>()
        val keys = parsed.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!key.matches(Regex("^[A-Za-z][A-Za-z0-9_.-]{0,63}$"))) {
                return BardoToolOutput(false, "", "Nombre de argumento no válido: " + key)
            }
            val value = parsed.opt(key)
            if (value !is String || value.length > MAX_ARGUMENT_VALUE_CHARS) {
                return BardoToolOutput(false, "", "Todos los argumentos de la skill deben ser cadenas acotadas.")
            }
            values[key] = value
        }

        val report = skillExecutor.execute(
            skillId,
            version,
            listOf(BardoSkillActionInvocation(actionId, values))
        )
        val output = report.steps.joinToString("\n") { step ->
            "Acción=" + step.toolId + "; resultado=" +
                (if (step.succeeded) "correcto" else "fallido") + "\n" +
                step.output + (step.error?.let { "\nError: " + it } ?: "")
        }
        val success = report.status == BardoSkillExecutionStatus.COMPLETED
        return BardoToolOutput(
            succeeded = success,
            content = (report.summary + if (output.isBlank()) "" else "\n" + output)
                .take(MAX_OUTPUT_CHARS),
            error = if (success) null else report.summary
        )
    }

    companion object {
        const val TOOL_ID = "skill.run_action"
        const val MAX_ARGUMENT_JSON_CHARS = 8_000
        const val MAX_ACTION_ARGUMENTS = 16
        const val MAX_ARGUMENT_VALUE_CHARS = 2_000
        const val MAX_OUTPUT_CHARS = 12_000
    }
}
