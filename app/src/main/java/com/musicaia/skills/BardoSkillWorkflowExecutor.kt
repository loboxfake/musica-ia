package com.musicaia.skills

import com.musicaia.herramientas.BardoToolExecutor
import com.musicaia.herramientas.BardoToolOutput
import com.musicaia.herramientas.BardoToolRequest
import com.musicaia.seguridad.BardoPrincipal
import org.json.JSONObject
import java.io.File

data class BardoSkillWorkflowStep(
    val actionId: String,
    val arguments: Map<String, String>
)

data class BardoSkillWorkflowReport(
    val skillId: String,
    val version: String,
    val succeeded: Boolean,
    val completedSteps: Int,
    val outputs: List<String>,
    val error: String?,
    val summary: String,
    val executionEligible: Boolean = false,
    val ineligibleActionIds: List<String> = emptyList()
)

/** Resultado de una inspección estática. No ejecuta acciones del catálogo. */
data class BardoSkillWorkflowInspection(
    val skillId: String,
    val version: String,
    val succeeded: Boolean,
    val stepCount: Int,
    val actionIds: List<String>,
    val requiredInputs: Set<String>,
    val packageSha256: String?,
    val error: String?,
    val summary: String
)

/**
 * Interpreta paquetes JSON declarativos como secuencias de acciones ya registradas.
 * No carga scripts, bytecode, bibliotecas ni comandos del paquete.
 */
