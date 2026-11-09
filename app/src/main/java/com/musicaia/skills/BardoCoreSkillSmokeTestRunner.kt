package com.musicaia.skills

import android.content.Context
import com.musicaia.herramientas.BardoCoreToolCatalog
import com.musicaia.herramientas.BardoToolExecutor
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

        val registered = tools.registeredTools().associateBy { it.id }
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

data class BardoSkillRuntime(
    val manager: BardoSkillManager,
    val tools: BardoToolExecutor
)

/**
 * Composition root local. La lista de acciones se toma del ToolExecutor real,
 * no de la lista declarativa incluida por el paquete.
 */
object BardoSkillRuntimeFactory {
    fun create(context: Context): BardoSkillRuntime {
        val app = context.applicationContext
        val tools = BardoToolExecutor(BardoCoreToolCatalog.create(app))
        val registeredActions = tools.registeredTools().map { it.id }.toSet()
        val manager = BardoSkillManager(
            rootDirectory = File(app.filesDir, "bardo_skills"),
            registeredActionIds = { registeredActions },
            smokeTestRunner = BardoCoreSkillSmokeTestRunner(tools)
        )
        return BardoSkillRuntime(manager, tools)
    }
}
