package com.musicaia

import com.musicaia.memoria.BardoLearningJournal
import com.musicaia.memoria.BardoLearningState
import com.musicaia.memoria.BardoLearningVerification
import com.musicaia.memoria.BardoLearningVerifier
import com.musicaia.modelos.BardoAgentContribution
import com.musicaia.modelos.BardoAgentRole
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BardoLearningJournalTest {
    private fun contribution(content: String = "Usar una prueba de regresión"): BardoAgentContribution =
        BardoAgentContribution(
            agentId = "local-engineer",
            agentName = "Agente local",
            providerId = "gemma-local",
            providerVersion = "v1",
            role = BardoAgentRole.SOFTWARE_ENGINEER,
            succeeded = true,
            content = content,
            error = null,
            elapsedMillis = 12
        )

    @Test fun modelOutputStartsAsCandidateAndIsNotReturnedAsVerifiedKnowledge() {
        val root = createTempDir(prefix = "bardo-learning-candidate")
        try {
            val journal = BardoLearningJournal(root)
            val candidate = journal.recordCandidate("task-1", "pruebas", contribution())

            assertEquals(BardoLearningState.CANDIDATE, candidate.state)
            assertEquals(1, journal.listCandidates().size)
            assertTrue(journal.searchVerified("prueba").isBlank())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun onlyIndependentVerificationPromotesKnowledge() {
        val root = createTempDir(prefix = "bardo-learning-verified")
        try {
            val verifier = BardoLearningVerifier {
                BardoLearningVerification(
                    passed = true,
                    verifierId = "core-evaluator-v1",
                    evidenceRefs = listOf("ci://run/123/test/BardoRegressionTest"),
                    note = "La prueba independiente pasó."
                )
            }
            val journal = BardoLearningJournal(root, verifier)
            val candidate = journal.recordCandidate("task-2", "pruebas", contribution())

            val promoted = journal.verifyAndPromote(candidate.id)

            assertEquals(BardoLearningState.VERIFIED, promoted.state)
            assertTrue(journal.listCandidates().isEmpty())
            assertTrue(journal.searchVerified("prueba").contains("Usar una prueba de regresión"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun failedVerificationNeverAppearsAsVerifiedKnowledge() {
        val root = createTempDir(prefix = "bardo-learning-rejected")
        try {
            val verifier = BardoLearningVerifier {
                BardoLearningVerification(
                    passed = false,
                    verifierId = "core-evaluator-v1",
                    evidenceRefs = listOf("test://failed/fixture"),
                    note = "La afirmación no se pudo reproducir."
                )
            }
            val journal = BardoLearningJournal(root, verifier)
            val candidate = journal.recordCandidate("task-3", "pruebas", contribution())

            val rejected = journal.verifyAndPromote(candidate.id)

            assertEquals(BardoLearningState.REJECTED, rejected.state)
            assertTrue(journal.searchVerified("prueba").isBlank())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun promotionIsBlockedWithoutAnIndependentVerifier() {
        val root = createTempDir(prefix = "bardo-learning-no-verifier")
        try {
            val journal = BardoLearningJournal(root)
            val candidate = journal.recordCandidate("task-4", "pruebas", contribution())

            assertThrows(IllegalStateException::class.java) {
                journal.verifyAndPromote(candidate.id)
            }
            assertEquals(BardoLearningState.CANDIDATE, journal.listCandidates().single().state)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun corruptedContentHashBlocksReading() {
        val root = createTempDir(prefix = "bardo-learning-corrupt")
        try {
            val journal = BardoLearningJournal(root)
            journal.recordCandidate("task-5", "integridad", contribution())
            val file = File(root, "bardo_learning.jsonl")
            val source = file.readText()
            file.writeText(source.replaceFirst("Usar una prueba", "Alterar una prueba"))

            assertThrows(IllegalArgumentException::class.java) {
                journal.listCandidates()
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
