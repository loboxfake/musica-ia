package com.musicaia.autonomia

import android.content.Context
import com.musicaia.herramientas.BardoToolExecutor
import com.musicaia.herramientas.BardoToolRequest
import com.musicaia.modelos.BardoModelRegistry
import com.musicaia.modelos.BardoModelRequest
import com.musicaia.seguridad.BardoPrincipal
import com.musicaia.skills.BardoSkillManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class BardoTaskStep(
    val toolId: String,
    val arguments: Map<String, String>,
    val purpose: String,
    val expectedOutputContains: String? = null
)

data class BardoActionPlan(val steps: List<BardoTaskStep>, val correctionBudget: Int = 1)

/**
 * Presupuesto de una ejecución concreta. Los valores se validan antes de
 * ejecutar herramientas y pueden ajustarse por tarea sin alterar el motor.
 */
data class BardoAutonomyBudget(
    val maxTaskMillis: Long = 180_000L,
    val maxSteps: Int = 12,
    val maxCorrections: Int = 2
) {
    init {
        require(maxTaskMillis in 10_000L..900_000L) {
            "El tiempo de tarea debe estar entre 10 segundos y 15 minutos."
        }
        require(maxSteps in 1..BardoAutonomyEngine.MAX_STEPS)
        require(maxCorrections in 0..BardoAutonomyEngine.MAX_CORRECTIONS)
    }
}

data class BardoTaskObservation(
    val toolId: String,
    val succeeded: Boolean,
    val output: String,
    val error: String?,
    val elapsedMillis: Long
)

fun interface BardoTaskPlanner {
    fun createPlan(
        goal: String,
        previousObservations: List<BardoTaskObservation>,
        correctionReason: String?
    ): BardoActionPlan
}

data class BardoTaskStepVerification(
    val accepted: Boolean,
    val reason: String? = null
)

/**
 * Verifica la salida de una herramienta fuera del modelo que propuso el plan.
 * El verificador predeterminado exige ejecución satisfactoria, salida no vacía
 * y, cuando se declara, el marcador objetivo exacto.
 */
fun interface BardoTaskStepVerifier {
    fun verify(step: BardoTaskStep, output: com.musicaia.herramientas.BardoToolOutput): BardoTaskStepVerification
}

/** Comprueba condiciones externas justo antes de cada herramienta. Devuelve un motivo de bloqueo o null. */
fun interface BardoTaskExecutionGuard {
    fun blockingReason(): String?
}

object BardoDeterministicTaskStepVerifier : BardoTaskStepVerifier {
    override fun verify(
        step: BardoTaskStep,
        output: com.musicaia.herramientas.BardoToolOutput
    ): BardoTaskStepVerification {
        if (!output.succeeded) {
            return BardoTaskStepVerification(false, output.error ?: "La herramienta no completó la acción.")
        }
        if (output.content.isBlank()) {
            return BardoTaskStepVerification(false, "La herramienta informó éxito, pero no aportó evidencia de salida.")
        }
        val marker = step.expectedOutputContains
        if (!marker.isNullOrBlank() && !output.content.contains(marker, ignoreCase = true)) {
            return BardoTaskStepVerification(false, "La salida no contiene el criterio objetivo declarado.")
        }
        return BardoTaskStepVerification(true)
    }
}

enum class BardoAutonomyStatus { COMPLETED, PARTIAL, BLOCKED, FAILED }

/** Estado persistido sin convertir bloqueos o resultados parciales en éxitos. */
fun BardoAutonomyStatus.toActivityState(): String = when (this) {
    BardoAutonomyStatus.COMPLETED -> "completed"
    BardoAutonomyStatus.PARTIAL -> "partial"
    BardoAutonomyStatus.BLOCKED -> "blocked"
    BardoAutonomyStatus.FAILED -> "failed"
}

data class BardoAutonomyResult(
    val taskId: String,
    val status: BardoAutonomyStatus,
    val observations: List<BardoTaskObservation>,
    val correctionsUsed: Int,
    val elapsedMillis: Long,
    val summary: String
)

/**
 * Bucle local planificar→actuar→observar→evaluar→corregir.
 * Cada acción pasa por el catálogo cerrado y la política de autoridad.
 */
