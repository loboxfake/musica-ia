package com.musicaia.desarrollo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.musicaia.evaluacion.BardoCandidateEvaluation
import com.musicaia.evaluacion.BardoCandidateEvaluationPipeline
import com.musicaia.evaluacion.BardoCheckEvidence
import com.musicaia.evaluacion.BardoLocalCandidateEvidenceCollector
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.UUID
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Keeps the repository-scoped GitHub token encrypted with a non-exportable Android Keystore key. */
object BardoGitHubCredentialStore {
    private const val PREFS = "bardo_github_credentials"
    private const val KEY_ALIAS = "bardo_github_actions_pat_v1"
    private const val IV_KEY = "iv"
    private const val DATA_KEY = "ciphertext"

    fun hasToken(context: Context): Boolean = read(context) != null

    fun save(context: Context, tokenValue: String) {
        val token = tokenValue.trim()
        require(token.startsWith("github_pat_") && token.length >= 40 && token.length <= 512) {
            "Introduce un token fine-grained de GitHub (prefijo github_pat_)."
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(DATA_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit()
        check(saved) { "Android no pudo guardar la credencial cifrada." }
    }

    fun read(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ivText = prefs.getString(IV_KEY, null) ?: return null
        val dataText = prefs.getString(DATA_KEY, null) ?: return null
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                javax.crypto.spec.GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(dataText, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            clear(context)
            null
        }
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}

/** Shared local policy; the two CI workflows enforce the same rules again on the runner. */
object BardoGitHubPolicy {
    const val REPOSITORY = "loboxfake/musica-ia"
    const val MAX_PATCH_BYTES = 384 * 1024
    const val MAX_PACKED_PATCH_CHARS = 60_000
    private val shaPattern = Regex("^[0-9a-fA-F]{40}$")
    private val candidatePattern = Regex("^[0-9a-f]{32}$")
    private val allowedPrefixes = listOf(
        "app/src/main/java/",
        "app/src/test/java/",
        "app/src/main/res/",
        "docs/"
    )


    fun newCandidateId(): String = UUID.randomUUID().toString().replace("-", "")

    fun validateBaseSha(value: String) {
        require(shaPattern.matches(value)) { "El snapshot no contiene un SHA de Git válido." }
    }

    fun validateCandidateId(value: String) {
        require(candidatePattern.matches(value)) { "Identificador de candidato inválido." }
    }

    fun validatePatch(patch: String) {
        val bytes = patch.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_PATCH_BYTES) {
            "La propuesta supera el límite seguro de 384 KB."
        }
        require(patch.contains("--- a/") && patch.contains("+++ b/")) {
            "La propuesta no contiene un diff unificado válido."
        }
    }

    fun validateChangedPaths(paths: List<String>) {
        require(paths.isNotEmpty() && paths.size <= 30) {
            "El candidato debe modificar entre 1 y 30 archivos."
        }
        require(paths.distinct().size == paths.size) {
            "El candidato contiene rutas duplicadas."
        }
        paths.forEach { raw ->
            val path = BardoProtectedPathPolicy.normalize(raw)
                ?: throw IllegalArgumentException("Ruta no permitida en el candidato: $raw")
            require(allowedPrefixes.any { path.startsWith(it) }) {
                "Solo se validan cambios en código, pruebas, recursos y documentación: $path"
            }
            require(
                BardoProtectedPathPolicy.classify(path) !in setOf(
                    BardoProtectedPathPolicy.PathClass.HUMAN_REVIEW_REQUIRED,
                    BardoProtectedPathPolicy.PathClass.FORBIDDEN
                )
            ) {
                "Archivo protegido por BARDO: $path"
            }
        }
    }

    /** Más estricta que la validación genérica: bloquea también cambios sobre pruebas existentes. */
    fun validateAutonomousChangedPaths(paths: List<String>) {
        validateChangedPaths(paths)
        paths.forEach { path ->
            require(BardoProtectedPathPolicy.canModifyExisting(path)) {
                "BARDO no puede modificar pruebas existentes ni rutas de revisión humana: $path"
            }
        }
    }

    fun packPatch(patch: String): String {
        validatePatch(patch)
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(patch.toByteArray(Charsets.UTF_8)) }
        val packed = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        require(packed.length <= MAX_PACKED_PATCH_CHARS) {
            "El diff comprimido supera el límite de GitHub Actions."
        }
        return packed
    }
}

data class BardoGitHubRun(
    val id: Long,
    val status: String,
    val conclusion: String?,
    val title: String,
    val url: String,
    val headSha: String,
    val createdAt: String
) {
    val finished: Boolean get() = status == "completed"
    val succeeded: Boolean get() = finished && conclusion == "success"
}

data class BardoGitHubPullRequest(
    val number: Int,
    val url: String,
    val headSha: String
)

data class BardoGitHubBranchEvaluation(
    val pullRequest: BardoGitHubPullRequest,
    val validationRun: BardoGitHubRun?,
    val evaluation: BardoCandidateEvaluation?
)

/** Minimal GitHub REST client. It dispatches repository workflows; the app never embeds a token. */
class BardoGitHubActionsClient(private val token: String) {
    companion object {
        const val VALIDATION_WORKFLOW = "bardo-candidate-validation.yml"
        const val PR_WORKFLOW = "bardo-commit-candidate.yml"
        const val FULL_BUILD_WORKFLOW = "full-build.yml"
        private const val API_ROOT = "https://api.github.com/repos/loboxfake/musica-ia"
    }

