package com.musicaia.skills

import android.content.Context
import com.musicaia.herramientas.BardoCoreToolCatalog
import com.musicaia.herramientas.BardoToolExecutor
import com.musicaia.herramientas.BardoToolDescriptor
import com.musicaia.herramientas.BardoToolHandler
import com.musicaia.herramientas.BardoToolOutput
import com.musicaia.seguridad.BardoPermissionScope
import java.io.File
import java.security.MessageDigest

/**
 * Smoke-test de confianza para skills declarativas.
 *
 * Solo invoca pruebas embebidas en handlers Kotlin revisados dentro del APK.
 * Los bytes de un paquete importado jamás se cargan ni ejecutan.
 */
class BardoCoreSkillSmokeTestRunner(
    private val tools: BardoToolExecutor
) : BardoSkillSmokeTestRunner {

    override fun run(
        manifest: BardoSkillManifest,
        requiredPermissions: Set<BardoSkillPermission>
    ): BardoSkillSmokeTestReport {
        if (!manifest.permissions.containsAll(requiredPermissions)) {
            return failed("El manifiesto no declara todos los permisos requeridos.")
        }

        val skillEligibleIds = tools.registeredSkillToolIds()
        val registered = tools.registeredTools()
            .filter { it.id in skillEligibleIds }
            .associateBy { it.id }
        val unknown = manifest.actions - registered.keys
        if (unknown.isNotEmpty()) {
            return failed("Acciones no registradas en el catálogo seguro: " + unknown.sorted().joinToString())
        }

        val evidence = mutableListOf<String>()
        for (actionId in manifest.actions.sorted()) {
            val descriptor = registered[actionId]
                ?: return failed("La acción desapareció del catálogo durante la prueba: " + actionId)
            val result = tools.runTrustedSmokeTest(actionId)
            if (!result.succeeded) {
                return BardoSkillSmokeTestReport(
                    passed = false,
                    verifierId = VERIFIER_ID,
                    evidenceRefs = evidence,
                    note = "Falló el smoke-test de " + actionId + ": " +
                        (result.error ?: "resultado no satisfactorio").take(1200)
                )
            }

            val digest = sha256(
                (descriptor.id + "\n" + descriptor.requiredScope.name + "\n" + result.content)
                    .toByteArray(Charsets.UTF_8)
            )
            evidence += "bardo://skill-smoke/${manifest.id}/${manifest.version}/" + descriptor.id + "/" + digest
        }

        if (evidence.isEmpty()) return failed("La skill no contiene acciones probables.")
        return BardoSkillSmokeTestReport(
            passed = true,
            verifierId = VERIFIER_ID,
            evidenceRefs = evidence,
            note = "Se ejecutaron los smoke-tests escritos en el APK para todas las acciones declaradas. " +
                "No se ejecutó ni cargó código del paquete."
        )
    }

    private fun failed(note: String) = BardoSkillSmokeTestReport(
        passed = false,
        verifierId = VERIFIER_ID,
        evidenceRefs = emptyList(),
        note = note.take(2000)
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val VERIFIER_ID = "bardo-core-tool-smoke-v1"
    }
}

/**
 * Informe de mantenimiento consumible por el planificador. Es estrictamente
 * de lectura: nunca deshabilita ni elimina skills por decisión del modelo.
 */
class BardoSkillMaintenanceToolHandler(
    private val manager: BardoSkillManager,
    private val registeredActionIds: () -> Set<String>
) : BardoToolHandler {
    override val descriptor = BardoToolDescriptor(
        id = TOOL_ID,
        description = "Analiza las skills instaladas y propone conservar, revisar, volver a probar, deshabilitar o retirar versiones obsoletas. Solo informa; no modifica el registro.",
        requiredScope = BardoPermissionScope.READ_BARDO_DATA,
        maxArguments = 0
    )

    override fun execute(arguments: Map<String, String>): BardoToolOutput {
        if (arguments.isNotEmpty()) {
            return BardoToolOutput(false, "", "El informe de mantenimiento no admite argumentos.")
        }
        val records = manager.list()
        if (records.isEmpty()) {
            return BardoToolOutput(true, "No hay skills instaladas. No se ha propuesto ninguna modificación.")
        }
        val findings = BardoSkillMaintenancePlanner.evaluate(records, registeredActionIds())
        val body = findings.joinToString("\n") { finding ->
            val reasons = if (finding.reasons.isEmpty()) "sin incidencias detectadas"
                else finding.reasons.joinToString("; ")
            finding.skillId + "@" + finding.version + " → " + finding.action.name +
                " · " + reasons
        }
        return BardoToolOutput(
            true,
            "INFORME DE MANTENIMIENTO DE SKILLS (solo recomendaciones)\n" +
                "Versiones revisadas: " + records.size + "\n" + body +
                "\n\nNinguna skill ha sido modificada ni eliminada."
        )
    }

    companion object {
        const val TOOL_ID = "skills.maintenance_report"
    }
}

data class BardoSkillRuntime(
    val manager: BardoSkillManager,
    val tools: BardoToolExecutor,
    val executor: BardoSkillExecutor,
    val workflowExecutor: BardoSkillWorkflowExecutor,
    val registeredActionIds: Set<String>
)

/**
 * Composition root local. La lista de acciones se toma del ToolExecutor real,
 * no de la lista declarativa incluida por el paquete.
 */
object BardoSkillRuntimeFactory {
    fun create(context: Context): BardoSkillRuntime {
        val app = context.applicationContext
        val coreHandlers = BardoCoreToolCatalog.create(app)
        val coreTools = BardoToolExecutor(coreHandlers)
        val registeredActions = coreTools.registeredSkillToolIds()
        val manager = BardoSkillManager(
            rootDirectory = File(app.filesDir, "bardo_skills"),
            registeredActionIds = { registeredActions },
            smokeTestRunner = BardoCoreSkillSmokeTestRunner(coreTools)
        )
        val skillExecutor = BardoSkillExecutor(manager, coreTools)
        val workflowExecutor = BardoSkillWorkflowExecutor(manager, coreTools)
        // Bridges are excluded from registeredActions so a downloaded skill cannot
        // recursively invoke another skill bridge.
        val plannerTools = BardoToolExecutor(
            coreHandlers +
                BardoSkillToolHandler(skillExecutor) +
                BardoSkillWorkflowToolHandler(workflowExecutor) +
                BardoSkillMaintenanceToolHandler(manager) { registeredActions } +
                BardoSkillDraftToolHandler(
                    BardoSkillDraftBuilder(
                        File(app.filesDir, "bardo_skill_drafts"),
                        registeredActionIds = { registeredActions }
                    )
                )
        )
        return BardoSkillRuntime(
            manager = manager,
            tools = plannerTools,
            executor = skillExecutor,
            workflowExecutor = workflowExecutor,
            registeredActionIds = registeredActions
        )
    }
}