class BardoSkillWorkflowExecutor(
    private val manager: BardoSkillManager,
    private val tools: BardoToolExecutor,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    /**
     * Valida el paquete habilitado y describe lo que haría, sin invocar ninguna herramienta.
     * findEnabled vuelve a comprobar el tamaño y SHA-256 del paquete persistido.
     */
    fun inspect(skillId: String, version: String): BardoSkillWorkflowInspection {
        val enabled = try {
            manager.findEnabled(skillId, version)
        } catch (error: Exception) {
            return inspectionFailure(
                skillId, version,
                error.message ?: "No se pudo recuperar la skill."
            )
        } ?: return inspectionFailure(
            skillId, version,
            "La skill no está habilitada o su integridad no es válida."
        )

        val packageFile = enabled.installedPath?.let(::File)
            ?: return inspectionFailure(skillId, version, "La skill no tiene paquete instalado.")

        val steps = try {
            parseWorkflow(packageFile, enabled.manifest.actions)
        } catch (error: Exception) {
            return inspectionFailure(
                skillId, version,
                "El paquete no supera la inspección: " +
                    (error.message ?: error.javaClass.simpleName).take(MAX_ERROR_CHARS)
            )
        }

        val requiredInputs = try {
            collectRequiredInputs(steps)
        } catch (error: Exception) {
            return inspectionFailure(
                skillId, version,
                "Las entradas del workflow no son válidas: " +
                    (error.message ?: error.javaClass.simpleName).take(MAX_ERROR_CHARS)
            )
        }

        val ineligibleActions = steps.map { it.actionId }.distinct()
            .filterNot { it in tools.registeredSkillToolIds() }
            .sorted()
        val summary = buildString {
            append("Workflow estructuralmente válido: ").append(steps.size)
                .append(" pasos; acciones=").append(steps.map { it.actionId }.distinct().joinToString())
            append("; entradas requeridas=").append(requiredInputs.sorted().joinToString())
            if (ineligibleActions.isNotEmpty()) {
                append(". No ejecutable por BARDO: faltan smoke-tests de confianza para ")
                    .append(ineligibleActions.joinToString())
            } else {
                append(". Todas las acciones tienen smoke-test de confianza.")
            }
            append(" No se ejecutó ninguna acción.")
        }
        return BardoSkillWorkflowInspection(
            skillId = skillId,
            version = version,
            succeeded = true,
            stepCount = steps.size,
            actionIds = steps.map { it.actionId }.distinct(),
            requiredInputs = requiredInputs,
            packageSha256 = enabled.manifest.packageSha256,
            error = null,
            summary = summary,
            executionEligible = ineligibleActions.isEmpty(),
            ineligibleActionIds = ineligibleActions
        )
    }

    fun execute(
        skillId: String,
        version: String,
        inputs: Map<String, String> = emptyMap()
    ): BardoSkillWorkflowReport {
        if (inputs.size > MAX_INPUTS || inputs.any { (key, value) ->
                !INPUT_KEY.matches(key) || value.length > MAX_INPUT_CHARS
            }
        ) return failure(skillId, version, 0, emptyList(), "Los datos de entrada superan el esquema permitido.")

        val enabled = manager.findEnabled(skillId, version)
            ?: return failure(skillId, version, 0, emptyList(), "La skill no está habilitada o su integridad no es válida.")
        val packageFile = enabled.installedPath?.let(::File)
            ?: return failure(skillId, version, 0, emptyList(), "La skill no tiene paquete instalado.")

        val steps = try {
            parseWorkflow(packageFile, enabled.manifest.actions)
        } catch (error: Exception) {
            return failure(
                skillId, version, 0, emptyList(),
                "El paquete declarativo no supera la validación: " +
                    (error.message ?: error.javaClass.simpleName).take(MAX_ERROR_CHARS)
            )
        }
        val requiredInputs = try {
            collectRequiredInputs(steps)
        } catch (error: Exception) {
            return failure(
                skillId, version, 0, emptyList(),
                "Las entradas del workflow no son válidas: " +
                    (error.message ?: error.javaClass.simpleName).take(MAX_ERROR_CHARS)
            )
        }
        if (inputs.keys != requiredInputs) {
            val missing = requiredInputs - inputs.keys
            val unused = inputs.keys - requiredInputs
            return failure(
                skillId, version, 0, emptyList(),
                "Los parámetros no coinciden con el workflow. Faltan=" +
                    missing.sorted().joinToString() + "; no utilizados=" + unused.sorted().joinToString()
            )
        }

        val ineligibleActions = steps.map { it.actionId }.toSet() - tools.registeredSkillToolIds()
        if (ineligibleActions.isNotEmpty()) {
            return failure(
                skillId, version, 0, emptyList(),
                "Ejecución denegada: las acciones no tienen smoke-test de confianza escrito en el APK: " +
                    ineligibleActions.sorted().joinToString()
            )
        }

        val outputs = mutableListOf<String>()
        var totalOutput = 0
        for ((index, step) in steps.withIndex()) {
            // Recalcular SHA-256 antes de cada acción para cerrar la ventana de sustitución del paquete.
            val current = manager.findEnabled(skillId, version)
                ?: return failure(
                    skillId, version, index, outputs,
                    "La skill perdió su estado habilitado o cambió durante la ejecución."
                )
            if (current.manifest.packageSha256 != enabled.manifest.packageSha256) {
                return failure(skillId, version, index, outputs, "Cambió la identidad del paquete durante la ejecución.")
            }

            val resolved = try {
                step.arguments.mapValues { (_, value) -> resolveInput(value, inputs) }
            } catch (error: Exception) {
                return failure(
                    skillId, version, index, outputs,
                    error.message ?: "No se pudieron resolver los parámetros de la skill."
                )
            }

            val result: BardoToolOutput = tools.execute(
                BardoToolRequest(
                    toolId = step.actionId,
                    arguments = resolved,
                    principal = BardoPrincipal.BARDO
                ),
                nowMillis()
            )
            if (!result.succeeded) {
                return failure(
                    skillId, version, index + 1, outputs,
                    "Falló la acción " + step.actionId + ": " +
                        (result.error ?: "permiso denegado o resultado no satisfactorio")
                )
            }
            val output = result.content.take(MAX_STEP_OUTPUT_CHARS)
            totalOutput += output.length
            if (totalOutput > MAX_TOTAL_OUTPUT_CHARS) {
                return failure(skillId, version, index + 1, outputs, "La salida total superó el límite permitido.")
            }
            outputs += output
        }

        return BardoSkillWorkflowReport(
            skillId, version, true, steps.size, outputs, null,
            "Workflow declarativo completado: " + steps.size + " acciones tipadas ejecutadas."
        )
    }

    private fun parseWorkflow(file: File, declaredActions: Set<String>): List<BardoSkillWorkflowStep> {
        require(file.isFile && file.length() in 1..MAX_PACKAGE_BYTES) {
            "El paquete está vacío o supera el tamaño permitido."
        }
        val text = file.readText(Charsets.UTF_8)
        require(text.length <= MAX_PACKAGE_BYTES.toInt()) { "El paquete supera el límite de texto." }
        val root = JSONObject(text)
        require(root.length() == 2 && root.has("schema_version") && root.has("steps")) {
            "El paquete solo admite schema_version y steps."
        }
        require(root.optInt("schema_version", -1) == SCHEMA_VERSION) {
            "Versión de workflow no compatible."
        }
        val array = root.optJSONArray("steps")
            ?: throw IllegalArgumentException("Falta el array steps.")
        require(array.length() in 1..MAX_STEPS) { "El workflow debe contener entre 1 y " + MAX_STEPS + " pasos." }

        val result = mutableListOf<BardoSkillWorkflowStep>()
        // La inspección estructural puede describir acciones no elegibles.
        // execute() aplica la allowlist completa antes de ejecutar el primer paso.
        val registered = tools.registeredTools().associateBy { it.id }
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index)
                ?: throw IllegalArgumentException("El paso " + index + " no es un objeto.")
            require(row.length() == 2 && row.has("action_id") && row.has("arguments")) {
                "El paso " + index + " debe contener solo action_id y arguments."
            }
            val actionId = row.optString("action_id")
            require(actionId in declaredActions) { "Acción no declarada en el manifiesto: " + actionId }
            require(actionId in registered) { "Acción no registrada en BARDO: " + actionId }
            val args = row.optJSONObject("arguments")
                ?: throw IllegalArgumentException("Los argumentos del paso " + index + " deben ser un objeto.")
            require(args.length() <= registered.getValue(actionId).maxArguments) {
                "Demasiados argumentos para " + actionId + "."
            }
            val values = linkedMapOf<String, String>()
            val keys = args.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                require(ARGUMENT_KEY.matches(key)) { "Nombre de argumento no válido: " + key }
                val value = args.opt(key)
                require(value is String && value.length <= MAX_INPUT_CHARS) {
                    "Los argumentos del workflow deben ser cadenas acotadas."
                }
                values[key] = value
            }
            result += BardoSkillWorkflowStep(actionId, values)
        }
        require(result.map { it.actionId }.toSet() == declaredActions) {
            "Las acciones del manifiesto y del workflow no coinciden."
        }
        return result
    }

    /**
     * Prevalida los marcadores de todos los pasos antes de ejecutar el primero.
     * Así, un error en un paso posterior no deja acciones anteriores ejecutadas parcialmente.
     */
    private fun collectRequiredInputs(steps: List<BardoSkillWorkflowStep>): Set<String> {
        val required = linkedSetOf<String>()
        for (step in steps) {
            for (value in step.arguments.values) {
                val matches = INPUT_TOKEN.findAll(value).toList()
                if (matches.isEmpty()) {
                    require(!value.contains("{{") && !value.contains("}}")) {
                        "Marcador de entrada mal formado."
                    }
                } else {
                    require(matches.size == 1 && matches.single().value == value) {
                        "Solo se permite un marcador de entrada completo por valor."
                    }
                    required += matches.single().groupValues[1]
                }
            }
        }
        require(required.size <= MAX_INPUTS) {
            "El workflow declara demasiadas entradas."
        }
        return required
    }

    private fun inspectionFailure(
        skillId: String,
        version: String,
        error: String
    ) = BardoSkillWorkflowInspection(
        skillId = skillId,
        version = version,
        succeeded = false,
        stepCount = 0,
        actionIds = emptyList(),
        requiredInputs = emptySet(),
        packageSha256 = null,
        error = error.take(MAX_ERROR_CHARS),
        summary = "Inspección rechazada; no se ejecutó ninguna acción."
    )

    private fun resolveInput(value: String, inputs: Map<String, String>): String {
        val matcher = INPUT_TOKEN.findAll(value).toList()
        if (matcher.isEmpty()) {
            require(!value.contains("{{") && !value.contains("}}")) {
                "Marcador de entrada mal formado."
            }
            return value
        }
        require(matcher.size == 1 && matcher.single().value == value) {
            "Solo se permite un marcador de entrada completo por valor."
        }
        val key = matcher.single().groupValues[1]
        return inputs[key] ?: throw IllegalArgumentException("Falta el parámetro de entrada: " + key)
    }

    private fun failure(
        id: String,
        version: String,
        completed: Int,
        outputs: List<String>,
        error: String
    ) = BardoSkillWorkflowReport(
        id, version, false, completed, outputs.toList(), error.take(MAX_ERROR_CHARS),
        "Workflow detenido; no se ejecutaron más acciones."
    )

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_STEPS = 20
        const val MAX_INPUTS = 16
        const val MAX_INPUT_CHARS = 2_000
        const val MAX_STEP_OUTPUT_CHARS = 8_000
        const val MAX_TOTAL_OUTPUT_CHARS = 40_000
        const val MAX_ERROR_CHARS = 1_200
        const val MAX_PACKAGE_BYTES = 10L * 1024L * 1024L
        private val INPUT_KEY = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,63}$")
        private val ARGUMENT_KEY = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,63}$")
        private val INPUT_TOKEN = Regex("^\\{\\{input[.:]([A-Za-z][A-Za-z0-9_.-]{0,63})}}$")
    }
}
