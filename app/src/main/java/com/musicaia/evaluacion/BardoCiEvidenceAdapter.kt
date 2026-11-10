package com.musicaia.evaluacion

import org.json.JSONArray
import org.json.JSONObject

enum class BardoCiEvidenceState {
    PENDING,
    CI_PASSED,
    CI_FAILED,
    INVALID
}

data class BardoCiEvidenceCollection(
    val state: BardoCiEvidenceState,
    val runId: Long?,
    val headSha: String?,
    val evidence: List<BardoCheckEvidence>,
    val problems: List<String>
)

/**
 * Adapta respuestas de la API REST de GitHub Actions a evidencia tipada.
 *
 * No acepta resúmenes escritos por el modelo: comprueba repositorio, workflow,
 * SHA, ejecución, job, nombres y conclusiones de pasos, y el artefacto asociado.
 * Solo produce los cuatro checks que realmente demuestra bardo-validation.yml.
 * No produce APK_AUDIT ni ninguna evidencia de instalación, Gemma, offline o WAV.
 */
object BardoCiEvidenceAdapter {
    const val REPOSITORY = "loboxfake/musica-ia"
    const val WORKFLOW_PATH = ".github/workflows/bardo-validation.yml"

    private const val JOB_NAME = "validate"
    private const val GITLEAKS_STEP = "Scan complete Git history with redacted findings"
    private const val ACTIONLINT_STEP = "Lint all GitHub Actions workflows"
    private const val GRADLE_STEP = "Run unit tests and compile debug APK"
    private const val VERIFY_APK_STEP = "Verify candidate APK exists"
    private const val UPLOAD_APK_STEP = "Upload branch validation APK (not a release)"

    private val shaPattern = Regex("^[0-9a-f]{40}$")

