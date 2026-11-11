package com.musicaia

import com.musicaia.evaluacion.BardoCiDiagnosticsPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoCiDiagnosticsPolicyTest {
    @Test
    fun acceptsNewerSuccessfulOrFailedRuns() {
        assertTrue(BardoCiDiagnosticsPolicy.canReplace(10L, 11L))
        assertTrue(BardoCiDiagnosticsPolicy.canReplace(10L, 10L))
    }

    @Test
    fun rejectsOlderRunsSoTheyCannotResurrectResolvedFailures() {
        assertFalse(BardoCiDiagnosticsPolicy.canReplace(11L, 10L))
    }

    @Test
    fun clearsDiagnosticsOnlyAfterSuccessFromTheSameWorkflowScope() {
        assertTrue(BardoCiDiagnosticsPolicy.shouldClear(10L, "candidate_validation", 11L, "candidate_validation"))
        assertTrue(BardoCiDiagnosticsPolicy.shouldClear(10L, "main_commit", 11L, "main_commit"))
        assertFalse(BardoCiDiagnosticsPolicy.shouldClear(10L, "main_commit", 11L, "candidate_validation"))
        assertFalse(BardoCiDiagnosticsPolicy.shouldClear(10L, "candidate_validation", 11L, "main_commit"))
    }

    @Test
    fun rejectsOldSuccessAndInvalidRunIdsWhenClearing() {
        assertFalse(BardoCiDiagnosticsPolicy.shouldClear(11L, "candidate_validation", 10L, "candidate_validation"))
        assertFalse(BardoCiDiagnosticsPolicy.shouldClear(0L, "", 10L, "candidate_validation"))
        assertFalse(BardoCiDiagnosticsPolicy.shouldClear(10L, "candidate_validation", 0L, "candidate_validation"))
    }

    @Test
    fun storesDiagnosticsForAllJobLogFailureConclusions() {
        assertTrue(BardoCiDiagnosticsPolicy.shouldStoreDiagnostics("failure"))
        assertTrue(BardoCiDiagnosticsPolicy.shouldStoreDiagnostics("timed_out"))
        assertTrue(BardoCiDiagnosticsPolicy.shouldStoreDiagnostics("action_required"))
        assertFalse(BardoCiDiagnosticsPolicy.shouldStoreDiagnostics("success"))
        assertFalse(BardoCiDiagnosticsPolicy.shouldStoreDiagnostics("cancelled"))
        assertFalse(BardoCiDiagnosticsPolicy.shouldStoreDiagnostics(null))
    }

    @Test
    fun rejectsInvalidRunIds() {
        assertFalse(BardoCiDiagnosticsPolicy.canReplace(-1L, 10L))
        assertFalse(BardoCiDiagnosticsPolicy.canReplace(10L, 0L))
        assertFalse(BardoCiDiagnosticsPolicy.canReplace(0L, -1L))
    }
}
