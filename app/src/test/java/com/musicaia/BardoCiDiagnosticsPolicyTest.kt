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
    fun rejectsInvalidRunIds() {
        assertFalse(BardoCiDiagnosticsPolicy.canReplace(-1L, 10L))
        assertFalse(BardoCiDiagnosticsPolicy.canReplace(10L, 0L))
        assertFalse(BardoCiDiagnosticsPolicy.canReplace(0L, -1L))
    }
}