    fun collect(
        runJson: JSONObject,
        jobsJson: JSONObject,
        artifactsJson: JSONObject,
        expectedHeadSha: String
    ): BardoCiEvidenceCollection {
        val expectedSha = expectedHeadSha.lowercase()
        if (!shaPattern.matches(expectedSha)) {
            return invalid("El SHA esperado no tiene 40 caracteres hexadecimales.")
        }

        val runId = runJson.optLong("id", 0L)
        if (runId <= 0L) return invalid("La ejecución no contiene un ID válido.")

        val headSha = runJson.optString("head_sha", "").lowercase()
        if (!shaPattern.matches(headSha) || headSha != expectedSha) {
            return invalid("La ejecución de GitHub no corresponde al commit candidato esperado.", runId, headSha)
        }

        val repository = runJson.optJSONObject("repository")
            ?.optString("full_name", "").orEmpty()
        val headRepository = runJson.optJSONObject("head_repository")
            ?.optString("full_name", "").orEmpty()
        val workflowPath = runJson.optString("path", "").substringBefore("@")
        val branch = runJson.optString("head_branch", "")
        val event = runJson.optString("event", "")
        val expectedUrl = "https://github.com/" + REPOSITORY + "/actions/runs/" + runId
        val htmlUrl = runJson.optString("html_url", "")

        if (repository != REPOSITORY || headRepository != REPOSITORY) {
            return invalid("El repositorio base y el origen de la rama deben ser el repositorio BARDO autorizado.", runId, headSha)
        }
        if (workflowPath != WORKFLOW_PATH) {
            return invalid("La ejecución no procede del workflow BARDO de validación esperado.", runId, headSha)
        }
        if (!branch.startsWith("bardo/")) {
            return invalid("La rama de origen no pertenece al espacio bardo/.", runId, headSha)
        }
        if (event !in setOf("push", "pull_request", "workflow_dispatch")) {
            return invalid("El evento de GitHub no está permitido para esta evidencia.", runId, headSha)
        }
        if (htmlUrl != expectedUrl) {
            return invalid("La URL de la ejecución no coincide con su ID y repositorio.", runId, headSha)
        }

        val runStatus = runJson.optString("status", "")
        if (runStatus != "completed") {
            return BardoCiEvidenceCollection(
                state = BardoCiEvidenceState.PENDING,
                runId = runId,
                headSha = headSha,
                evidence = emptyList(),
                problems = listOf("La ejecución sigue pendiente; todavía no se genera evidencia.")
            )
        }

        val runNumber = runJson.optLong("run_number", 0L)
        if (runNumber <= 0L) {
            return invalid("La ejecución no contiene un número de workflow válido.", runId, headSha)
        }

        val jobs = jobsJson.optJSONArray("jobs") ?: JSONArray()
        val matchingJobs = (0 until jobs.length()).mapNotNull { jobs.optJSONObject(it) }
            .filter { it.optString("name", "") == JOB_NAME }
        if (matchingJobs.size != 1) {
            return invalid("No existe un único job validate en la ejecución.", runId, headSha)
        }

        val job = matchingJobs.single()
        if (job.optLong("run_id", runId) != runId) {
            return invalid("El job recibido pertenece a otra ejecución.", runId, headSha)
        }
        if (job.optString("status", "") != "completed") {
            return BardoCiEvidenceCollection(
                state = BardoCiEvidenceState.PENDING,
                runId = runId,
                headSha = headSha,
                evidence = emptyList(),
                problems = listOf("El job validate todavía no ha terminado.")
            )
        }

        val stepsArray = job.optJSONArray("steps") ?: JSONArray()
        val steps = linkedMapOf<String, JSONObject>()
        for (index in 0 until stepsArray.length()) {
            val step = stepsArray.optJSONObject(index) ?: continue
            val name = step.optString("name", "")
            if (name.isNotBlank()) {
                if (steps.put(name, step) != null) {
                    return invalid("La API devolvió nombres de pasos duplicados.", runId, headSha)
                }
            }
        }

        val pipelineSucceeded =
            runJson.optString("conclusion", "") == "success" &&
                job.optString("conclusion", "") == "success"

        fun stepSucceeded(name: String): Boolean {
            val step = steps[name] ?: return false
            return step.optString("status", "") == "completed" &&
                step.optString("conclusion", "") == "success"
        }

        val artifactName = "bardo-debug-candidate-" + runNumber
        val artifacts = artifactsJson.optJSONArray("artifacts") ?: JSONArray()
        val matchingArtifacts = (0 until artifacts.length()).mapNotNull {
            artifacts.optJSONObject(it)
        }.filter { it.optString("name", "") == artifactName }

        val artifact = matchingArtifacts.singleOrNull()
        val artifactRun = artifact?.optJSONObject("workflow_run")
        val artifactDigest = artifact?.optString("digest", "").orEmpty()
        val artifactDigestMetadataValid = Regex("^sha256:[0-9a-f]{64}$").matches(artifactDigest)
        val artifactVerified = artifact != null &&
            artifactRun != null &&
            !artifact.optBoolean("expired", true) &&
            artifact.optLong("size_in_bytes", 0L) > 0L &&
            artifactRun.optLong("id", 0L) == runId &&
            artifactRun.optString("head_sha", "").equals(expectedSha, ignoreCase = true) &&
            artifactDigestMetadataValid

        val reference = expectedUrl
        val allGood = pipelineSucceeded &&
            stepSucceeded(GITLEAKS_STEP) &&
            stepSucceeded(ACTIONLINT_STEP) &&
            stepSucceeded(GRADLE_STEP) &&
            stepSucceeded(VERIFY_APK_STEP) &&
            stepSucceeded(UPLOAD_APK_STEP) &&
            artifactVerified

        fun addEvidence(
            check: BardoCheck,
            stepName: String,
            passed: Boolean,
            extra: String = ""
        ): BardoCheckEvidence {
            val step = steps[stepName]
            val conclusion = step?.optString("conclusion", "missing") ?: "missing"
            val status = step?.optString("status", "missing") ?: "missing"
            val detail = buildString {
                append("workflow=")
                append(WORKFLOW_PATH)
                append("; run=")
                append(runId)
                append("; commit=")
                append(expectedSha)
                append("; job=")
                append(JOB_NAME)
                append("; step=")
                append(stepName)
                append("; step_status=")
                append(status)
                append("; step_conclusion=")
                append(conclusion)
                append("; run_conclusion=")
                append(runJson.optString("conclusion", "missing"))
                if (extra.isNotBlank()) {
                    append("; ")
                    append(extra)
                }
            }.take(2_000)
            return BardoCheckEvidence(
                check = check,
                passed = passed,
                detail = detail,
                evidenceRef = reference,
                origin = BardoEvidenceOrigin.GITHUB_ACTIONS
            )
        }

        val evidence = listOf(
            addEvidence(
                BardoCheck.GITLEAKS,
                GITLEAKS_STEP,
                pipelineSucceeded && stepSucceeded(GITLEAKS_STEP)
            ),
            addEvidence(
                BardoCheck.ACTIONLINT,
                ACTIONLINT_STEP,
                pipelineSucceeded && stepSucceeded(ACTIONLINT_STEP)
            ),
            addEvidence(
                BardoCheck.UNIT_TESTS,
                GRADLE_STEP,
                pipelineSucceeded && stepSucceeded(GRADLE_STEP),
                "El paso Gradle ejecuta testDebugUnitTest y assembleDebug."
            ),
            addEvidence(
                BardoCheck.DEBUG_BUILD,
                UPLOAD_APK_STEP,
                pipelineSucceeded &&
                    stepSucceeded(GRADLE_STEP) &&
                    stepSucceeded(VERIFY_APK_STEP) &&
                    stepSucceeded(UPLOAD_APK_STEP) &&
                    artifactVerified,
                "verify_apk=" + stepSucceeded(VERIFY_APK_STEP) +
                    "; artifact=" + artifactName +
                    "; artifact_verified=" + artifactVerified +
                    "; artifact_digest_metadata_format_valid=" + artifactDigestMetadataValid
            )
        )

        val problems = buildList {
            if (!pipelineSucceeded) add("La ejecución o el job validate no terminó en SUCCESS.")
            if (!stepSucceeded(GITLEAKS_STEP)) add("Gitleaks no terminó en SUCCESS.")
            if (!stepSucceeded(ACTIONLINT_STEP)) add("actionlint no terminó en SUCCESS.")
            if (!stepSucceeded(GRADLE_STEP)) add("Los tests unitarios y/o la compilación debug no terminaron en SUCCESS.")
            if (!stepSucceeded(VERIFY_APK_STEP)) add("La verificación de existencia del APK no terminó en SUCCESS.")
            if (!stepSucceeded(UPLOAD_APK_STEP)) add("La subida del artefacto debug no terminó en SUCCESS.")
            if (!artifactVerified) add("No se ha verificado la presencia/formato del digest de GitHub y la asociación del artefacto no expirado al mismo run y commit.")
        }

        return BardoCiEvidenceCollection(
            state = if (allGood) BardoCiEvidenceState.CI_PASSED else BardoCiEvidenceState.CI_FAILED,
            runId = runId,
            headSha = headSha,
            evidence = evidence,
            problems = problems
        )
    }

