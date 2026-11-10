package com.musicaia

import com.musicaia.desarrollo.BardoPatchApplier
import com.musicaia.evaluacion.BardoCheck
import com.musicaia.evaluacion.BardoCheckEvidence
import com.musicaia.evaluacion.BardoEvidenceOrigin
import com.musicaia.evaluacion.BardoLocalCandidateAuditInput
import com.musicaia.evaluacion.BardoLocalCandidateEvidenceCollector
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoLocalCandidateEvidenceCollectorTest {
    private val roots = mutableListOf<File>()
    private val baseSha = "0123456789abcdef0123456789abcdef01234567"
    private val sourcePath = "app/src/main/java/com/musicaia/modulos/ModuloPrueba.kt"
    private val modo3Path = "app/src/main/java/com/musicaia/modulos/Modo3MezclaVozUsuario.kt"
    private val patch = """
        --- a/$sourcePath
        +++ b/$sourcePath
        @@ -1,1 +1,1 @@
        -val value = 1
        +val value = 2
    """.trimIndent()

    private data class Fixture(
        val workspace: File,
        val snapshot: File,
        val candidate: File,
        val modo3Hash: String,
        val input: BardoLocalCandidateAuditInput
    )

    @After
    fun cleanUp() {
        roots.forEach { runCatching { it.deleteRecursively() } }
        roots.clear()
    }

    private fun fixture(): Fixture {
        val workspace = Files.createTempDirectory("bardo-evidence-test").toFile()
        roots += workspace
        val snapshot = File(workspace, "snapshot-test")
        check(snapshot.mkdir())
        File(snapshot, "BARDO_SOURCE_COMMIT.txt").writeText(baseSha + "\n")
        val source = File(snapshot, sourcePath)
        check(source.parentFile!!.mkdirs())
        source.writeText("val value = 1\n")
        val modo3 = File(snapshot, modo3Path)
        check(modo3.parentFile!!.isDirectory || modo3.parentFile!!.mkdirs())
        modo3.writeText("protected Modo3 fixture\n")
        val candidateResult = BardoPatchApplier.aplicar(snapshot, patch, workspace)
        val modo3Hash = gitBlobSha1(modo3)
        val input = BardoLocalCandidateAuditInput(
            workspaceRoot = workspace,
            snapshotDirectory = snapshot,
            candidateDirectory = candidateResult.directory,
            expectedBaseSha = baseSha,
            patchText = patch,
            changedFiles = candidateResult.changedFiles,
            auditId = "test/session-001"
        )
        return Fixture(workspace, snapshot, candidateResult.directory, modo3Hash, input)
    }

    private fun gitBlobSha1(file: File): String {
        val bytes = file.readBytes()
        val header = ("blob " + bytes.size + "\u0000").toByteArray(Charsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(header)
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun acceptsMatchingSnapshotPatchCandidateAndProtectedModo3() {
        val fixture = fixture()
        val result = BardoLocalCandidateEvidenceCollector.collectForTest(
            fixture.input,
            fixture.modo3Hash
        )
        assertEquals(
            setOf(
                BardoCheck.SOURCE_BASE_CURRENT,
                BardoCheck.PATCH_INTEGRITY,
                BardoCheck.PROTECTED_PATHS,
                BardoCheck.MODO3_HASH_UNCHANGED
            ),
            result.map { it.check }.toSet()
        )
        assertTrue(result.all { it.passed })
        assertTrue(BardoLocalCandidateEvidenceCollector.isReadyForCiSubmission(result))
        assertFalse(BardoLocalCandidateEvidenceCollector.isReadyForCiSubmission(result.dropLast(1)))
        assertFalse(BardoLocalCandidateEvidenceCollector.isReadyForCiSubmission(result + result.first()))
        assertTrue(result.all { it.origin == BardoEvidenceOrigin.LOCAL_AUDIT })
        assertTrue(result.all { it.evidenceRef == "bardo-audit://test/session-001" })
        assertTrue(fixture.candidate.isDirectory)
        assertEquals("val value = 2\n", File(fixture.candidate, sourcePath).readText())
        assertFalse(File(fixture.candidate, modo3Path).exists())
        assertEquals(
            setOf("snapshot-test", fixture.candidate.name),
            fixture.workspace.listFiles()!!.map { it.name }.toSet()
        )
    }

    @Test
    fun detectsCandidateFileTamperingAndKeepsOriginalCandidateForReview() {
        val fixture = fixture()
        File(fixture.candidate, sourcePath).writeText("val value = 999\n")

        val result = BardoLocalCandidateEvidenceCollector.collectForTest(
            fixture.input,
            fixture.modo3Hash
        )

        assertFalse(result.single { it.check == BardoCheck.PATCH_INTEGRITY }.passed)
        assertFalse(BardoLocalCandidateEvidenceCollector.isReadyForCiSubmission(result))
        assertTrue(result.single { it.check == BardoCheck.SOURCE_BASE_CURRENT }.passed)
        assertTrue(result.single { it.check == BardoCheck.MODO3_HASH_UNCHANGED }.passed)
        assertEquals("val value = 999\n", File(fixture.candidate, sourcePath).readText())
    }

    @Test
    fun detectsStaleSnapshotMarker() {
        val fixture = fixture()
        File(fixture.snapshot, "BARDO_SOURCE_COMMIT.txt").writeText("ffffffffffffffffffffffffffffffffffffffff\n")

        val result = BardoLocalCandidateEvidenceCollector.collectForTest(
            fixture.input,
            fixture.modo3Hash
        )

        assertFalse(result.single { it.check == BardoCheck.SOURCE_BASE_CURRENT }.passed)
        assertTrue(result.single { it.check == BardoCheck.PATCH_INTEGRITY }.passed)
    }

    @Test
    fun detectsModifiedModo3BlobWithoutChangingTheOriginalCandidate() {
        val fixture = fixture()
        File(fixture.snapshot, modo3Path).writeText("unexpected Modo3 change\n")

        val result = BardoLocalCandidateEvidenceCollector.collectForTest(
            fixture.input,
            fixture.modo3Hash
        )

        assertFalse(result.single { it.check == BardoCheck.MODO3_HASH_UNCHANGED }.passed)
        assertTrue(File(fixture.candidate, sourcePath).isFile)
        assertFalse(File(fixture.candidate, modo3Path).exists())
    }

    @Test
    fun policyRejectsModo3InTheDeclaredChangeSet() {
        val fixture = fixture()
        val modified = fixture.input.copy(
            changedFiles = fixture.input.changedFiles + modo3Path
        )

        val result = BardoLocalCandidateEvidenceCollector.collectForTest(
            modified,
            fixture.modo3Hash
        )

        assertFalse(result.single { it.check == BardoCheck.PROTECTED_PATHS }.passed)
        assertFalse(result.single { it.check == BardoCheck.MODO3_HASH_UNCHANGED }.passed)
    }

    @Test
    fun refusesWrongSnapshotOrCandidateWorkspaceLayout() {
        val fixture = fixture()
        val outside = Files.createTempDirectory("bardo-outside-test").toFile()
        roots += outside
        val modified = fixture.input.copy(snapshotDirectory = outside)

        val result = BardoLocalCandidateEvidenceCollector.collectForTest(
            modified,
            fixture.modo3Hash
        )

        assertTrue(result.all { !it.passed })
        assertTrue(fixture.candidate.isDirectory)
    }

    @Test
    fun submissionGateRejectsUntrustedOriginAndMalformedEvidenceReference() {
        val fixture = fixture()
        val evidence = BardoLocalCandidateEvidenceCollector.collectForTest(
            fixture.input,
            fixture.modo3Hash
        )
        val forgedOrigin = evidence.mapIndexed { index, item ->
            if (index == 0) {
                BardoCheckEvidence(
                    item.check,
                    item.passed,
                    item.detail,
                    item.evidenceRef,
                    BardoEvidenceOrigin.GITHUB_ACTIONS
                )
            } else item
        }
        val malformedReference = evidence.mapIndexed { index, item ->
            if (index == 0) {
                BardoCheckEvidence(
                    item.check,
                    item.passed,
                    item.detail,
                    "https://example.invalid/audit",
                    item.origin
                )
            } else item
        }
        assertTrue(BardoLocalCandidateEvidenceCollector.isReadyForCiSubmission(evidence))
        assertFalse(BardoLocalCandidateEvidenceCollector.isReadyForCiSubmission(forgedOrigin))
        assertFalse(BardoLocalCandidateEvidenceCollector.isReadyForCiSubmission(malformedReference))
    }

    @Test
    fun rejectsSymlinkInCandidateAndDoesNotTrustModo3Check() {
        val fixture = fixture()
        val candidateSource = File(fixture.candidate, sourcePath)
        assertTrue(candidateSource.delete())
        Files.createSymbolicLink(
            candidateSource.toPath(),
            File(fixture.snapshot, sourcePath).toPath()
        )

        val result = BardoLocalCandidateEvidenceCollector.collectForTest(
            fixture.input,
            fixture.modo3Hash
        )

        assertTrue(result.single { it.check == BardoCheck.SOURCE_BASE_CURRENT }.passed)
        assertFalse(result.single { it.check == BardoCheck.PATCH_INTEGRITY }.passed)
        assertFalse(result.single { it.check == BardoCheck.PROTECTED_PATHS }.passed)
        assertFalse(result.single { it.check == BardoCheck.MODO3_HASH_UNCHANGED }.passed)
    }


}
