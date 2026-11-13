package com.musicaia

import com.musicaia.memoria.BardoLearningJournal
import com.musicaia.memoria.BardoLearningState
import com.musicaia.memoria.BardoSourceExcerptLearningVerifier
import com.musicaia.modelos.BardoAgentContribution
import com.musicaia.modelos.BardoAgentRole
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoSourceExcerptLearningVerifierTest {
    private val relativePath =
        "app/src/main/java/com/musicaia/demo/VerifiedSource.kt"

    private fun writeSource(root: File): Pair<File, String> {
        val source = File(root, relativePath)
        source.parentFile?.mkdirs()
        val text = "package demo\nobject VerifiedSource {\n    const val LOCAL_FACT = \"Stored on device\"\n}\n"
        source.writeText(text, Charsets.UTF_8)
        val quote = text.split('\n').subList(1, 3).joinToString("\n")
        return source to quote
    }

    private fun envelope(quote: String, path: String = relativePath): String {
        val encoded = Base64.getEncoder().encodeToString(quote.toByteArray(Charsets.UTF_8))
        return listOf(
            "BARDO_SOURCE_EVIDENCE_V1",
            "path=" + path,
            "start=2",
            "end=3",
            "quote64=" + encoded
        ).joinToString("\n")
    }

    private fun contribution(content: String) = BardoAgentContribution(
        agentId = "local-engineer",
        agentName = "Agente local",
        providerId = "gemma-local",
        providerVersion = "v1",
        role = BardoAgentRole.SOFTWARE_ENGINEER,
        succeeded = true,
        content = content,
        error = null,
        elapsedMillis = 1
    )

    @Test fun promotesOnlyAnExactLocalSourceExcerptAndStoresTheVerifiedExcerpt() {
        val root = createTempDir(prefix = "bardo-source-learning")
        val journalRoot = createTempDir(prefix = "bardo-source-journal")
        try {
            val (_, quote) = writeSource(root)
            val verifier = BardoSourceExcerptLearningVerifier(root)
            val envelope = envelope(quote)
            val extracted = verifier.extractEvidenceEnvelope(
                "Análisis previo\n```text\n" + envelope + "\n```\n"
            )
            assertEquals(envelope, extracted)

            val journal = BardoLearningJournal(journalRoot, verifier)
            val candidate = journal.recordCandidate("task-source-1", "fuente", contribution(envelope))
            val promoted = journal.verifyAndPromote(candidate.id)

            assertEquals(BardoLearningState.VERIFIED, promoted.state)
            assertTrue(promoted.content.contains(quote))
            assertTrue(promoted.content.contains("SHA-256"))
            assertTrue(promoted.evidenceRefs.single().startsWith("bardo-source://"))
            assertTrue(journal.searchVerified("LOCAL_FACT").contains(quote))
            val expectedHash = MessageDigest.getInstance("SHA-256")
                .digest(promoted.content.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            assertEquals(expectedHash, promoted.contentSha256)
        } finally {
            root.deleteRecursively()
            journalRoot.deleteRecursively()
        }
    }

    @Test fun mismatchedExcerptStaysPendingAndNeverEntersVerifiedSearch() {
        val root = createTempDir(prefix = "bardo-source-mismatch")
        val journalRoot = createTempDir(prefix = "bardo-source-journal")
        try {
            writeSource(root)
            val verifier = BardoSourceExcerptLearningVerifier(root)
            val journal = BardoLearningJournal(journalRoot, verifier)
            val candidate = journal.recordCandidate(
                "task-source-2", "fuente", contribution(envelope("Invented statement"))
            )

            assertTrue(runCatching { journal.verifyAndPromote(candidate.id) }.isFailure)
            assertEquals(BardoLearningState.CANDIDATE, journal.listCandidates().single().state)
            assertTrue(journal.searchVerified("Invented").isBlank())
        } finally {
            root.deleteRecursively()
            journalRoot.deleteRecursively()
        }
    }

    @Test fun rejectsPathTraversalAndProtectedModo3Source() {
        val root = createTempDir(prefix = "bardo-source-path")
        try {
            writeSource(root)
            val verifier = BardoSourceExcerptLearningVerifier(root)
            val traversal = envelope("not-used", "../../private/secret.kt")
            val protected = envelope(
                "not-used",
                "app/src/main/java/com/musicaia/modulos/Modo3MezclaVozUsuario.kt"
            )
            val dummy = contribution(traversal)
            val candidate = com.musicaia.memoria.BardoLearningCandidate(
                id = "f64c5e0a-0b9c-4e8d-a137-8f9cae2f3d41",
                taskId = "task",
                topic = "source",
                providerId = dummy.providerId,
                providerVersion = dummy.providerVersion,
                agentId = dummy.agentId,
                role = dummy.role.name,
                content = traversal,
                contentSha256 = "0".repeat(64),
                state = BardoLearningState.CANDIDATE,
                createdAtMillis = 1L
            )
            assertTrue(runCatching { verifier.verify(candidate) }.isFailure)
            val protectedCandidate = candidate.copy(content = protected)
            assertFalse(runCatching { verifier.verify(protectedCandidate) }.isSuccess)
        } finally {
            root.deleteRecursively()
        }
    }
}
