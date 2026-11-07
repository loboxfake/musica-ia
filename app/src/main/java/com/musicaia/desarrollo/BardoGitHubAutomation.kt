package com.musicaia.desarrollo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
    private val protectedPaths = setOf(
        "app/src/main/java/com/musicaia/MainActivity.kt",
        "app/src/main/java/com/musicaia/BardoDeveloperActivity.kt",
        "app/src/main/java/com/musicaia/BardoGitHubDevelopmentActivity.kt",
        "app/src/main/java/com/musicaia/desarrollo/BardoDeveloperCore.kt",
        "app/src/main/java/com/musicaia/desarrollo/BardoDeveloperWorker.kt",
        "app/src/main/java/com/musicaia/desarrollo/BardoPatchApplier.kt",
        "app/src/main/java/com/musicaia/desarrollo/BardoGitHubAutomation.kt",
        "app/src/main/java/com/musicaia/modelo/BardoCapabilityRegistry.kt",
        "app/src/main/java/com/musicaia/modelo/BardoIntentRouter.kt",
        "app/src/main/java/com/musicaia/modelo/BardoResourceBudget.kt",
        "app/src/main/java/com/musicaia/modelo/Orquestador.kt",
        "app/src/main/java/com/musicaia/modelo/Catalogo.kt",
        "app/src/main/java/com/musicaia/modelo/ManifiestoModelo.kt",
        "app/src/main/java/com/musicaia/modulos/litert/EngineLiteRT.kt",
        "app/src/main/java/com/musicaia/modulos/litert/LiteRTLMModule.kt",
        "app/src/main/AndroidManifest.xml",
        "app/build.gradle.kts",
        "build.gradle.kts",
        "settings.gradle.kts",
        "gradle/wrapper/gradle-wrapper.properties"
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
        require(paths.isNotEmpty() && paths.size <= 30) { "El candidato debe modificar entre 1 y 30 archivos." }
        require(paths.distinct().size == paths.size) { "El candidato contiene rutas duplicadas." }
        paths.forEach { raw ->
            val path = raw.replace('\\', '/')
            require(path == raw && path.isNotBlank() && !path.startsWith("/") &&
                !path.contains(':') && path.split('/').none { it.isBlank() || it == "." || it == ".." }) {
                "Ruta no permitida en el candidato: $raw"
            }
            require(allowedPrefixes.any { path.startsWith(it) }) {
                "Solo se validan cambios en código, pruebas, recursos y documentación: $path"
            }
            require(path !in protectedPaths &&
                !path.endsWith("Modo3MezclaVozUsuario.kt", ignoreCase = true) &&
                !path.startsWith(".github/") && !path.startsWith("gradle/")) {
                "Archivo protegido por BARDO: $path"
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

/** Minimal GitHub REST client. It dispatches repository workflows; the app never embeds a token. */
class BardoGitHubActionsClient(private val token: String) {
    companion object {
        const val VALIDATION_WORKFLOW = "bardo-candidate-validation.yml"
        const val COMMIT_WORKFLOW = "bardo-commit-candidate.yml"
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

    fun dispatchIntegration(baseSha: String, candidateId: String, packedPatch: String, validationRunId: Long) {
        require(validationRunId > 0L) { "Falta la ejecución de validación aprobada." }
        dispatch(
            COMMIT_WORKFLOW,
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
            COMMIT_WORKFLOW -> "BARDO approved integration"
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
