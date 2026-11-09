package com.musicaia.skills

import com.musicaia.herramientas.BardoToolDescriptor
import com.musicaia.herramientas.BardoToolHandler
import com.musicaia.herramientas.BardoToolOutput
import com.musicaia.seguridad.BardoPermissionScope
import org.json.JSONObject

/** Puente para que el planificador invoque un workflow declarativo instalado. */
class BardoSkillWorkflowToolHandler(
    private val executor: BardoSkillWorkflowExecutor
) : BardoToolHandler {
    override val descriptor = BardoToolDescriptor(
        id = TOOL_ID,
        description = "Ejecuta el workflow JSON de una skill instalada y habilitada. Argumentos: skill_id, version e inputs_json (objeto JSON de cadenas).",
        requiredScope = BardoPermissionScope.READ_BARDO_DATA,
        maxArguments = 3,
        maxValueChars = 8_000
    )

    override fun execute(arguments: Map<String, String>): BardoToolOutput {
        val id = arguments["skill_id"].orEmpty()
        val version = arguments["version"].orEmpty()
        val rawInputs = arguments["inputs_json"] ?: "{}"
        if (id.isBlank() || version.isBlank()) {
            return BardoToolOutput(false, "", "Faltan skill_id o version.")
        }
        if (rawInputs.length > MAX_INPUT_JSON_CHARS) {
            return BardoToolOutput(false, "", "inputs_json supera el límite.")
        }
        val parsed = try {
            JSONObject(rawInputs)
        } catch (_: Exception) {
            return BardoToolOutput(false, "", "inputs_json no es un objeto JSON válido.")
        }
        if (parsed.length() > BardoSkillWorkflowExecutor.MAX_INPUTS) {
            return BardoToolOutput(false, "", "Demasiados parámetros de entrada.")
        }
        val inputs = linkedMapOf<String, String>()
        val keys = parsed.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = parsed.opt(key)
            if (!key.matches(Regex("^[A-Za-z][A-Za-z0-9_.-]{0,63}$")) ||
                value !is String || value.length > BardoSkillWorkflowExecutor.MAX_INPUT_CHARS
            ) {
                return BardoToolOutput(false, "", "Los parámetros deben ser cadenas con nombres válidos y límites.")
            }
            inputs[key] = value
        }

        val report = executor.execute(id, version, inputs)
        val output = report.outputs.joinToString("\n").take(MAX_OUTPUT_CHARS)
        return BardoToolOutput(
            succeeded = report.succeeded,
            content = report.summary + if (output.isBlank()) "" else "\n" + output,
            error = report.error
        )
    }

    companion object {
        const val TOOL_ID = "skill.run_workflow"
        const val MAX_INPUT_JSON_CHARS = 8_000
        const val MAX_OUTPUT_CHARS = 12_000
    }
}
