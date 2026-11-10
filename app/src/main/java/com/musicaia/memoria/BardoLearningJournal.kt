package com.musicaia.memoria

import com.musicaia.modelos.BardoAgentContribution
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

enum class BardoLearningState { CANDIDATE, VERIFIED, REJECTED }

data class BardoLearningCandidate(
    val id: String,
    val taskId: String,
    val topic: String,
    val providerId: String,
    val providerVersion: String,
    val agentId: String,
    val role: String,
    val content: String,
    val contentSha256: String,
    val state: BardoLearningState,
    val createdAtMillis: Long,
    val verifierId: String? = null,
    val evidenceRefs: List<String> = emptyList(),
    val verificationNote: String? = null
)

data class BardoLearningVerification(
    val passed: Boolean,
    val verifierId: String,
    val evidenceRefs: List<String>,
    val note: String
)

/**
 * Solo un componente de evaluación independiente puede promover conocimiento.
 * La implementación no debe delegar la decisión al mismo modelo que generó el texto.
 */
fun interface BardoLearningVerifier {
    fun verify(candidate: BardoLearningCandidate): BardoLearningVerification
}

/**
 * Memoria de aprendizaje con procedencia y estados explícitos.
 * Las respuestas de modelos empiezan siempre como CANDIDATE y no se devuelven
 * como conocimiento fiable hasta que un verificador independiente aporta
 * evidencia identificable. No modifica los pesos de ningún modelo.
 */