class BardoAutonomyEngine(
    private val planner: BardoTaskPlanner,
    private val tools: BardoToolExecutor,
    private val journal: BardoTaskJournal,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val maxTaskMillis: Long = 180_000L,
    private val stepVerifier: BardoTaskStepVerifier = BardoDeterministicTaskStepVerifier,
    private val budget: BardoAutonomyBudget = BardoAutonomyBudget(maxTaskMillis = maxTaskMillis),
    private val executionGuard: BardoTaskExecutionGuard = BardoTaskExecutionGuard { null }
) {
    fun run(goal: String): BardoAutonomyResult {
        require(goal.isNotBlank() && goal.length <= 4000) {
            "El objetivo está vacío o supera 4.000 caracteres."
        }
        val taskId = UUID.randomUUID().toString()
        val started = nowMillis()
        val observations = mutableListOf<BardoTaskObservation>()

        var plan = try {
            planner.createPlan(goal, emptyList(), null).also(::validatePlan)
        } catch (error: Exception) {
            journal.record(taskId, "plan_rejected", null, false, error.message.orEmpty(), nowMillis())
            return result(taskId, BardoAutonomyStatus.BLOCKED, observations, 0, started,
                "No se pudo obtener un plan seguro: " + error.message)
        }

        val correctionBudget = minOf(plan.correctionBudget, budget.maxCorrections)
        var corrections = 0
        var pending = ArrayDeque(plan.steps)
        var lastStepFailed = false
        journal.record(taskId, "plan_created", null, true, "Pasos=${plan.steps.size}", nowMillis())

        while (pending.isNotEmpty()) {
            if (observations.size >= budget.maxSteps) {
                journal.record(
                    taskId, "step_limit", null, false,
                    "Se alcanzó el máximo total de ${budget.maxSteps} pasos de esta tarea.",
                    nowMillis()
                )
                return result(
                    taskId, BardoAutonomyStatus.PARTIAL, observations, corrections, started,
                    "Tarea detenida por el límite total de pasos; se conserva el trabajo parcial."
                )
            }
            if (Thread.currentThread().isInterrupted) {
                journal.record(taskId, "cancelled", null, false, "Interrupción solicitada.", nowMillis())
                return result(taskId, BardoAutonomyStatus.PARTIAL, observations, corrections, started,
                    "Tarea interrumpida; se conserva el trabajo parcial.")
            }
            if (nowMillis() - started > budget.maxTaskMillis) {
                journal.record(taskId, "time_limit", null, false, "Se alcanzó el límite temporal.", nowMillis())
                return result(taskId, BardoAutonomyStatus.PARTIAL, observations, corrections, started,
                    "Tarea detenida por límite de tiempo.")
            }

            val resourceBlock = try {
                executionGuard.blockingReason()
            } catch (error: Exception) {
                "No se pudieron comprobar los recursos antes del paso: " +
                    (error.message ?: error.javaClass.simpleName)
            }
            if (resourceBlock != null) {
                val blockedStatus = if (observations.isEmpty()) {
                    BardoAutonomyStatus.BLOCKED
                } else {
                    BardoAutonomyStatus.PARTIAL
                }
                journal.record(
                    taskId,
                    "resource_blocked",
                    null,
                    false,
                    resourceBlock.take(1000),
                    nowMillis()
                )
                return result(
                    taskId,
                    blockedStatus,
                    observations,
                    corrections,
                    started,
                    "Tarea detenida antes de ejecutar la siguiente herramienta: " +
                        resourceBlock.take(700) +
                        if (observations.isEmpty()) "" else " Se conserva el trabajo parcial."
                )
            }

            val step = pending.removeFirst()
            val stepStarted = nowMillis()
            val toolResult = tools.execute(
                BardoToolRequest(step.toolId, step.arguments, BardoPrincipal.BARDO),
                nowMillis()
            )
            val elapsed = (nowMillis() - stepStarted).coerceAtLeast(0L)
            val verification = stepVerifier.verify(step, toolResult)
            val succeeded = verification.accepted
            val reason = verification.reason
            observations += BardoTaskObservation(
                step.toolId, succeeded, toolResult.content.take(MAX_OBSERVATION_CHARS), reason, elapsed
            )
            journal.record(
                taskId,
                "step_verified",
                step.toolId,
                succeeded,
                (reason ?: "Salida no vacía y criterio objetivo satisfecho") +
                    "; duración=${elapsed}ms",
                nowMillis()
            )

            lastStepFailed = !succeeded
            if (succeeded) continue

            if (corrections >= correctionBudget) break
            corrections++
            val corrected = try {
                planner.createPlan(goal, observations.toList(), reason).also(::validatePlan)
            } catch (error: Exception) {
                journal.record(taskId, "correction_rejected", null, false, error.message.orEmpty(), nowMillis())
                break
            }
            pending = ArrayDeque(corrected.steps)
            journal.record(
                taskId, "correction_created", null, true,
                "Corrección=$corrections; pasos=${pending.size}", nowMillis()
            )
        }

        val status = when {
            pending.isEmpty() && observations.isNotEmpty() && !lastStepFailed ->
                BardoAutonomyStatus.COMPLETED
            observations.isNotEmpty() -> BardoAutonomyStatus.PARTIAL
            else -> BardoAutonomyStatus.FAILED
        }
        val summary = when (status) {
            BardoAutonomyStatus.COMPLETED -> "Todos los pasos y criterios objetivos han pasado."
            BardoAutonomyStatus.PARTIAL -> "La tarea terminó parcialmente; se conservan observaciones y errores."
            BardoAutonomyStatus.FAILED -> "La tarea falló antes de producir un resultado verificable."
            BardoAutonomyStatus.BLOCKED -> "La tarea se bloqueó por política o falta de herramientas."
        }
        journal.record(taskId, "task_finished", null, status == BardoAutonomyStatus.COMPLETED, summary, nowMillis())
        return result(taskId, status, observations, corrections, started, summary)
    }

    private fun validatePlan(plan: BardoActionPlan) {
        require(plan.steps.isNotEmpty() && plan.steps.size <= minOf(MAX_STEPS, budget.maxSteps)) {
            "El plan debe contener entre 1 y $MAX_STEPS pasos."
        }
        require(plan.correctionBudget in 0..MAX_CORRECTIONS) {
            "El presupuesto de corrección debe estar entre 0 y $MAX_CORRECTIONS."
        }
        plan.steps.forEach { step ->
            require(step.toolId.matches(Regex("^[a-z][a-z0-9._-]{2,63}$"))) {
                "ID de herramienta no válido."
            }
            require(step.purpose.isNotBlank() && step.purpose.length <= 300) {
                "La finalidad de cada paso debe ser concreta."
            }
            require(step.arguments.size <= 24 && step.arguments.values.all { it.length <= 8000 }) {
                "El paso supera los límites de argumentos."
            }
            val objectiveCriterion = step.expectedOutputContains
            require(!objectiveCriterion.isNullOrBlank()) {
                "Cada paso debe declarar un criterio objetivo de salida; sin él no puede verificarse el éxito."
            }
            require(objectiveCriterion.length <= 300) {
                "Criterio de salida demasiado largo."
            }
        }
    }

    private fun result(
        taskId: String,
        status: BardoAutonomyStatus,
        observations: List<BardoTaskObservation>,
        corrections: Int,
        started: Long,
        summary: String
    ) = BardoAutonomyResult(
        taskId,
        status,
        observations.toList(),
        corrections,
        (nowMillis() - started).coerceAtLeast(0L),
        summary
    )

    companion object {
        const val MAX_STEPS = 12
        const val MAX_CORRECTIONS = 2
        const val MAX_OBSERVATION_CHARS = 6000
    }
}