    init {
        require(token.startsWith("github_pat_")) { "Se requiere un token fine-grained de GitHub." }
    }

    fun dispatchValidation(baseSha: String, candidateId: String, packedPatch: String) {
        dispatch(
            VALIDATION_WORKFLOW,
            baseSha,
            candidateId,
            packedPatch,
            null
        )
    }

    fun dispatchPullRequest(baseSha: String, candidateId: String, packedPatch: String, validationRunId: Long) {
        BardoGitHubIntegrationPolicy.requireDraftPrWorkflowAvailable()
        require(validationRunId > 0L) { "Falta la ejecución de validación aprobada." }
        dispatch(
            PR_WORKFLOW,
            baseSha,
            candidateId,
            packedPatch,
            validationRunId
        )
    }

    private fun dispatch(
        workflow: String,
        baseSha: String,
        candidateId: String,
        packedPatch: String,
        validationRunId: Long?
    ) {
        BardoGitHubPolicy.validateBaseSha(baseSha)
        BardoGitHubPolicy.validateCandidateId(candidateId)
        require(packedPatch.length in 1..BardoGitHubPolicy.MAX_PACKED_PATCH_CHARS)
        val inputs = JSONObject()
            .put("candidate_id", candidateId)
            .put("base_sha", baseSha)
            .put("patch_gzip_b64", packedPatch)
        if (validationRunId != null) inputs.put("validation_run_id", validationRunId.toString())
        val body = JSONObject().put("ref", "main").put("inputs", inputs)
        request("POST", "/actions/workflows/$workflow/dispatches", body, setOf(204))
    }

    fun findRun(workflow: String, candidateId: String, baseSha: String): BardoGitHubRun? {
        if (workflow == PR_WORKFLOW && !BardoGitHubIntegrationPolicy.DRAFT_PR_WORKFLOW_ENABLED) {
            return null
        }
        BardoGitHubPolicy.validateCandidateId(candidateId)
        BardoGitHubPolicy.validateBaseSha(baseSha)
        val response = request(
            "GET",
            "/actions/workflows/$workflow/runs?branch=main&event=workflow_dispatch&per_page=100",
            null,
            setOf(200)
        )
        val array = JSONObject(response).optJSONArray("workflow_runs") ?: JSONArray()
        val prefix = when (workflow) {
            VALIDATION_WORKFLOW -> "BARDO candidate validation"
            PR_WORKFLOW -> "BARDO approved PR preparation"
            else -> ""
        }
        val matches = mutableListOf<BardoGitHubRun>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val title = item.optString("display_title", "")
            // The candidate ID is unique. Do not filter by head_sha: GitHub records the
            // branch tip, while the submitted base SHA is a workflow input. A stale-base
            // run must still be visible so BARDO can report why CI rejected it.
            if (title.contains(prefix, ignoreCase = true) &&
                title.contains(candidateId, ignoreCase = true)
            ) matches += parseRun(item)
        }
        return matches.maxByOrNull { it.createdAt }
    }

