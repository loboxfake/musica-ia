package com.musicaia.servicios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class BardoTaskLifecyclePolicyTest {
    @Test
    fun distinguishesCancellationFromRealFailure() {
        assertEquals("cancelled", BardoTaskLifecyclePolicy.terminalState(false, CancellationException("cancelled")))
        assertEquals("cancelled", BardoTaskLifecyclePolicy.terminalState(true, IllegalStateException("native error")))
        assertEquals("failed", BardoTaskLifecyclePolicy.terminalState(false, IllegalStateException("native error")))
    }

    @Test
    fun finalizesOnlyTheTaskThatStillOwnsTheService() {
        assertTrue(BardoTaskLifecyclePolicy.mayFinalize("task-a", "task-a", false))
        assertFalse(BardoTaskLifecyclePolicy.mayFinalize("task-b", "task-a", false))
        assertFalse(BardoTaskLifecyclePolicy.mayFinalize("task-a", "task-a", true))
        assertFalse(BardoTaskLifecyclePolicy.mayFinalize("", "task-a", false))
    }

    @Test
    fun refusesToCommitCompletionAfterCancellationWasRequested() {
        assertThrows(CancellationException::class.java) {
            BardoTaskLifecyclePolicy.ensureCanCommitCompletion(true)
        }
        BardoTaskLifecyclePolicy.ensureCanCommitCompletion(false)
    }
}