/** Diario JSONL append-only; no guarda argumentos ni salidas privadas de herramientas. */
class BardoTaskJournal(private val directory: File) {
    private val file: File
    private val lock = Any()

    init {
        if (!directory.exists()) check(directory.mkdirs()) {
            "No se pudo crear el diario de BARDO."
        }
        require(directory.isDirectory && !java.nio.file.Files.isSymbolicLink(directory.toPath()))
        file = File(directory.canonicalFile, "bardo_task_journal.jsonl")
        require(file.canonicalFile.parentFile == directory.canonicalFile &&
            !java.nio.file.Files.isSymbolicLink(file.toPath()))
    }

    fun record(
        taskId: String,
        event: String,
        toolId: String?,
        succeeded: Boolean,
        detail: String,
        nowMillis: Long
    ) = synchronized(lock) {
        require(taskId.matches(Regex("^[A-Za-z0-9-]{16,80}$")))
        require(event.matches(Regex("^[a-z_]{3,40}$")))
        require(toolId == null || toolId.matches(Regex("^[a-z][a-z0-9._-]{2,63}$")))
        require(detail.length <= 1000)
        require(!file.exists() || file.isFile)
        require(file.length() <= MAX_BYTES) {
            "El diario alcanzó el límite; se bloquean nuevas tareas para preservar la auditoría."
        }
        val row = JSONObject()
            .put("taskId", taskId)
            .put("event", event)
            .put("toolId", toolId)
            .put("succeeded", succeeded)
            .put("detail", detail)
            .put("timestampEpochMillis", nowMillis)
            .toString() + "\n"
        FileOutputStream(file, true).use { output ->
            output.write(row.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
    }

    fun readRecent(maxEvents: Int = 100): List<JSONObject> = synchronized(lock) {
        require(maxEvents in 1..1000)
        if (!file.isFile) return@synchronized emptyList()
        file.readLines(Charsets.UTF_8).takeLast(maxEvents).mapNotNull {
            runCatching { JSONObject(it) }.getOrNull()
        }
    }

    companion object { const val MAX_BYTES = 20L * 1024L * 1024L }
}

/** Planificador que solicita JSON tipado a un proveedor local; nunca ejecuta herramientas. */
class BardoGemmaTaskPlanner(
    private val context: Context,
    private val models: BardoModelRegistry,
    private val toolExecutor: BardoToolExecutor? = null,
    private val skillManager: BardoSkillManager? = null
) : BardoTaskPlanner {
    override fun createPlan(
        goal: String,
        previousObservations: List<BardoTaskObservation>,
        correctionReason: String?
    ): BardoActionPlan {
        val observationsJson = JSONArray().also { array ->
            previousObservations.takeLast(12).forEach { observation ->
                array.put(JSONObject()
                    .put("toolId", observation.toolId)
                    .put("succeeded", observation.succeeded)
                    .put("output", observation.output.take(2000))
                    .put("error", observation.error))
            }
        }
        val availableTools = toolExecutor?.registeredTools()?.joinToString("\n") { descriptor ->
            "- " + descriptor.id + ": " + descriptor.description +
                " (permiso=" + descriptor.requiredScope.name + ")"
        } ?: "El catálogo de herramientas no fue inyectado."
        val enabledSkills = skillManager?.enabled()?.joinToString("\n") { record ->
            "- " + record.manifest.id + "@" + record.manifest.version +
                " acciones=" + record.manifest.actions.sorted().joinToString(",") +
                " verificador=" + record.lastTestVerifierId
        } ?: "No hay catálogo de skills habilitadas."

        val prompt = """
            Eres el planificador local de BARDO. No ejecutas herramientas.
            Jerarquía permanente: usuario (autoridad máxima) > JARVIS (segunda autoridad, por encima de BARDO) > BARDO > modelos/agentes/skills/herramientas.
            La conexión técnica de JARVIS se realizará en el futuro; BARDO no depende de ella para funcionar, aprender o desarrollarse.
            BARDO es el núcleo autónomo y dirige los recursos subordinados. Ningún modelo o skill puede cambiar esta jerarquía.
            Selecciona exclusivamente herramientas del catálogo de abajo.
            La herramienta web.search_public solo está disponible después de una aprobación directa, por parte del usuario,
            de la consulta exacta en la interfaz. La aprobación es de un solo uso, vence en 120 segundos y se consume
            antes de abrir la conexión. Nunca existe un permiso global o permanente para buscar.
            Envía exclusivamente esa consulta pública aprobada; no la reformules ni añadas contexto de BARDO.
            Nunca incluyas archivos, código del usuario, proyectos importados, rutas, nombres privados, IDs,
            estadísticas de RAM/batería/almacenamiento, logs, historial, prompts ni datos de sesión.
            Si para investigar necesitarías alguno de esos datos, no busques en la web: trabaja localmente
            o pide al usuario una consulta genérica que pueda aprobar. El filtro local rechazará consultas sensibles.
            Para ejecutar una skill completa, llama a skill.run_workflow con skill_id, version
            e inputs_json (objeto JSON de cadenas). El paquete es JSON declarativo con schema_version=1
            y steps; cada paso contiene action_id y arguments. Los valores pueden usar {{input:nombre}}
            para recibir una entrada. Solo usa skills habilitadas y acciones declaradas.
            Para una acción aislada, skill.run_action admite skill_id, version, action_id
            y action_arguments_json. Toda acción vuelve a pasar por ToolExecutor y permisos.
            Para diseñar una skill declarativa nueva, puedes llamar a skills.create_draft con
            id, name, version, description, license y workflow_json. workflow_json debe ser
            JSON schema_version=1 con steps; cada paso usa solo acciones registradas y sus
            argumentos de texto. El sistema calcula hash y permisos, valida el workflow y
            guarda un .bskill en borradores privados. Esto NO importa, instala ni habilita
            la skill. Nunca inventes una licencia: usa una licencia conocida de la fuente o
            deja la creación pendiente de confirmación del usuario. No incluyas scripts,
            comandos, código ejecutable ni URL de descarga en el workflow.
            Devuelve SOLO JSON:
            {"correctionBudget":1,"steps":[{"toolId":"id.registrado","purpose":"motivo breve","arguments":{"key":"value"},"expectedOutputContains":"frase literal, distintiva y verificable que debe aparecer en la salida de esa herramienta"}]}
            expectedOutputContains es obligatorio en TODOS los pasos, no uses frases genéricas.
            Elige una cadena literal que realmente deba aparecer en la respuesta esperada de la herramienta.
            Si no puedes definir un criterio objetivo, no incluyas ese paso.
            Máximo 12 pasos y 2 correcciones. Cada paso invoca una herramienta tipada.
            No propongas shell, comandos, código, URL arbitrarias, permisos, GitHub write,
            merge, instalación, borrado, cambios a main ni acceso fuera de BARDO.
            Herramientas registradas:
            $availableTools

            Skills habilitadas y verificadas:
            $enabledSkills

            Objetivo:
            $goal
            Motivo de corrección:
            ${correctionReason.orEmpty()}
            Observaciones previas:
            $observationsJson
        """.trimIndent()
        val response = models.generate(
            context,
            "general_chat",
            BardoModelRequest(prompt, resetConversation = true),
            allowRemoteModels = false
        )
        val fence = 96.toChar().toString().repeat(3)
        val jsonText = response.text.substringAfter("</think>", response.text).trim()
            .removePrefix(fence + "json").removePrefix(fence).removeSuffix(fence).trim()
        val root = JSONObject(jsonText)
        val array = root.optJSONArray("steps")
            ?: throw IllegalArgumentException("El modelo local no devolvió una lista de pasos.")
        require(array.length() in 1..BardoAutonomyEngine.MAX_STEPS) {
            "El plan devuelto por el modelo local excede los pasos permitidos."
        }
        val steps = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val argsObject = item.optJSONObject("arguments") ?: JSONObject()
            val args = linkedMapOf<String, String>()
            val keys = argsObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = argsObject.get(key)
                require(value is String) { "Los argumentos de herramientas deben ser texto simple." }
                args[key] = value
            }
            BardoTaskStep(
                toolId = item.getString("toolId"),
                arguments = args,
                purpose = item.optString("purpose"),
                expectedOutputContains = item.optString("expectedOutputContains")
                    .takeIf { it.isNotBlank() && it != "null" }
            )
        }
        val budget = root.optInt("correctionBudget", 1)
        require(budget in 0..BardoAutonomyEngine.MAX_CORRECTIONS)
        return BardoActionPlan(steps, budget)
    }
}