    fun getRun(runId: Long): BardoGitHubRun {
        require(runId > 0L)
        val response = request("GET", "/actions/runs/$runId", null, setOf(200))
        return parseRun(JSONObject(response))
    }

    fun jobsSummary(runId: Long): String {
        val response = request("GET", "/actions/runs/$runId/jobs?per_page=100", null, setOf(200))
        val jobs = JSONObject(response).optJSONArray("jobs") ?: JSONArray()
        val lines = mutableListOf<String>()
        for (i in 0 until jobs.length()) {
            val job = jobs.optJSONObject(i) ?: continue
            lines += "• ${job.optString("name")} — ${job.optString("conclusion", job.optString("status"))}"
            val steps = job.optJSONArray("steps") ?: JSONArray()
            for (j in 0 until steps.length()) {
                val step = steps.optJSONObject(j) ?: continue
                val state = step.optString("conclusion").ifBlank { step.optString("status") }
                if (state == "failure" || state == "in_progress") {
                    lines += "  - ${step.optString("name")}: $state"
                }
            }
        }
        return lines.joinToString("\n").take(6000)
    }

    /** Busca la PR del candidato y vincula su SHA exacto; no modifica ni fusiona main. */
    fun pullRequestDetailsForCandidate(candidateId: String): BardoGitHubPullRequest? {
        BardoGitHubPolicy.validateCandidateId(candidateId)
        val branch = "bardo/$candidateId"
        val head = java.net.URLEncoder.encode("loboxfake:$branch", "UTF-8")
        val response = request(
            "GET",
            "/pulls?state=all&head=$head&base=main&per_page=100",
            null,
            setOf(200)
        )
        val pulls = JSONArray(response)
        for (index in 0 until pulls.length()) {
            val item = pulls.optJSONObject(index) ?: continue
            val headObject = item.optJSONObject("head")
            val headRef = headObject?.optString("ref").orEmpty()
            val baseRef = item.optJSONObject("base")?.optString("ref").orEmpty()
            val number = item.optInt("number")
            val url = item.optString("html_url")
            val headSha = headObject?.optString("sha").orEmpty()
            if (headRef == branch && baseRef == "main" && number > 0 &&
                url == "https://github.com/loboxfake/musica-ia/pull/$number" &&
                Regex("^[0-9a-f]{40}$").matches(headSha)
            ) {
                return BardoGitHubPullRequest(number, url, headSha)
            }
        }
        return null
    }

    /** Compatibilidad con consumidores que solo necesitan número y URL. */
    fun pullRequestForCandidate(candidateId: String): Pair<Int, String>? =
        pullRequestDetailsForCandidate(candidateId)?.let { it.number to it.url }