class BardoLearningJournal(
    directory: File,
    private val verifier: BardoLearningVerifier? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()
    private val root: File
    private val journalFile: File

    init {
        if (!directory.exists()) check(directory.mkdirs()) {
            "No se pudo crear el diario de aprendizaje."
        }
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) {
            "El diario debe estar en un directorio normal."
        }
        root = directory.canonicalFile
        journalFile = File(root, JOURNAL_NAME)
        require(!Files.isSymbolicLink(journalFile.toPath())) {
            "El diario no puede ser un enlace simbólico."
        }
    }

    fun recordCandidate(
        taskId: String,
        topic: String,
        contribution: BardoAgentContribution
    ): BardoLearningCandidate = synchronized(lock) {
        require(contribution.succeeded && contribution.content.isNotBlank()) {
            "Solo se guardan como candidatos las respuestas completadas."
        }
        require(taskId.isNotBlank() && taskId.length <= 120)
        require(topic.isNotBlank() && topic.length <= 240)
        require(contribution.content.length <= MAX_CONTENT_CHARS)
        val content = contribution.content.trim()
        val candidate = BardoLearningCandidate(
            id = UUID.randomUUID().toString(),
            taskId = taskId,
            topic = topic.take(240),
            providerId = contribution.providerId,
            providerVersion = contribution.providerVersion,
            agentId = contribution.agentId,
            role = contribution.role.name,
            content = content,
            contentSha256 = sha256(content.toByteArray(Charsets.UTF_8)),
            state = BardoLearningState.CANDIDATE,
            createdAtMillis = nowMillis()
        )
        append(eventJson("candidate", candidate))
        candidate
    }

    /**
     * Verifica el candidato mediante una dependencia de confianza del núcleo.
     * Sin verificador no existe ruta para promover una respuesta de modelo.
     */
    fun verifyAndPromote(candidateId: String): BardoLearningCandidate = synchronized(lock) {
        require(candidateId.matches(UUID_PATTERN)) { "ID de aprendizaje no válido." }
        val activeVerifier = verifier
            ?: throw IllegalStateException("No hay un evaluador independiente conectado.")
        val candidate = readLatest().firstOrNull { it.id == candidateId }
            ?: throw IllegalArgumentException("No existe el candidato solicitado.")
        require(candidate.state == BardoLearningState.CANDIDATE) {
            "Solo se pueden verificar candidatos pendientes."
        }
        val verification = activeVerifier.verify(candidate)
        require(verification.verifierId.matches(ID_PATTERN)) {
            "El evaluador no tiene un identificador válido."
        }
        require(verification.evidenceRefs.isNotEmpty() &&
            verification.evidenceRefs.size <= MAX_EVIDENCE_REFS &&
            verification.evidenceRefs.all { it.isNotBlank() && it.length <= 1000 }) {
            "La verificación debe aportar referencias de evidencia."
        }
        require(verification.note.isNotBlank() && verification.note.length <= 2000)
        val updated = candidate.copy(
            state = if (verification.passed) BardoLearningState.VERIFIED else BardoLearningState.REJECTED,
            verifierId = verification.verifierId,
            evidenceRefs = verification.evidenceRefs.distinct(),
            verificationNote = verification.note.take(2000)
        )
        append(eventJson("verification", updated))
        updated
    }

    fun listCandidates(): List<BardoLearningCandidate> = synchronized(lock) {
        readLatest().filter { it.state == BardoLearningState.CANDIDATE }
            .sortedByDescending { it.createdAtMillis }
    }

    /** La recuperación ordinaria devuelve únicamente conocimientos promovidos. */
    fun searchVerified(query: String, maxChars: Int = 12_000): String = synchronized(lock) {
        require(maxChars in 1..MAX_SEARCH_CHARS)
        val terms = query.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }
            .toSet()
        if (terms.isEmpty()) return@synchronized ""
        val matches = readLatest()
            .filter { it.state == BardoLearningState.VERIFIED }
            .mapNotNull { candidate ->
                val haystack = (candidate.topic + " " + candidate.content).lowercase()
                val score = terms.count { haystack.contains(it) }
                if (score == 0) null else candidate to score
            }
            .sortedByDescending { it.second }
        buildString {
            for ((candidate, score) in matches) {
                if (length >= maxChars) break
                val header = "\n[Lección verificada: " + candidate.topic +
                    " · proveedor: " + candidate.providerId +
                    " · versión: " + candidate.providerVersion +
                    " · evaluación: " + candidate.verifierId +
                    " · coincidencias: " + score + "]\n"
                val room = maxChars - length - header.length
                if (room > 0) {
                    append(header)
                    append(candidate.content.take(room))
                }
            }
        }
    }

    private fun readLatest(): List<BardoLearningCandidate> {
        if (!journalFile.exists()) return emptyList()
        require(journalFile.isFile && !Files.isSymbolicLink(journalFile.toPath())) {
            "El diario de aprendizaje no es un archivo regular."
        }
        require(journalFile.length() <= MAX_JOURNAL_BYTES) {
            "El diario de aprendizaje superó el límite de tamaño."
        }
        val latest = linkedMapOf<String, BardoLearningCandidate>()
        journalFile.forEachLine(Charsets.UTF_8) { line ->
            if (line.isBlank()) return@forEachLine
            val json = JSONObject(line)
            val candidate = fromJson(json)
            require(candidate.contentSha256 == sha256(candidate.content.toByteArray(Charsets.UTF_8))) {
                "La integridad de una lección no coincide con su SHA-256."
            }
            latest[candidate.id] = candidate
        }
        return latest.values.toList()
    }

    private fun append(json: JSONObject) {
        require(root.canonicalFile == journalFile.canonicalFile.parentFile)
        require(!Files.isSymbolicLink(journalFile.toPath()))
        require(journalFile.length() <= MAX_JOURNAL_BYTES - MAX_EVENT_BYTES) {
            "El diario de aprendizaje alcanzó su límite; se bloquea la escritura."
        }
        FileOutputStream(journalFile, true).use { output ->
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            output.write('\n'.code)
            output.flush()
            output.fd.sync()
        }
    }

    private fun eventJson(event: String, candidate: BardoLearningCandidate): JSONObject =
        JSONObject()
            .put("event", event)
            .put("id", candidate.id)
            .put("taskId", candidate.taskId)
            .put("topic", candidate.topic)
            .put("providerId", candidate.providerId)
            .put("providerVersion", candidate.providerVersion)
            .put("agentId", candidate.agentId)
            .put("role", candidate.role)
            .put("content", candidate.content)
            .put("contentSha256", candidate.contentSha256)
            .put("state", candidate.state.name)
            .put("createdAtMillis", candidate.createdAtMillis)
            .put("verifierId", candidate.verifierId)
            .put("evidenceRefs", org.json.JSONArray(candidate.evidenceRefs))
            .put("verificationNote", candidate.verificationNote)

    private fun fromJson(json: JSONObject): BardoLearningCandidate {
        val refsJson = json.optJSONArray("evidenceRefs") ?: org.json.JSONArray()
        val refs = (0 until refsJson.length()).map { refsJson.getString(it) }
        val id = json.getString("id")
        require(id.matches(UUID_PATTERN))
        return BardoLearningCandidate(
            id = id,
            taskId = json.getString("taskId"),
            topic = json.getString("topic"),
            providerId = json.getString("providerId"),
            providerVersion = json.getString("providerVersion"),
            agentId = json.getString("agentId"),
            role = json.getString("role"),
            content = json.getString("content"),
            contentSha256 = json.getString("contentSha256"),
            state = BardoLearningState.valueOf(json.getString("state")),
            createdAtMillis = json.getLong("createdAtMillis"),
            verifierId = json.optString("verifierId").takeIf { it.isNotBlank() && it != "null" },
            evidenceRefs = refs,
            verificationNote = json.optString("verificationNote")
                .takeIf { it.isNotBlank() && it != "null" }
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val JOURNAL_NAME = "bardo_learning.jsonl"
        private const val MAX_CONTENT_CHARS = 24_000
        private const val MAX_SEARCH_CHARS = 100_000
        private const val MAX_EVIDENCE_REFS = 20
        private const val MAX_EVENT_BYTES = 64 * 1024L
        private const val MAX_JOURNAL_BYTES = 20L * 1024L * 1024L
        private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val ID_PATTERN = Regex("^[a-zA-Z0-9._:-]{3,100}$")
    }
}
