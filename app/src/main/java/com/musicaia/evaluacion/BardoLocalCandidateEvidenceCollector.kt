package com.musicaia.evaluacion

import com.musicaia.desarrollo.BardoGitHubPolicy
import com.musicaia.desarrollo.BardoPatchApplier
import com.musicaia.desarrollo.BardoProtectedPathPolicy
import com.musicaia.desarrollo.BardoWorkspaceManager
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

data class BardoLocalCandidateAuditInput(
    val workspaceRoot: File,
    val snapshotDirectory: File,
    val candidateDirectory: File,
    val expectedBaseSha: String,
    val patchText: String,
    val changedFiles: List<String>,
    val auditId: String
)

/**
 * Productor de las cuatro evidencias locales obligatorias.
 *
 * Trabaja en lectura sobre snapshot/candidato. Para comprobar la integridad del
 * parche lo vuelve a aplicar en otra carpeta candidata temporal y compara los
 * archivos byte a byte mediante SHA-256. El candidato original nunca se modifica.
 */
object BardoLocalCandidateEvidenceCollector {
    private val shaPattern = Regex("^[0-9a-f]{40}$")
    private val auditIdPattern = Regex("^[A-Za-z0-9._/-]{3,180}$")
    private val candidateNamePattern = Regex("^candidate-[0-9a-fA-F-]{36}$")

    fun collect(input: BardoLocalCandidateAuditInput): List<BardoCheckEvidence> =
        collectInternal(input, BardoProtectedPathPolicy.MODO3_EXPECTED_BLOB_SHA1)

    /** Sustituye solo el hash de referencia para poder probar el cálculo Git-blob con fixtures. */
    internal fun collectForTest(
        input: BardoLocalCandidateAuditInput,
        expectedModo3BlobSha1: String
    ): List<BardoCheckEvidence> = collectInternal(input, expectedModo3BlobSha1)

