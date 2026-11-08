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
import com.musicaia.desarrollo.BardoGitHubPolicy
import com.musicaia.desarrollo.BardoGitHubRun
import java.util.concurrent.Executors

/** GitHub CI bridge: validates isolated diffs and integrates only after an explicit in-app approval. */
class BardoGitHubDevelopmentActivity : AppCompatActivity() {
    private lateinit var output: TextView
    private lateinit var tokenInput: EditText
    private lateinit var validateButton: Button
    private lateinit var refreshButton: Button
    private lateinit var commitButton: Button
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "BARDO-GitHub-CI").apply { isDaemon = true }
    }
    private val state by lazy { getSharedPreferences("bardo_github_candidate", MODE_PRIVATE) }

    private var baseSha = ""
    private var candidateId = ""
    private var patch = ""
    private var changedFiles: List<String> = emptyList()
    private var validationRunId = 0L
    private var commitRunId = 0L
    private var validationDispatched = false
    private var validationSucceeded = false
    private var commitDispatched = false
    private var integrated = false
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
        commitRunId = state.getLong(key("commit_run_id"), 0L)
        validationDispatched = state.getBoolean(key("validation_dispatched"), validationRunId > 0L)
        validationSucceeded = state.getBoolean(key("validation_succeeded"), false)
        commitDispatched = state.getBoolean(key("commit_dispatched"), commitRunId > 0L)
        integrated = state.getBoolean(key("integrated"), false)

        buildUi()
        if (!valid) {
            output.text = "CANDIDATO NO VÁLIDO\n\nEste proyecto no contiene el SHA de origen o la propuesta modifica una ruta protegida. No se ha enviado nada a GitHub."
            validateButton.isEnabled = false
            commitButton.isEnabled = false
            return
        }

        output.text = buildString {
            appendLine("BARDO · DESARROLLO AUTÓNOMO CON GITHUB ACTIONS")
            appendLine()
            appendLine("Repositorio: ${BardoGitHubPolicy.REPOSITORY}")
            appendLine("Rama única: main")
            appendLine("Commit base: $baseSha")
            appendLine("Candidato: $candidateId")
            appendLine()
            appendLine("Archivos propuestos:")
            changedFiles.forEach { appendLine("• $it") }
            appendLine()
            appendLine("Token requerido: Fine-grained PAT limitado a este repositorio, con Actions: Read and write y Contents: Read.")
            appendLine("El token se cifra con Android Keystore. No se incluye en el APK ni se transmite al runner.")
            appendLine("El runner no recibe secretos ni permisos para escribir en el repositorio.")
            appendLine("La integración en main requiere una aprobación independiente en esta pantalla.")
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
            text = "VALIDACIÓN EN RUNNER AISLADO · SIN RAMAS PARALELAS · SIN TOKEN DENTRO DEL APK"
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
        commitButton = addButton(root, "Aprobar e integrar en main") { confirmIntegration() }
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
                val integration = if (commitRunId > 0L) client.getRun(commitRunId) else
                    client.findRun(BardoGitHubActionsClient.COMMIT_WORKFLOW, candidateId, baseSha)
                if (validation != null && validationRunId == 0L) {
                    validationRunId = validation.id
                    state.edit().putLong(key("validation_run_id"), validation.id).apply()
                }
                if (integration != null && commitRunId == 0L) {
                    commitRunId = integration.id
                    state.edit().putLong(key("commit_run_id"), integration.id).apply()
                }
                val validationSummary = validation?.let { client.jobsSummary(it.id) }.orEmpty()
                val integrationSummary = integration?.let { client.jobsSummary(it.id) }.orEmpty()
                Triple(validation, integration, validationSummary to integrationSummary)
            }
            runOnUiThread {
                result.onSuccess { (validation, integration, summaries) ->
                    if (integration != null) {
                        showRun("INTEGRACIÓN APROBADA", integration, summaries.second)
                        if (integration.succeeded) checkIntegratedCommit(token)
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
        if (!validationSucceeded || validationRunId <= 0L || integrated || commitDispatched) {
            output.text = "Solo se puede integrar un candidato cuya validación haya terminado en SUCCESS."
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Aprobar integración en main")
            .setMessage("BARDO volverá a ejecutar tests y compilación antes del commit. Si pasan, creará un commit fast-forward directamente en main y solicitará la APK integral. No se crearán ramas. ¿Apruebas?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Aprobar e integrar") { _, _ -> dispatchIntegration() }
            .show()
    }

    private fun dispatchIntegration() {
        if (busy || commitDispatched) return
        val token = BardoGitHubCredentialStore.read(this)
        if (token == null) {
            output.text = "No hay un token GitHub guardado."
            return
        }
        val payload = runCatching { BardoGitHubPolicy.packPatch(patch) }.getOrElse {
            output.text = "No se pudo empaquetar la propuesta: ${it.message}"
            return
        }
        setBusy(true, "Integración aprobada. GitHub vuelve a ejecutar las pruebas antes del commit.")
        executor.execute {
            val result = runCatching {
                val client = BardoGitHubActionsClient(token)
                val validation = client.getRun(validationRunId)
                check(validation.succeeded && validation.headSha.equals(baseSha, true)) {
                    "La validación no corresponde a este commit o no terminó en SUCCESS."
                }
                client.dispatchIntegration(baseSha, candidateId, payload, validationRunId)
                var found: BardoGitHubRun? = null
                repeat(20) {
                    if (found == null) {
                        found = client.findRun(BardoGitHubActionsClient.COMMIT_WORKFLOW, candidateId, baseSha)
                        if (found == null) Thread.sleep(1500L)
                    }
                }
                val summary = found?.let { client.jobsSummary(it.id) }.orEmpty()
                found to summary
            }
            runOnUiThread {
                result.onSuccess { (run, summary) ->
                    commitDispatched = true
                    state.edit().putBoolean(key("commit_dispatched"), true).apply()
                    if (run == null) {
                        output.text = "GitHub aceptó la integración. La ejecución todavía no aparece; pulsa «Consultar CI» para recuperarla."
                    } else {
                        commitRunId = run.id
                        state.edit().putLong(key("commit_run_id"), run.id).apply()
                        showRun("INTEGRACIÓN APROBADA", run, summary)
                    }
                }.onFailure { output.text = "No se pudo iniciar la integración: ${it.message}" }
                setBusy(false)
            }
        }
    }

    private fun checkIntegratedCommit(token: String) {
        executor.execute {
            val result = runCatching {
                val client = BardoGitHubActionsClient(token)
                var commit: Pair<String, String>? = null
                repeat(10) {
                    if (commit == null) {
                        commit = client.integratedCommit(candidateId)
                        if (commit == null) Thread.sleep(1500L)
                    }
                }
                val build = commit?.let { client.fullBuildForCommit(it.first) }
                val buildSummary = build?.let { client.jobsSummary(it.id) }.orEmpty()
                Triple(commit, build, buildSummary)
            }
            runOnUiThread {
                result.onSuccess { (commit, build, buildSummary) ->
                    if (commit != null) {
                        integrated = true
                        state.edit().putBoolean(key("integrated"), true).apply()
                        output.append(
                            "\n\nCOMMIT INTEGRADO EN MAIN\n${commit.first}\n${commit.second}" +
                                if (build != null) "\n\nBUILD INTEGRAL: ${build.status.uppercase()} · ${build.conclusion ?: "pendiente"}\n${build.url}\n\nDETALLE DE CI:\n${buildSummary.ifBlank { "GitHub todavía no ha publicado los pasos del build." }}" else
                                    "\n\nEl commit está en main. La ejecución integral todavía puede tardar en aparecer."
                        )
                        android.text.util.Linkify.addLinks(output, android.text.util.Linkify.WEB_URLS)
                    }
                    renderButtons()
                }.onFailure { output.append("\nNo se pudo consultar el commit/build: ${it.message}") }
            }
        }
    }

    private fun showRun(label: String, run: BardoGitHubRun, jobSummary: String = "") {
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
            if (run.conclusion == "failure") appendLine("\nLa ejecución falló. No se integró ningún cambio. Revisa el paso indicado y prepara un nuevo candidato después de corregirlo.")
            if (run.succeeded && label.contains("VALIDACIÓN")) {
                appendLine("\nVALIDACIÓN SUPERADA. La propuesta sigue sin integrarse. Debes aprobar el segundo workflow.")
            }
            if (run.succeeded && label.contains("INTEGRACIÓN")) {
                appendLine("\nIntegración terminada. Consultando commit en main y APK integral…")
            }
        }
        android.text.util.Linkify.addLinks(output, android.text.util.Linkify.WEB_URLS)
        renderButtons()
    }

    private fun renderButtons() {
        if (!::validateButton.isInitialized) return
        val tokenReady = BardoGitHubCredentialStore.hasToken(this)
        validateButton.isEnabled = !busy && tokenReady && !integrated && !validationDispatched
        refreshButton.isEnabled = !busy && tokenReady && (validationDispatched || commitDispatched)
        commitButton.isEnabled = !busy && tokenReady && validationSucceeded && !integrated && !commitDispatched
        commitButton.text = if (integrated) "Candidato ya integrado" else "Aprobar e integrar en main"
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
