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
import com.musicaia.desarrollo.BardoGitHubRun
import java.util.concurrent.Executors

/** GitHub CI bridge: validates isolated diffs and prepares a draft PR only after explicit user approval. */
class BardoGitHubDevelopmentActivity : AppCompatActivity() {
    private lateinit var output: TextView
    private lateinit var tokenInput: EditText
    private lateinit var validateButton: Button
    private lateinit var refreshButton: Button
    private lateinit var prButton: Button
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "BARDO-GitHub-CI").apply { isDaemon = true }
    }
    private val state by lazy { getSharedPreferences("bardo_github_candidate", MODE_PRIVATE) }

    private var baseSha = ""
    private var candidateId = ""
    private var patch = ""
    private var changedFiles: List<String> = emptyList()
    private var validationRunId = 0L
    private var prRunId = 0L
    private var validationDispatched = false
    private var validationSucceeded = false
    private var prDispatched = false
    private var prCreated = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        baseSha = intent.getStringExtra(EXTRA_BASE_SHA).orEmpty()
        candidateId = intent.getStringExtra(EXTRA_CANDIDATE_ID).orEmpty()
        patch = intent.getStringExtra(EXTRA_PATCH).orEmpty()
        changedFiles = intent.getStringArrayListExtra(EXTRA_CHANGED_FILES).orEmpty()

        val valid = runCatching {
            BardoGitHubPolicy.validateBaseSha(baseSha)
            BardoGitHubPolicy.validateCandidateId(candidateId)
            BardoGitHubPolicy.validatePatch(patch)
            BardoGitHubPolicy.validateChangedPaths(changedFiles)
        }.isSuccess

        validationRunId = state.getLong(key("validation_run_id"), 0L)
        prRunId = state.getLong(key("pr_run_id"), 0L)
        validationDispatched = state.getBoolean(key("validation_dispatched"), validationRunId > 0L)
        validationSucceeded = state.getBoolean(key("validation_succeeded"), false)
        prDispatched = state.getBoolean(key("pr_dispatched"), prRunId > 0L)
        prCreated = state.getBoolean(key("pr_created"), false)

        buildUi()
        if (!valid) {
            output.text = "CANDIDATO NO VÁLIDO\n\nEste proyecto no contiene el SHA de origen o la propuesta modifica una ruta protegida. No se ha enviado nada a GitHub."
            validateButton.isEnabled = false
            prButton.isEnabled = false
            return
        }

        output.text = buildString {
            appendLine("BARDO · DESARROLLO AUTÓNOMO CON GITHUB ACTIONS")
            appendLine()
            appendLine("Repositorio: ${BardoGitHubPolicy.REPOSITORY}")
            appendLine("Rama base: main")
            appendLine("Rama candidata: bardo/$candidateId")
            appendLine("Commit base: $baseSha")
            appendLine("Candidato: $candidateId")
            appendLine()
            appendLine("Archivos propuestos:")
            changedFiles.forEach { appendLine("• $it") }
            appendLine()
            appendLine("Token requerido: Fine-grained PAT limitado a este repositorio, con Actions: Read and write, Contents: Read y Pull requests: Read.")
            appendLine("El token se cifra con Android Keystore. No se incluye en el APK ni se transmite al runner.")
            appendLine()
            if (BardoGitHubIntegrationPolicy.DRAFT_PR_WORKFLOW_ENABLED) {
                appendLine("INTEGRACIÓN SEGURA DISPONIBLE: tras la confirmación explícita, GitHub repetirá la validación y los tests, creará una rama bardo/* y abrirá un PR borrador.")
                appendLine("No se hace push a main, no se fusiona el PR y no se publica una Release. La revisión y la fusión quedan en manos del usuario.")
            } else {
                appendLine("INTEGRACIÓN BLOQUEADA: " + BardoGitHubIntegrationPolicy.BLOCK_REASON)
            }
            appendLine("La validación y la preparación del PR se ejecutan en runners efímeros. El token personal permanece en el dispositivo; el workflow utiliza su GITHUB_TOKEN limitado por permisos.")
        }
        renderButtons()
    }

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
            text = "VALIDACIÓN AISLADA · RAMA bardo/* · PR BORRADOR · SIN TOKEN DENTRO DEL APK"
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
        prButton = addButton(root, "Crear PR del candidato") { confirmIntegration() }
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
                        showRun("VALIDACIÓN DEL CANDIDATO", run, summary)
                    }
                }.onFailure { output.text = "No se pudo enviar el candidato: ${it.message}" }
                setBusy(false)
            }
        }
    }

    private fun refreshRuns() {
        if (busy) return
        val token = BardoGitHubCredentialStore.read(this)
        if (token == null) {
            output.text = "No hay un token GitHub guardado."
            return
        }
        setBusy(true, "Consultando el estado real de las ejecuciones en GitHub Actions…")
        executor.execute {
            val result = runCatching {
                val client = BardoGitHubActionsClient(token)
                val validation = if (validationRunId > 0L) client.getRun(validationRunId) else
                    client.findRun(BardoGitHubActionsClient.VALIDATION_WORKFLOW, candidateId, baseSha)
                val prPreparation = if (BardoGitHubIntegrationPolicy.DRAFT_PR_WORKFLOW_ENABLED) {
                    if (prRunId > 0L) client.getRun(prRunId) else
                        client.findRun(BardoGitHubActionsClient.PR_WORKFLOW, candidateId, baseSha)
                } else null
                if (validation != null && validationRunId == 0L) {
                    validationRunId = validation.id
                    state.edit().putLong(key("validation_run_id"), validation.id).apply()
                }
                if (prPreparation != null && prRunId == 0L) {
                    prRunId = prPreparation.id
                    state.edit().putLong(key("pr_run_id"), prPreparation.id).apply()
                }
                val validationSummary = validation?.let { client.jobsSummary(it.id) }.orEmpty()
                val prSummary = prPreparation?.let { client.jobsSummary(it.id) }.orEmpty()
                Triple(validation, prPreparation, validationSummary to prSummary)
            }
            runOnUiThread {
                result.onSuccess { (validation, prPreparation, summaries) ->
                    if (prPreparation != null) {
                        showRun("PREPARACIÓN DEL PR", prPreparation, summaries.second)
                        if (prPreparation.succeeded) checkPullRequest(token)
                    } else if (validation != null) {
                        showRun("VALIDACIÓN DEL CANDIDATO", validation, summaries.first)
                        if (validation.succeeded) {
                            validationSucceeded = true
                            state.edit().putBoolean(key("validation_succeeded"), true).apply()
                        }
                    } else {
                        output.text = "No se encontró una ejecución para este candidato. Primero envíalo a validación."
                    }
                }.onFailure { output.text = "No se pudo consultar GitHub: ${it.message}" }
                setBusy(false)
            }
        }
    }

    private fun confirmIntegration() {
        if (!BardoGitHubIntegrationPolicy.DRAFT_PR_WORKFLOW_ENABLED) {
            output.text = BardoGitHubIntegrationPolicy.BLOCK_REASON
            return
        }
        if (!validationSucceeded || validationRunId <= 0L || prCreated || prDispatched) {
            output.text = "Solo se puede preparar un PR para un candidato cuya validación haya terminado en SUCCESS."
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Crear PR del candidato")
            .setMessage("BARDO volverá a ejecutar las pruebas y compilación. Si pasan, creará la rama bardo/$candidateId y abrirá un PR borrador contra main. No modificará main, no fusionará el PR ni publicará una APK. ¿Apruebas crear el PR?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Crear PR") { _, _ -> dispatchIntegration() }
            .show()
    }

    private fun dispatchIntegration() {
        if (!BardoGitHubIntegrationPolicy.DRAFT_PR_WORKFLOW_ENABLED) {
            output.text = BardoGitHubIntegrationPolicy.BLOCK_REASON
            return
        }
        if (busy || prDispatched || prCreated) return
        val token = BardoGitHubCredentialStore.read(this)
        if (token == null) {
            output.text = "No hay un token GitHub guardado."
            return
        }
        val payload = runCatching { BardoGitHubPolicy.packPatch(patch) }.getOrElse {
            output.text = "No se pudo empaquetar la propuesta: ${it.message}"
            return
        }
        setBusy(true, "Aprobación recibida. GitHub volverá a probar el candidato y abrirá un PR sin tocar main.")
        executor.execute {
            val result = runCatching {
                val client = BardoGitHubActionsClient(token)
                val validation = client.getRun(validationRunId)
                check(validation.succeeded && validation.headSha.equals(baseSha, true)) {
                    "La validación no corresponde a este commit o no terminó en SUCCESS."
                }
                client.dispatchPullRequest(baseSha, candidateId, payload, validationRunId)
                var found: BardoGitHubRun? = null
                repeat(20) {
                    if (found == null) {
                        found = client.findRun(BardoGitHubActionsClient.PR_WORKFLOW, candidateId, baseSha)
                        if (found == null) Thread.sleep(1500L)
                    }
                }
                val summary = found?.let { client.jobsSummary(it.id) }.orEmpty()
                found to summary
            }
            runOnUiThread {
                result.onSuccess { (run, summary) ->
                    prDispatched = true
                    state.edit().putBoolean(key("pr_dispatched"), true).apply()
                    if (run == null) {
                        output.text = "GitHub aceptó la solicitud. La ejecución aún no aparece; pulsa «Consultar CI y resultado» para recuperarla."
                    } else {
                        prRunId = run.id
                        state.edit().putLong(key("pr_run_id"), run.id).apply()
                        showRun("PREPARACIÓN DEL PR", run, summary)
                        if (run.succeeded) checkPullRequest(token)
                    }
                }.onFailure { output.text = "No se pudo iniciar la preparación del PR: ${it.message}" }
                setBusy(false)
            }
        }
    }

    private fun checkPullRequest(token: String) {
        executor.execute {
            val result = runCatching {
                val client = BardoGitHubActionsClient(token)
                var pullRequest: Pair<Int, String>? = null
                repeat(10) {
                    if (pullRequest == null) {
                        pullRequest = client.pullRequestForCandidate(candidateId)
                        if (pullRequest == null) Thread.sleep(1500L)
                    }
                }
                pullRequest
            }
            runOnUiThread {
                result.onSuccess { pullRequest ->
                    if (pullRequest != null) {
                        prCreated = true
                        state.edit().putBoolean(key("pr_created"), true).apply()
                        output.append(
                            "\n\nPR BORRADOR CREADA\nNúmero: #${pullRequest.first}\n${pullRequest.second}" +
                                "\n\nmain sigue sin cambios. Revisa el diff y los checks del PR. BARDO no lo fusionará ni publicará una APK."
                        )
                        android.text.util.Linkify.addLinks(output, android.text.util.Linkify.WEB_URLS)
                    } else {
                        output.append(
                            "\n\nEl workflow terminó, pero la PR aún no aparece en la consulta de GitHub. Pulsa «Consultar CI y resultado» de nuevo. main no se ha modificado por esta pantalla."
                        )
                    }
                    renderButtons()
                }.onFailure {
                    output.append("\nNo se pudo consultar la PR: ${it.message}")
                }
            }
        }
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
            if (run.conclusion == "failure") appendLine("\nLa ejecución falló. No se creó ningún PR ni se modificó main. Corrige el problema y prepara un candidato nuevo.")
            if (run.succeeded && label.contains("VALIDACIÓN")) {
                appendLine("\nVALIDACIÓN SUPERADA. La propuesta sigue sin publicarse. Puedes autorizar por separado la creación de un PR borrador.")
            }
            if (run.succeeded && label.contains("PR")) {
                appendLine("\nPreparación del PR terminada. Consultando la rama candidata y el PR; main no se fusiona automáticamente.")
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
        validateButton.isEnabled = !busy && tokenReady && !prCreated && !validationDispatched
        refreshButton.isEnabled = !busy && tokenReady && (validationDispatched || prDispatched)
        prButton.isEnabled = BardoGitHubIntegrationPolicy.DRAFT_PR_WORKFLOW_ENABLED &&
            !busy && tokenReady && validationSucceeded && !prCreated && !prDispatched
        prButton.text = when {
            prCreated -> "PR borrador creado"
            !BardoGitHubIntegrationPolicy.DRAFT_PR_WORKFLOW_ENABLED -> "PR bloqueado: workflow inseguro"
            else -> "Crear PR del candidato"
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