    private fun collectInternal(
        input: BardoLocalCandidateAuditInput,
        expectedModo3BlobSha1: String
    ): List<BardoCheckEvidence> {
        require(auditIdPattern.matches(input.auditId)) { "Identificador de auditoría local no válido." }
        val evidenceRef = "bardo-audit://" + input.auditId
        val workspaceSafe = isRealDirectory(input.workspaceRoot)
        val workspace = if (workspaceSafe) input.workspaceRoot.canonicalFile else null
        val snapshotSafe = workspace != null &&
            isRealDirectory(input.snapshotDirectory) &&
            input.snapshotDirectory.name.startsWith("snapshot-") &&
            input.snapshotDirectory.canonicalFile.parentFile == workspace
        val candidateSafe = workspace != null &&
            isRealDirectory(input.candidateDirectory) &&
            candidateNamePattern.matches(input.candidateDirectory.name) &&
            input.candidateDirectory.canonicalFile.parentFile == workspace

        val baseSha = input.expectedBaseSha.lowercase()
        val baseMarkerMatches = snapshotSafe &&
            shaPattern.matches(baseSha) &&
            runCatching {
                val marker = File(input.snapshotDirectory, "BARDO_SOURCE_COMMIT.txt")
                marker.isFile &&
                    !Files.isSymbolicLink(marker.toPath()) &&
                    marker.length() in 1L..128L &&
                    marker.readText(Charsets.UTF_8).trim().equals(baseSha, ignoreCase = true)
            }.getOrDefault(false)

        val candidateInventory = if (candidateSafe) {
            runCatching { listRegularFiles(input.candidateDirectory) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val pathsMatchCandidate = candidateSafe &&
            candidateInventory.sorted() == input.changedFiles.sorted()

        val protectedPathsValid = runCatching {
            BardoProtectedPathPolicy.validateAutonomousChanges(input.changedFiles)
            BardoGitHubPolicy.validateAutonomousChangedPaths(input.changedFiles)
            input.changedFiles.distinct().size == input.changedFiles.size
        }.getOrDefault(false)
        val protectedPathsPassed = workspaceSafe && snapshotSafe && candidateSafe &&
            protectedPathsValid && pathsMatchCandidate

        val patchBytes = input.patchText.toByteArray(Charsets.UTF_8)
        val patchHash = if (patchBytes.isNotEmpty() && patchBytes.size <= BardoGitHubPolicy.MAX_PATCH_BYTES) {
            sha256(patchBytes)
        } else {
            "unavailable"
        }
        val patchVerification = if (
            workspaceSafe && snapshotSafe && candidateSafe && protectedPathsValid &&
            pathsMatchCandidate && patchHash != "unavailable"
        ) {
            verifyPatchMatchesCandidate(input, workspace!!)
        } else {
            false to "No se pudo verificar la disposición segura del workspace, las rutas o el diff."
        }

        val modo3PathChanged = input.changedFiles.any {
            it.substringAfterLast('/').equals("Modo3MezclaVozUsuario.kt", ignoreCase = true)
        }
        val modo3Unchanged = runCatching {
            if (!snapshotSafe || !candidateSafe || modo3PathChanged ||
                !shaPattern.matches(expectedModo3BlobSha1)
            ) {
                false
            } else {
                val snapshotModo3 = BardoWorkspaceManager(input.workspaceRoot)
                    .resolveInside(input.snapshotDirectory, BardoProtectedPathPolicy.MODO3_PATH)
                snapshotModo3.isFile &&
                    snapshotModo3.length() <= BardoWorkspaceManager.MAX_FILE_BYTES &&
                    gitBlobSha1(snapshotModo3).equals(expectedModo3BlobSha1, ignoreCase = true) &&
                    BardoProtectedPathPolicy.MODO3_PATH !in candidateInventory
            }
        }.getOrDefault(false)

        val shared = "audit_id=" + input.auditId +
            "; base_sha=" + baseSha +
            "; changed_files=" + input.changedFiles.size +
            "; patch_sha256=" + patchHash

        return listOf(
            evidence(
                BardoCheck.SOURCE_BASE_CURRENT,
                baseMarkerMatches,
                shared + "; snapshot_marker_matches_expected=" + baseMarkerMatches,
                evidenceRef
            ),
            evidence(
                BardoCheck.PATCH_INTEGRITY,
                patchVerification.first,
                shared + "; candidate_matches_reapplied_patch=" + patchVerification.first +
                    "; detail=" + patchVerification.second,
                evidenceRef
            ),
            evidence(
                BardoCheck.PROTECTED_PATHS,
                protectedPathsPassed,
                shared + "; policy_and_candidate_inventory_valid=" + protectedPathsPassed,
                evidenceRef
            ),
            evidence(
                BardoCheck.MODO3_HASH_UNCHANGED,
                modo3Unchanged,
                shared + "; baseline_git_blob_sha1_matches=" + modo3Unchanged +
                    "; modo3_in_patch=" + modo3PathChanged,
                evidenceRef
            )
        )
    }

    private fun verifyPatchMatchesCandidate(
        input: BardoLocalCandidateAuditInput,
        workspaceRoot: File
    ): Pair<Boolean, String> {
        var verificationCandidate: File? = null
        val matched = try {
            val regenerated = BardoPatchApplier.aplicar(
                input.snapshotDirectory,
                input.patchText,
                workspaceRoot
            )
            verificationCandidate = regenerated.directory
            if (regenerated.changedFiles.sorted() != input.changedFiles.sorted()) {
                false to "Las rutas aplicadas no coinciden con la lista de cambios declarada."
            } else {
                val sourceManager = BardoWorkspaceManager(workspaceRoot)
                val filesMatch = input.changedFiles.all { relative ->
                    val actual = sourceManager.resolveInside(input.candidateDirectory, relative)
                    val expected = sourceManager.resolveInside(regenerated.directory, relative)
                    actual.isFile && expected.isFile &&
                        actual.length() == expected.length() &&
                        sha256(actual).equals(sha256(expected), ignoreCase = true)
                }
                filesMatch to if (filesMatch) {
                    "El diff se reaplicó desde el snapshot y la copia candidata coincide."
                } else {
                    "Uno o más archivos candidatos no coinciden con la reaplicación del diff."
                }
            }
        } catch (error: Exception) {
            false to ("La reaplicación del diff falló: " +
                (error.message ?: error.javaClass.simpleName).take(500))
        }

        val cleanupSucceeded = verificationCandidate?.let { directory ->
            runCatching {
                BardoWorkspaceManager(workspaceRoot).deleteCandidate(directory)
            }.isSuccess
        } ?: true
        return (matched.first && cleanupSucceeded) to
            (matched.second + if (cleanupSucceeded) "" else " No se pudo limpiar el candidato temporal.")
    }

    private fun listRegularFiles(root: File): List<String> {
        require(isRealDirectory(root)) { "La carpeta candidata no es real." }
        val result = mutableListOf<String>()

        fun visit(directory: File, relative: String) {
            val children = directory.listFiles()
                ?: throw IllegalStateException("No se pudo enumerar el candidato.")
            children.sortedBy { it.name }.forEach { child ->
                require(!Files.isSymbolicLink(child.toPath())) {
                    "El candidato contiene un enlace simbólico."
                }
                val path = if (relative.isBlank()) child.name else relative + "/" + child.name
                if (child.isDirectory) {
                    visit(child, path)
                } else {
                    require(child.isFile) { "El candidato contiene una entrada no regular." }
                    result += path
                }
            }
        }

        visit(root, "")
        return result
    }

    private fun isRealDirectory(file: File): Boolean =
        file.isDirectory && !Files.isSymbolicLink(file.toPath())

    private fun evidence(
        check: BardoCheck,
        passed: Boolean,
        detail: String,
        ref: String
    ) = BardoCheckEvidence(
        check = check,
        passed = passed,
        detail = detail.take(2_000),
        evidenceRef = ref,
        origin = BardoEvidenceOrigin.LOCAL_AUDIT
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** SHA-1 Git de un blob, incluyendo el encabezado "blob <bytes>\\0". */
    private fun gitBlobSha1(file: File): String {
        val bytes = file.readBytes()
        val header = ("blob " + bytes.size + "\u0000").toByteArray(Charsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(header)
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