    private fun invalid(
        message: String,
        runId: Long? = null,
        headSha: String? = null
    ) = BardoCiEvidenceCollection(
        state = BardoCiEvidenceState.INVALID,
        runId = runId,
        headSha = headSha,
        evidence = emptyList(),
        problems = listOf(message)
    )
}


data class BardoCandidateEvaluation(
    val ci: BardoCiEvidenceCollection,
    val report: BardoEvaluationReport?
)

/**
 * Conecta evidencias API de CI al evaluador determinista existente.
 * La falta de CI válido/pasado nunca se interpreta como revisión aprobada.
 * Las pruebas físicas deben aportarse por separado y conservar su origen DEVICE_TEST.
 */
object BardoCandidateEvaluationPipeline {
    fun evaluate(
        runJson: JSONObject,
        jobsJson: JSONObject,
        artifactsJson: JSONObject,
        expectedHeadSha: String,
        nonCiEvidence: List<BardoCheckEvidence>
    ): BardoCandidateEvaluation {
        val githubChecks = setOf(
            BardoCheck.GITLEAKS,
            BardoCheck.ACTIONLINT,
            BardoCheck.UNIT_TESTS,
            BardoCheck.DEBUG_BUILD
        )
        require(nonCiEvidence.none { it.check in githubChecks }) {
            "Las evidencias de Gitleaks, actionlint, tests y build solo pueden proceder del adaptador GitHub verificado."
        }
        val collection = BardoCiEvidenceAdapter.collect(
            runJson,
            jobsJson,
            artifactsJson,
            expectedHeadSha
        )
        val report = when (collection.state) {
            BardoCiEvidenceState.PENDING,
            BardoCiEvidenceState.INVALID -> null
            BardoCiEvidenceState.CI_PASSED,
            BardoCiEvidenceState.CI_FAILED -> BardoIndependentEvaluator.evaluate(
                nonCiEvidence + collection.evidence
            )
        }
        return BardoCandidateEvaluation(collection, report)
    }
}
