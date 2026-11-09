package com.musicaia

import com.musicaia.evaluacion.BardoCheck
import com.musicaia.evaluacion.BardoCheckEvidence
import com.musicaia.evaluacion.BardoEvaluationStatus
import com.musicaia.evaluacion.BardoIndependentEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoIndependentEvaluatorTest {
    private fun ciEvidence() = BardoIndependentEvaluator.mandatoryCiChecks().map {
        BardoCheckEvidence(it, true, "passed: $it")
    }

    @Test fun missingEvidenceIsIncomplete() {
        val result = BardoIndependentEvaluator.evaluate(emptyList())
        assertEquals(BardoEvaluationStatus.INCOMPLETE, result.status)
        assertTrue(result.missingChecks.contains(BardoCheck.UNIT_TESTS))
    }

    @Test fun failedMandatoryCiCheckRejectsCandidate() {
        val result = BardoIndependentEvaluator.evaluate(
            ciEvidence().filterNot { it.check == BardoCheck.DEBUG_BUILD } +
                BardoCheckEvidence(BardoCheck.DEBUG_BUILD, false, "compile failed")
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
            BardoCheckEvidence(it, true, "device verified: $it")
        }
        assertEquals(
            BardoEvaluationStatus.DEVICE_VERIFIED,
            BardoIndependentEvaluator.evaluate(all).status
        )
    }

    @Test fun refusesDuplicateCheckEvidence() {
        val one = BardoCheckEvidence(BardoCheck.UNIT_TESTS, true, "ok")
        assertTrue(runCatching {
            BardoIndependentEvaluator.evaluate(listOf(one, one))
        }.isFailure)
    }
}