    /**
     * Obtiene la validación workflow_dispatch de la rama creada por el workflow
     * de PR, exige que siga apuntando al SHA actual del PR y ejecuta el evaluador
     * con las cuatro evidencias locales recalculadas por la aplicación.
     */
    fun evaluateCandidateBranch(
        candidateId: String,
        localEvidence: List<BardoCheckEvidence>
    ): BardoGitHubBranchEvaluation? {
        BardoGitHubPolicy.validateCandidateId(candidateId)
        require(BardoLocalCandidateEvidenceCollector.isReadyForCiSubmission(localEvidence)) {
            "La auditoría local no está completa; no se consultará una aprobación CI."
        }
        val pullRequest = pullRequestDetailsForCandidate(candidateId) ?: return null
        val branch = "bardo/$candidateId"
        val encodedBranch = java.net.URLEncoder.encode(branch, "UTF-8")
        val response = request(
            "GET",
            "/actions/workflows/bardo-validation.yml/runs?branch=$encodedBranch&event=workflow_dispatch&per_page=100",
            null,
            setOf(200)
        )
        val runs = JSONObject(response).optJSONArray("workflow_runs") ?: JSONArray()
        val validation = (0 until runs.length()).mapNotNull { runs.optJSONObject(it) }
            .filter { item ->
                item.optString("event") == "workflow_dispatch" &&
                    item.optString("head_branch") == branch &&
                    item.optString("head_sha").equals(pullRequest.headSha, ignoreCase = true) &&
                    item.optString("path").substringBefore("@") == ".github/workflows/bardo-validation.yml"
            }
            .maxByOrNull { it.optString("created_at") }
            ?: return BardoGitHubBranchEvaluation(pullRequest, null, null)

        val runId = validation.optLong("id", 0L)
        require(runId > 0L) { "La validación de la rama no contiene un ID válido." }
        val runJson = JSONObject(request("GET", "/actions/runs/$runId", null, setOf(200)))
        val jobsJson = JSONObject(request("GET", "/actions/runs/$runId/jobs?per_page=100", null, setOf(200)))
        val artifactsJson = JSONObject(request("GET", "/actions/runs/$runId/artifacts?per_page=100", null, setOf(200)))
        val evaluation = BardoCandidateEvaluationPipeline.evaluate(
            runJson,
            jobsJson,
            artifactsJson,
            pullRequest.headSha,
            localEvidence
        )
        return BardoGitHubBranchEvaluation(
            pullRequest = pullRequest,
            validationRun = parseRun(validation),
            evaluation = evaluation
        )
    }

    fun integratedCommit(candidateId: String): Pair<String, String>? {
        BardoGitHubPolicy.validateCandidateId(candidateId)
        val response = request("GET", "/commits?sha=main&per_page=30", null, setOf(200))
        val commits = JSONArray(response)
        for (i in 0 until commits.length()) {
            val item = commits.optJSONObject(i) ?: continue
            val message = item.optJSONObject("commit")?.optString("message").orEmpty()
            if (message.contains(candidateId, ignoreCase = true)) {
                return item.optString("sha") to item.optString("html_url")
            }
        }
        return null
    }

