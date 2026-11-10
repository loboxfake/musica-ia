package com.musicaia

import android.os.Bundle
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.musicaia.desarrollo.BardoGitHubActionsClient
import com.musicaia.desarrollo.BardoGitHubCredentialStore
import com.musicaia.desarrollo.BardoGitHubIntegrationPolicy
import com.musicaia.desarrollo.BardoGitHubPolicy
import com.musicaia.desarrollo.BardoSnapshotOriginPolicy
import com.musicaia.desarrollo.BardoGitHubRun
import com.musicaia.evaluacion.BardoCheckEvidence
import com.musicaia.evaluacion.BardoLocalCandidateAuditInput
import com.musicaia.evaluacion.BardoLocalCandidateEvidenceCollector
import java.util.concurrent.Executors

/** Puente de CI: valida diffs aislados sobre un snapshot exacto de main; no crea ramas ni PR. */
class BardoGitHubDevelopmentActivity : AppCompatActivity() {
    private lateinit var output: TextView
    private lateinit var tokenInput: EditText
    private lateinit var validateButton: Button
    private lateinit var refreshButton: Button
    private lateinit var integrateButton: Button
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "BARDO-GitHub-CI").apply { isDaemon = true }
    }
    private val state by lazy { getSharedPreferences("bardo_github_candidate", MODE_PRIVATE) }

    private var baseSha = ""
    private var candidateId = ""
    private var patch = ""
    private var changedFiles: List<String> = emptyList()
    private var validationRunId = 0L
    private var mainCommitRunId = 0L
    private var validationDispatched = false
    private var validationSucceeded = false
    private var mainCommitDispatched = false
    private var mainCommitSucceeded = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        baseSha = intent.getStringExtra(EXTRA_BASE_SHA).orEmpty()
        candidateId = intent.getStringExtra(EXTRA_CANDIDATE_ID).orEmpty()
        patch = intent.getStringExtra(EXTRA_PATCH).orEmpty()
        changedFiles = intent.getStringArrayListExtra(EXTRA_CHANGED_FILES).orEmpty()

        val valid = sourceOriginIsTrusted() && candidatePromptPrivacyIsSafe() && runCatching {
            BardoGitHubPolicy.validateBaseSha(baseSha)
            BardoGitHubPolicy.validateCandidateId(candidateId)
            BardoGitHubPolicy.validatePatch(patch)
            BardoGitHubPolicy.validateChangedPaths(changedFiles)
        }.isSuccess

        validationRunId = state.getLong(key("validation_run_id"), 0L)
        mainCommitRunId = state.getLong(key("main_commit_run_id"), 0L)
        validationDispatched = state.getBoolean(key("validation_dispatched"), validationRunId > 0L)
        validationSucceeded = state.getBoolean(key("validation_succeeded"), false)
        mainCommitDispatched = state.getBoolean(key("main_commit_dispatched"), mainCommitRunId > 0L)
        mainCommitSucceeded = state.getBoolean(key("main_commit_succeeded"), false)

        buildUi()
        if (!valid) {
            output.text = when {
                !sourceOriginIsTrusted() ->
                    "ENVÍO BLOQUEADO POR PRIVACIDAD\n\nEste snapshot procede de un proyecto importado o no tiene procedencia BARDO verificada. Puede analizarse y modificarse localmente, pero no se enviará ningún archivo ni diff a GitHub."
                !candidatePromptPrivacyIsSafe() ->
                    "ENVÍO BLOQUEADO POR PRIVACIDAD\n\nLa propuesta se generó con un contexto que no está certificado como libre de referencias, memoria, diagnósticos y estadísticas del dispositivo. No se enviará a GitHub."
                else ->
                    "CANDIDATO NO VÁLIDO\n\nEste proyecto no contiene el SHA de origen o la propuesta modifica una ruta protegida. No se ha enviado nada a GitHub."
            }
            validateButton.isEnabled = false
            integrateButton.isEnabled = false
            return
        }

        output.text = buildString {
            appendLine("BARDO · DESARROLLO AUTÓNOMO CON GITHUB ACTIONS")
            appendLine()
            appendLine("Repositorio: ${BardoGitHubPolicy.REPOSITORY}")
            appendLine("Rama base: main")
            appendLine("Destino de desarrollo: main (la validación es aislada; no crea ramas)")
            appendLine("Commit base: $baseSha")
            appendLine("Candidato: $candidateId")
            appendLine()
            appendLine("Archivos propuestos:")
            changedFiles.forEach { appendLine("• $it") }
            appendLine()
            appendLine("PAT fine-grained necesario: Actions: Read and write y Contents: Read, limitado a este repositorio.")
            appendLine("El PAT se cifra con Android Keystore. No se incluye en el APK ni se transmite al runner.")
            appendLine()
            appendLine("La validación se ejecuta primero en un runner efímero y no modifica el repositorio.")
            appendLine("Después de SUCCESS puedes autorizar expresamente un único commit directo a main. El workflow vuelve a comprobar el SHA base, repite tests y APK ARM64 y usa un push normal sin force.")
            appendLine("Si main se ha movido, el workflow aborta sin sobrescribir commits.")
            appendLine("Solo el GITHUB_TOKEN del job de integración obtiene Contents: Write; el PAT personal permanece con Contents: Read.")
        }
        renderButtons()
    }

    private fun sourceOriginIsTrusted(): Boolean =
        BardoSnapshotOriginPolicy.allowsPrivateRepositoryCi(
            getSharedPreferences("bardo_developer", MODE_PRIVATE)
                .getString(BardoSnapshotOriginPolicy.PREF_KEY, null)
        )

    private fun candidatePromptPrivacyIsSafe(): Boolean =
        getSharedPreferences("bardo_developer", MODE_PRIVATE)
            .getBoolean("candidate_prompt_privacy_safe", false)

    private fun buildUi() {
        val white = android.graphics.Color.rgb(235, 243, 255)
        val cyan = android.graphics.Color.rgb(112, 216, 255)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 14)
            setBackgroundColor(android.graphics.Color.rgb(7, 15, 29))
        }
        root.addView(TextView(this).apply {
            text = "BARDO · GITHUB DEVELOPER"
            textSize = 20f
            setTextColor(cyan)
            setPadding(0, 4, 0, 12)
        })
        root.addView(TextView(this).apply {
            text = "VALIDACIÓN AISLADA · SOLO MAIN · SIN RAMAS NI PR · SIN TOKEN DENTRO DEL APK"
            textSize = 12f
            setTextColor(white)
            setPadding(0, 0, 0, 10)
        })
        tokenInput = EditText(this).apply {
            hint = "Token fine-grained github_pat_…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setTextColor(white)
            setHintTextColor(android.graphics.Color.LTGRAY)
            setPadding(12, 8, 12, 8)
            setBackgroundColor(android.graphics.Color.rgb(23, 34, 53))
        }
        root.addView(tokenInput, LinearLayout.LayoutParams(-1, 52))
        addButton(root, "Guardar token cifrado en este dispositivo") { saveToken() }
        addButton(root, "Eliminar token guardado") { revokeToken() }
        validateButton = addButton(root, "Enviar candidato a validación aislada") { dispatchValidation() }
        refreshButton = addButton(root, "Consultar CI y resultado") { refreshRuns() }
        integrateButton = addButton(root, "Autorizar integración del candidato en main") { confirmMainIntegration() }
        addButton(root, "Volver") { finish() }

        val scroll = ScrollView(this).apply { isFillViewport = true }
        output = TextView(this).apply {
            textSize = 12f
            setTextColor(white)
            setTextIsSelectable(true)
            setPadding(12, 14, 12, 24)
            setBackgroundColor(android.graphics.Color.rgb(15, 25, 42))
            movementMethod = LinkMovementMethod.getInstance()
        }
        scroll.addView(output)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 12 })
        setContentView(root)
    }

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit): Button {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(-1, 48).apply { topMargin = 5 })
        return button
    }

    private fun saveToken() {
        runCatching { BardoGitHubCredentialStore.save(this, tokenInput.text.toString()) }
            .onSuccess {
                tokenInput.text?.clear()
                output.text = "TOKEN GUARDADO\n\nLa clave AES-GCM es no exportable y reside en Android Keystore. El token nunca se muestra ni se escribe en informes."
                renderButtons()
            }
            .onFailure { output.text = "No se pudo guardar el token: ${it.message}" }
    }

    private fun revokeToken() {
        BardoGitHubCredentialStore.clear(this)
        output.text = "Token eliminado del almacenamiento local y clave de Keystore revocada. No se ha cambiado GitHub."
        renderButtons()
    }

    private fun dispatchValidation() {
        if (!sourceOriginIsTrusted() || !candidatePromptPrivacyIsSafe()) {
            output.text = "Envío bloqueado por privacidad: solo se envían diffs de BARDO generados sin referencias, memoria, diagnósticos ni estadísticas privadas."
            return
        }
        if (busy || validationDispatched) return
        val token = BardoGitHubCredentialStore.read(this)
        if (token == null) {
            output.text = "Primero guarda un token fine-grained de GitHub."
            return
        }
        val payload = runCatching { BardoGitHubPolicy.packPatch(patch) }.getOrElse {
            output.text = "No se pudo empaquetar la propuesta: ${it.message}"
            return
        }
        setBusy(true, "Enviando candidato a un runner efímero. main no se modifica.")
        executor.execute {
            val result = runCatching {
                collectLocalCandidateEvidence()
                val client = BardoGitHubActionsClient(token)
                client.dispatchValidation(baseSha, candidateId, payload)
                var found: BardoGitHubRun? = null
                repeat(20) {
                    if (found == null) {
                        found = client.findRun(BardoGitHubActionsClient.VALIDATION_WORKFLOW, candidateId, baseSha)
                        if (found == null) Thread.sleep(1500L)
                    }
                }
                val summary = found?.let { client.jobsSummary(it.id) }.orEmpty()
                found to summary
            }
            runOnUiThread {
                result.onSuccess { (run, summary) ->
                    validationDispatched = true
                    state.edit().putBoolean(key("validation_dispatched"), true).apply()
                    if (run == null) {
                        output.text = "GitHub aceptó el envío. La ejecución todavía no aparece en la lista; pulsa «Consultar CI» dentro de unos segundos."
                    } else {
                        validationRunId = run.id
                        state.edit().putLong(key("validation_run_id"), run.id).apply()
                        if (run.succeeded) {
                            validationSucceeded = true
                            state.edit().putBoolean(key("validation_succeeded"), true).apply()
                        }
                        showRun("VALIDACIÓN DEL CANDIDATO", run, summary)
                    }
                }.onFailure { output.text = "No se pudo enviar el candidato: ${it.message}" }
                setBusy(false)
            }
        }
    }

    private data class RunRefresh(
        val validation: BardoGitHubRun?,
        val mainCommit: BardoGitHubRun?,
        val validationSummary: String,
        val mainCommitSummary: String,
        val integratedCommit: Pair<String, String>?
    )

    private fun refreshRuns() {
        if (busy) return
        val token = BardoGitHubCredentialStore.read(this)
        if (token == null) {
            output.text = "No hay un token GitHub guardado."
            return
        }
        setBusy(true, "Consultando la validación y el resultado de integración de main…")
        executor.execute {
            val result = runCatching {
                val client = BardoGitHubActionsClient(token)
                val validation = if (validationRunId > 0L) client.getRun(validationRunId) else
                    client.findRun(BardoGitHubActionsClient.VALIDATION_WORKFLOW, candidateId, baseSha)
                val mainCommit = if (mainCommitRunId > 0L) client.getRun(mainCommitRunId) else {
                    if (mainCommitDispatched) {
                        client.findRun(BardoGitHubActionsClient.MAIN_COMMIT_WORKFLOW, candidateId, baseSha)
                    } else null
                }
                if (validation != null && validationRunId == 0L) {
                    validationRunId = validation.id
                    state.edit().putLong(key("validation_run_id"), validation.id).apply()
                }
                if (mainCommit != null && mainCommitRunId == 0L) {
                    mainCommitRunId = mainCommit.id
                    state.edit().putLong(key("main_commit_run_id"), mainCommit.id).apply()
                }
                val validationSummary = validation?.let { client.jobsSummary(it.id) }.orEmpty()
                val mainCommitSummary = mainCommit?.let { client.jobsSummary(it.id) }.orEmpty()
                // The workflow run's head SHA is the base commit. Confirm integration
                // independently by looking for the unique candidate ID in main history.
                val integrated = if (mainCommit?.finished == true) {
                    client.integratedCommit(candidateId)
                } else null
                RunRefresh(validation, mainCommit, validationSummary, mainCommitSummary, integrated)
            }
            runOnUiThread {
                result.onSuccess { snapshot ->
                    if (snapshot.integratedCommit != null) {
                        mainCommitSucceeded = true
                        state.edit().putBoolean(key("main_commit_succeeded"), true).apply()
                        snapshot.mainCommit?.let {
                            showRun("INTEGRACIÓN EN MAIN", it, snapshot.mainCommitSummary)
                        }
                        output.append(
                            "\n\nCOMMIT CONFIRMADO EN MAIN\nSHA: ${snapshot.integratedCommit.first}" +
                                "\n${snapshot.integratedCommit.second}" +
                                "\nLa búsqueda del historial de main confirma que el commit del candidato está incorporado."
                        )
                        android.text.util.Linkify.addLinks(output, android.text.util.Linkify.WEB_URLS)
                    } else if (snapshot.mainCommit != null) {
                        showRun("INTEGRACIÓN EN MAIN", snapshot.mainCommit, snapshot.mainCommitSummary)
                        if (snapshot.mainCommit.finished && !snapshot.mainCommit.succeeded) {
                            output.append(
                                "\n\nNo se ha encontrado el commit del candidato en main. Revisa el error del workflow; no se intentará forzar ni sobrescribir la rama."
                            )
                        } else if (snapshot.mainCommit.succeeded) {
                            output.append(
                                "\n\nEl workflow terminó correctamente; la consulta del historial aún no devuelve el commit. Vuelve a consultar para confirmar el SHA definitivo."
                            )
                        }
                    } else if (snapshot.validation != null) {
                        if (snapshot.validation.succeeded) {
                            validationSucceeded = true
                            state.edit().putBoolean(key("validation_succeeded"), true).apply()
                        }
                        showRun("VALIDACIÓN DEL CANDIDATO", snapshot.validation, snapshot.validationSummary)
                    } else {
                        output.text = "No se encontró una ejecución para este candidato. Envíalo primero a validación aislada."
                    }
                }.onFailure {
                    output.text = "No se pudo consultar GitHub: ${it.message}"
                }
                setBusy(false)
            }
        }
    }

    private fun confirmMainIntegration() {
        if (!BardoGitHubIntegrationPolicy.MAIN_COMMIT_WORKFLOW_ENABLED) {
            output.text = BardoGitHubIntegrationPolicy.BLOCK_REASON
            return
        }
        if (!validationSucceeded || validationRunId <= 0L || mainCommitSucceeded || mainCommitDispatched) {
            output.text = "Solo puedes integrar un candidato cuya validación aislada haya terminado en SUCCESS y que aún no se haya integrado."
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Autorizar commit directo a main")
            .setMessage(
                "Candidato: $candidateId\nCommit base: $baseSha\n\n" +
                    "GitHub comprobará que la validación aislada pertenece exactamente a este candidato y a este SHA. " +
                    "Después aplicará el mismo parche en un runner efímero, repetirá las pruebas Kotlin y la compilación ARM64, " +
                    "volverá a comprobar que main no ha cambiado y hará un push normal (sin force). " +
                    "Si main se ha movido, abortará sin sobrescribir trabajo.\n\n¿Autorizas este commit a main?"
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Autorizar commit a main") { _, _ -> dispatchMainIntegration() }
            .show()
    }

    private fun dispatchMainIntegration() {
        if (!sourceOriginIsTrusted() || !candidatePromptPrivacyIsSafe()) {
            output.text = "Integración bloqueada por privacidad: la procedencia o el contexto del candidato no están certificados como exclusivos de BARDO."
            return
        }
        if (!BardoGitHubIntegrationPolicy.MAIN_COMMIT_WORKFLOW_ENABLED) {
            output.text = BardoGitHubIntegrationPolicy.BLOCK_REASON
            return
        }
        if (busy || mainCommitDispatched || mainCommitSucceeded || !validationSucceeded || validationRunId <= 0L) return
        val token = BardoGitHubCredentialStore.read(this)
        if (token == null) {
            output.text = "No hay un token GitHub guardado."
            return
        }
        val payload = runCatching { BardoGitHubPolicy.packPatch(patch) }.getOrElse {
            output.text = "No se pudo empaquetar la propuesta: ${it.message}"
            return
        }
        setBusy(true, "Aprobación recibida. GitHub vuelve a verificar el candidato y solo hará un push normal a main si la base sigue intacta.")
        executor.execute {
            val result = runCatching {
                val client = BardoGitHubActionsClient(token)
                val validation = client.getRun(validationRunId)
                check(validation.succeeded && validation.headSha.equals(baseSha, true)) {
                    "La validación no terminó en SUCCESS para el SHA base de este candidato."
                }
                collectLocalCandidateEvidence()
                client.dispatchMainCommit(
                    baseSha = baseSha,
                    candidateId = candidateId,
                    packedPatch = payload,
                    validationRunId = validationRunId,
                    explicitlyApprovedByUser = true
                )
                var found: BardoGitHubRun? = null
                repeat(20) {
                    if (found == null) {
                        found = client.findRun(BardoGitHubActionsClient.MAIN_COMMIT_WORKFLOW, candidateId, baseSha)
                        if (found == null) Thread.sleep(1500L)
                    }
                }
                val summary = found?.let { client.jobsSummary(it.id) }.orEmpty()
                val integrated = if (found?.finished == true) client.integratedCommit(candidateId) else null
                Triple(found, summary, integrated)
            }
            runOnUiThread {
                result.onSuccess { (run, summary, integrated) ->
                    mainCommitDispatched = true
                    state.edit().putBoolean(key("main_commit_dispatched"), true).apply()
                    if (run != null) {
                        mainCommitRunId = run.id
                        state.edit().putLong(key("main_commit_run_id"), run.id).apply()
                    }
                    when {
                        integrated != null -> {
                            mainCommitSucceeded = true
                            state.edit().putBoolean(key("main_commit_succeeded"), true).apply()
                            if (run != null) showRun("INTEGRACIÓN EN MAIN", run, summary)
                            output.append(
                                "\n\nCOMMIT CONFIRMADO EN MAIN\nSHA: ${integrated.first}\n${integrated.second}"
                            )
                            android.text.util.Linkify.addLinks(output, android.text.util.Linkify.WEB_URLS)
                        }
                        run == null -> {
                            output.text = "GitHub aceptó la solicitud de integración. La ejecución aún no aparece; pulsa «Consultar CI y resultado» para recuperarla."
                        }
                        else -> showRun("INTEGRACIÓN EN MAIN", run, summary)
                    }
                }.onFailure {
                    output.text = "No se pudo iniciar la integración a main: ${it.message}"
                }
                setBusy(false)
            }
        }
    }

    /** Recalcula la auditoría desde el workspace privado; no reutiliza el booleano como evidencia. */
    private fun collectLocalCandidateEvidence(): List<BardoCheckEvidence> {
        check(sourceOriginIsTrusted()) {
            "La candidatura no procede del código propio de BARDO; no puede enviarse a GitHub."
        }
        check(candidatePromptPrivacyIsSafe()) {
            "El contexto que produjo el parche no tiene evidencia de aislamiento de datos privados."
        }
        val prefs = getSharedPreferences("bardo_developer", MODE_PRIVATE)
        check(prefs.getBoolean("candidate_local_audit_passed", false)) {
            "La candidatura no conserva un resultado local de auditoría superado."
        }
        val storedId = prefs.getString("candidate_id", "").orEmpty()
        val storedBase = prefs.getString("candidate_base_sha", "").orEmpty()
        val storedPatch = prefs.getString("candidate_patch", "").orEmpty()
        val storedPaths = prefs.getString("candidate_files", "").orEmpty()
            .lines().filter { it.isNotBlank() }
        check(storedId == candidateId && storedBase.equals(baseSha, true) && storedPatch == patch &&
            storedPaths.sorted() == changedFiles.sorted()
        ) { "Los datos locales ya no coinciden con la candidatura abierta en GitHub." }

        val workspace = java.io.File(filesDir, "bardo_developer")
        val snapshot = prefs.getString("source_snapshot_path", "").orEmpty()
            .takeIf { it.isNotBlank() }?.let { java.io.File(it) }
            ?: throw IllegalStateException("No está disponible el snapshot base local.")
        val candidate = prefs.getString("candidate_path", "").orEmpty()
            .takeIf { it.isNotBlank() }?.let { java.io.File(it) }
            ?: throw IllegalStateException("No está disponible la copia candidata local.")
        val evidence = BardoLocalCandidateEvidenceCollector.collect(
            BardoLocalCandidateAuditInput(
                workspaceRoot = workspace,
                snapshotDirectory = snapshot,
                candidateDirectory = candidate,
                expectedBaseSha = baseSha,
                patchText = patch,
                changedFiles = changedFiles,
                auditId = "candidate-" + candidateId
            )
        )
        check(BardoLocalCandidateEvidenceCollector.isReadyForCiSubmission(evidence)) {
            "La auditoría local recalculada no ha superado todos los controles."
        }
        return evidence
    }

    private fun showRun(label: String, run: BardoGitHubRun, jobSummary: String = "") {
        if (run.conclusion == "failure") saveCiDiagnostics(run, label, jobSummary)
        output.text = buildString {
            appendLine(label)
            appendLine("ID: ${run.id}")
            appendLine("Estado: ${run.status}")
            appendLine("Conclusión: ${run.conclusion ?: "pendiente"}")
            appendLine("Commit: ${run.headSha}")
            appendLine(run.url)
            if (jobSummary.isNotBlank()) {
                appendLine()
                appendLine("DETALLE DE CI:")
                appendLine(jobSummary)
            }
            if (run.conclusion == "failure") appendLine("\nLa ejecución falló. Revisa los pasos anteriores. No se ejecutará ningún force-push ni se sobrescribirá main.")
            if (run.succeeded && label.contains("VALIDACIÓN")) {
                appendLine("\nVALIDACIÓN SUPERADA. La propuesta sigue aislada. Puedes autorizar por separado un commit directo a main.")
            }
            if (run.succeeded && label.contains("INTEGRACIÓN")) {
                appendLine("\nEl workflow terminó. BARDO comprobará por separado el SHA del commit dentro del historial de main.")
            }
        }
        android.text.util.Linkify.addLinks(output, android.text.util.Linkify.WEB_URLS)
        renderButtons()
    }

    private fun saveCiDiagnostics(run: BardoGitHubRun, stage: String, summary: String) {
        val report = buildString {
            appendLine("GitHub Actions failure")
            appendLine("Stage: $stage")
            appendLine("Run ID: ${run.id}")
            appendLine("Run URL: ${run.url}")
            appendLine("Source commit: ${run.headSha}")
            appendLine("Conclusion: ${run.conclusion}")
            appendLine()
            appendLine("Failed steps and job status:")
            appendLine(summary.ifBlank { "GitHub did not return step details; inspect the run URL." })
            appendLine()
            appendLine("This report describes CI metadata, not the full runner log.")
        }.take(12000)
        getSharedPreferences("bardo_developer", MODE_PRIVATE).edit()
            .putString("latest_ci_diagnostics", report)
            .putString("latest_ci_run_url", run.url)
            .putLong("latest_ci_run_id", run.id)
            .apply()
    }

    private fun renderButtons() {
        if (!::validateButton.isInitialized) return
        val tokenReady = BardoGitHubCredentialStore.hasToken(this)
        val privacyReady = sourceOriginIsTrusted() && candidatePromptPrivacyIsSafe()
        validateButton.isEnabled = !busy && tokenReady && privacyReady && !mainCommitSucceeded && !validationDispatched
        refreshButton.isEnabled = !busy && tokenReady && (validationDispatched || mainCommitDispatched)
        integrateButton.isEnabled = BardoGitHubIntegrationPolicy.MAIN_COMMIT_WORKFLOW_ENABLED &&
            !busy && tokenReady && privacyReady && validationSucceeded && !mainCommitSucceeded && !mainCommitDispatched
        integrateButton.text = if (mainCommitSucceeded) {
            "Candidato integrado en main"
        } else {
            "Autorizar commit directo a main"
        }
    }

    private fun setBusy(value: Boolean, message: String = "") {
        busy = value
        if (message.isNotBlank()) output.text = message
        renderButtons()
    }

    private fun key(suffix: String) = "$candidateId:$suffix"

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_BASE_SHA = "bardo_candidate_base_sha"
        const val EXTRA_CANDIDATE_ID = "bardo_candidate_id"
        const val EXTRA_PATCH = "bardo_candidate_patch"
        const val EXTRA_CHANGED_FILES = "bardo_candidate_changed_files"
    }
}
