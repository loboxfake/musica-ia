package com.musicaia

import com.musicaia.evaluacion.BardoCheck
import com.musicaia.evaluacion.BardoCheckEvidence
import com.musicaia.evaluacion.BardoEvidenceOrigin
import com.musicaia.evaluacion.BardoEvaluationStatus
import com.musicaia.evaluacion.BardoIndependentEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoIndependentEvaluatorTest {
    private fun evidence(
        check: BardoCheck,
        passed: Boolean = true,
        origin: BardoEvidenceOrigin? = null,
        evidenceRef: String? = null,
        detail: String = "verified: $check"
    ): BardoCheckEvidence {
        val isCi = check in setOf(
            BardoCheck.GITLEAKS, BardoCheck.ACTIONLINT,
            BardoCheck.UNIT_TESTS, BardoCheck.DEBUG_BUILD
        )
        val isDevice = check in BardoIndependentEvaluator.mandatoryDeviceChecks() &&
            check != BardoCheck.APK_AUDIT
        val actualOrigin = origin ?: when {
            isCi -> BardoEvidenceOrigin.GITHUB_ACTIONS
            isDevice -> BardoEvidenceOrigin.DEVICE_TEST
            else -> BardoEvidenceOrigin.LOCAL_AUDIT
        }
        val actualRef = evidenceRef ?: when (actualOrigin) {
            BardoEvidenceOrigin.GITHUB_ACTIONS ->
                "https://github.com/loboxfake/musica-ia/actions/runs/42616896249"
            BardoEvidenceOrigin.DEVICE_TEST ->
                "bardo-device://test/session-1/" + check.name.lowercase()
            BardoEvidenceOrigin.LOCAL_AUDIT ->
                "bardo-audit://test/session-1/" + check.name.lowercase()
        }
        return BardoCheckEvidence(check, passed, detail, actualRef, actualOrigin)
    }

    private fun ciEvidence() = BardoIndependentEvaluator.mandatoryCiChecks().map {
        evidence(it)
    }

    @Test fun missingEvidenceIsIncomplete() {
        val result = BardoIndependentEvaluator.evaluate(emptyList())
        assertEquals(BardoEvaluationStatus.INCOMPLETE, result.status)
        assertTrue(result.missingChecks.contains(BardoCheck.UNIT_TESTS))
    }

    @Test fun failedMandatoryCiCheckRejectsCandidate() {
        val result = BardoIndependentEvaluator.evaluate(
            ciEvidence().filterNot { it.check == BardoCheck.DEBUG_BUILD } +
                evidence(BardoCheck.DEBUG_BUILD, passed = false, detail = "compile failed")
        )
        assertEquals(BardoEvaluationStatus.REJECTED, result.status)
    }

    @Test fun successfulCiStillRequiresHumanReviewAndDeviceEvidence() {
        val result = BardoIndependentEvaluator.evaluate(ciEvidence())
        assertEquals(BardoEvaluationStatus.READY_FOR_HUMAN_REVIEW, result.status)
        assertTrue(result.missingChecks.contains(BardoCheck.DEVICE_INSTALL))
    }

    @Test fun allCiAndDeviceChecksAreRequiredForDeviceVerifiedStatus() {
        val all = ciEvidence() + BardoIndependentEvaluator.mandatoryDeviceChecks().map {
            evidence(it)
        }
        assertEquals(
            BardoEvaluationStatus.DEVICE_VERIFIED,
            BardoIndependentEvaluator.evaluate(all).status
        )
    }

    @Test fun aFailedDeviceCheckCannotBeReportedAsVerifiedOrReady() {
        val evidence = ciEvidence() + evidence(
            BardoCheck.DEVICE_INSTALL,
            passed = false,
            detail = "install failed"
        )
        assertEquals(
            BardoEvaluationStatus.REJECTED,
            BardoIndependentEvaluator.evaluate(evidence).status
        )
    }

    @Test fun refusesDuplicateCheckEvidence() {
        val one = evidence(BardoCheck.UNIT_TESTS)
        assertTrue(runCatching {
            BardoIndependentEvaluator.evaluate(listOf(one, one))
        }.isFailure)
    }

    @Test fun rejectsPassedCheckWithoutAuditableReference() {
        val invalid = evidence(BardoCheck.UNIT_TESTS, evidenceRef = "")
        val result = BardoIndependentEvaluator.evaluate(ciEvidence().filterNot {
            it.check == BardoCheck.UNIT_TESTS
        } + invalid)
        assertEquals(BardoEvaluationStatus.REJECTED, result.status)
        assertTrue(result.failedChecks.contains(BardoCheck.UNIT_TESTS))
        assertFalse(result.passedChecks.contains(BardoCheck.UNIT_TESTS))
    }

    @Test fun rejectsCiEvidenceUsingDeviceOrigin() {
        val invalid = evidence(
            BardoCheck.UNIT_TESTS,
            origin = BardoEvidenceOrigin.DEVICE_TEST
        )
        val result = BardoIndependentEvaluator.evaluate(ciEvidence().filterNot {
            it.check == BardoCheck.UNIT_TESTS
        } + invalid)
        assertEquals(BardoEvaluationStatus.REJECTED, result.status)
    }

    @Test fun rejectsUnrecognizedGithubEvidenceUrl() {
        val invalid = evidence(
            BardoCheck.DEBUG_BUILD,
            evidenceRef = "https://example.invalid/actions/runs/123456"
        )
        val result = BardoIndependentEvaluator.evaluate(ciEvidence().filterNot {
            it.check == BardoCheck.DEBUG_BUILD
        } + invalid)
        assertEquals(BardoEvaluationStatus.REJECTED, result.status)
    }
}