    fun fullBuildForCommit(commitSha: String): BardoGitHubRun? {
        BardoGitHubPolicy.validateBaseSha(commitSha)
        val response = request(
            "GET",
            "/actions/workflows/$FULL_BUILD_WORKFLOW/runs?branch=main&event=workflow_dispatch&per_page=100",
            null,
            setOf(200)
        )
        val array = JSONObject(response).optJSONArray("workflow_runs") ?: JSONArray()
        val matches = mutableListOf<BardoGitHubRun>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optString("head_sha").equals(commitSha, ignoreCase = true)) matches += parseRun(item)
        }
        return matches.maxByOrNull { it.createdAt }
    }

    /**
     * Downloads an immutable source archive for the current main tip.
     * The fine-grained token is sent only to api.github.com; the codeload
     * redirect is host-checked and is fetched without Authorization.
     */
    fun downloadMainSourceSnapshot(destination: java.io.File): Pair<String, java.io.File> {
        val commitJson = JSONObject(request("GET", "/commits/main", null, setOf(200)))
        val sourceSha = commitJson.optString("sha")
        BardoGitHubPolicy.validateBaseSha(sourceSha)

        val temporary = java.io.File(destination.parentFile ?: throw IOException("Falta carpeta de destino."),
            destination.name + ".part")
        temporary.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "No se pudo crear la carpeta del snapshot." } }
        temporary.delete()

        var apiConnection: HttpURLConnection? = null
        var archiveConnection: HttpURLConnection? = null
        try {
            val archiveEndpoint = URL("$API_ROOT/zipball/$sourceSha")
            apiConnection = (archiveEndpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20000
                readTimeout = 60000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "BARDO-Developer/1.0")
            }
            val apiStatus = apiConnection.responseCode
            val archiveUrl = when (apiStatus) {
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_MOVED_PERM,
                307,
                308 -> {
                    val location = apiConnection.getHeaderField("Location")
                        ?: throw IOException("GitHub no devolvió la URL del archivo fuente.")
                    val redirected = URL(archiveEndpoint, location)
                    require(redirected.protocol.equals("https", true) &&
                        redirected.host.equals("codeload.github.com", true) &&
                        (redirected.port == -1 || redirected.port == 443)) {
                        "GitHub devolvió un destino de descarga no permitido."
                    }
                    redirected
                }
                HttpURLConnection.HTTP_OK -> archiveEndpoint
                else -> throw IOException("GitHub no pudo preparar el snapshot (HTTP $apiStatus).")
            }

            val input = if (apiStatus == HttpURLConnection.HTTP_OK) {
                apiConnection.inputStream
            } else {
                apiConnection.disconnect()
                apiConnection = null
                archiveConnection = (archiveUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 20000
                    readTimeout = 60000
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", "application/zip")
                    setRequestProperty("User-Agent", "BARDO-Developer/1.0")
                    // Never forward the GitHub PAT to codeload.github.com.
                }
                val downloadStatus = archiveConnection.responseCode
                if (downloadStatus != HttpURLConnection.HTTP_OK) {
                    throw IOException("No se pudo descargar el snapshot de GitHub (HTTP $downloadStatus).")
                }
                archiveConnection.inputStream
            }

            input.use { source ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 50L * 1024L * 1024L) {
                            "El ZIP de fuentes supera el límite comprimido de 50 MB."
                        }
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                }
            }
            check(temporary.isFile && temporary.length() > 0L) { "GitHub devolvió un ZIP vacío." }
            if (destination.exists()) check(destination.delete()) { "No se pudo sustituir el snapshot temporal anterior." }
            check(temporary.renameTo(destination)) { "No se pudo publicar el ZIP temporal del snapshot." }
            return sourceSha to destination
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            apiConnection?.disconnect()
            archiveConnection?.disconnect()
        }
    }

    private fun parseRun(item: JSONObject): BardoGitHubRun = BardoGitHubRun(
        id = item.optLong("id"),
        status = item.optString("status"),
        conclusion = item.optString("conclusion").takeIf { it.isNotBlank() && it != "null" },
        title = item.optString("display_title", item.optString("name")),
        url = item.optString("html_url"),
        headSha = item.optString("head_sha"),
        createdAt = item.optString("created_at")
    )

    private fun request(
        method: String,
        path: String,
        body: JSONObject?,
        allowedStatus: Set<Int>
    ): String {
        require(path.startsWith("/") && !path.contains("..")) { "Ruta API no válida." }
        val connection = (URL(API_ROOT + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 25000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "BARDO-Developer/1.0")
        }
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 2 * 1024 * 1024) {
                        throw IOException("La respuesta de GitHub supera el límite de 2 MB.")
                    }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }.orEmpty()
            if (status !in allowedStatus) {
                val safeBody = response.take(1200)
                throw IOException("GitHub API HTTP $status: ${safeBody.ifBlank { "sin detalle" }}")
            }
            return response
        } finally {
            connection.disconnect()
        }
    }
}

object BardoGitHubIntegrationPolicy {
    const val DRAFT_PR_WORKFLOW_ENABLED = true

    const val BLOCK_REASON =
        "Creación de PR disponible: bardo-commit-candidate.yml solo crea una rama bardo/* " +
        "y un PR borrador después de validar la candidatura y repetir los tests. No hace " +
        "merge ni push a main. La aprobación, fusión y publicación permanecen separadas."

    fun requireDraftPrWorkflowAvailable() {
        check(DRAFT_PR_WORKFLOW_ENABLED) { BLOCK_REASON }
    }
}
