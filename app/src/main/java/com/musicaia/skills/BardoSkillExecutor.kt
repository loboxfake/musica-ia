package com.musicaia.skills

import com.musicaia.herramientas.BardoToolExecutor
import com.musicaia.herramientas.BardoToolOutput
import com.musicaia.herramientas.BardoToolRequest
import com.musicaia.seguridad.BardoPrincipal

enum class BardoSkillExecutionStatus { COMPLETED, REJECTED, FAILED }

data class BardoSkillActionInvocation(
    val toolId: String,
    val arguments: Map<String, String> = emptyMap()
)

data class BardoSkillStepResult(
    val toolId: String,
    val succeeded: Boolean,
    val output: String,
    val error: String?
)

data class BardoSkillExecutionReport(
    val skillId: String,
    val version: String,
    val status: BardoSkillExecutionStatus,
    val steps: List<BardoSkillStepResult>,
    val summary: String
)

/**
 * Ejecuta una skill como secuencia de herramientas ya registradas.
 * No interpreta scripts, comandos, expresiones ni código contenido en el paquete.
 */
class BardoSkillExecutor(
    private val manager: BardoSkillManager,
    private val tools: BardoToolExecutor,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    fun execute(
        skillId: String,
        version: String,
        invocations: List<BardoSkillActionInvocation>
    ): BardoSkillExecutionReport {
        val record = manager.findEnabled(skillId, version)
            ?: return report(
                skillId, version, BardoSkillExecutionStatus.REJECTED, emptyList(),
                "La skill no está habilitada o su paquete/evidencia no supera la verificación."
            )
        if (invocations.isEmpty() || invocations.size > MAX_STEPS) {
            return report(
                skillId, version, BardoSkillExecutionStatus.REJECTED, emptyList(),
                "La ejecución debe contener entre 1 y $MAX_STEPS acciones."
            )
        }

        val declaredActions = record.manifest.actions
        val skillEligibleIds = tools.verifiedSkillToolIds()
        val undeclared = invocations.map { it.toolId }.toSet() - declaredActions
        if (undeclared.isNotEmpty()) {
            return report(
                skillId, version, BardoSkillExecutionStatus.REJECTED, emptyList(),
                "La skill intentó usar acciones no declaradas: " + undeclared.sorted().joinToString()
            )
        }
        val missing = invocations.map { it.toolId }.toSet() - skillEligibleIds
        if (missing.isNotEmpty()) {
            return report(
                skillId, version, BardoSkillExecutionStatus.REJECTED, emptyList(),
                "Las acciones no superaron su smoke-test de confianza: " + missing.sorted().joinToString()
            )
        }

        val results = mutableListOf<BardoSkillStepResult>()
        var totalOutput = 0
        for (invocation in invocations) {
            val toolResult: BardoToolOutput = tools.execute(
                BardoToolRequest(
                    toolId = invocation.toolId,
                    arguments = invocation.arguments,
                    principal = BardoPrincipal.BARDO
                ),
                nowMillis()
            )
            val visibleOutput = toolResult.content.take(MAX_STEP_OUTPUT_CHARS)
            totalOutput += visibleOutput.length
            results += BardoSkillStepResult(
                toolId = invocation.toolId,
                succeeded = toolResult.succeeded,
                output = visibleOutput,
                error = toolResult.error?.take(MAX_ERROR_CHARS)
            )
            if (!toolResult.succeeded) {
                return report(
                    skillId, version, BardoSkillExecutionStatus.FAILED, results,
                    "La ejecución se detuvo porque una acción falló o no tenía permisos."
                )
            }
            if (totalOutput > MAX_TOTAL_OUTPUT_CHARS) {
                return report(
                    skillId, version, BardoSkillExecutionStatus.FAILED, results,
                    "La salida superó el límite de la ejecución."
                )
            }
        }
        return report(
            skillId, version, BardoSkillExecutionStatus.COMPLETED, results,
            "Todas las acciones tipadas declaradas por la skill finalizaron correctamente."
        )
    }

    private fun report(
        id: String,
        version: String,
        status: BardoSkillExecutionStatus,
        steps: List<BardoSkillStepResult>,
        summary: String
    ) = BardoSkillExecutionReport(id, version, status, steps, summary)

    companion object {
        const val MAX_STEPS = 20
        const val MAX_STEP_OUTPUT_CHARS = 8_000
        const val MAX_ERROR_CHARS = 1_200
        const val MAX_TOTAL_OUTPUT_CHARS = 40_000
    }
}
