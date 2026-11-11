package com.musicaia

import com.musicaia.diagnostico.BardoCapabilityEvidenceStore
import com.musicaia.modelo.BardoCapabilityRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class BardoCapabilityEvidenceStoreTest {
    @Test fun evidenceRequiresHashThenLoadThenInferenceThenPhysicalAcceptance() {
        assertEquals(
            BardoCapabilityRegistry.Status.MODEL_PRESENT,
            BardoCapabilityEvidenceStore.resolveStatus(true, false, false, false, false, false)
        )
        assertEquals(
            BardoCapabilityRegistry.Status.VERIFIED,
            BardoCapabilityEvidenceStore.resolveStatus(true, true, true, false, false, false)
        )
        assertEquals(
            BardoCapabilityRegistry.Status.LOADABLE,
            BardoCapabilityEvidenceStore.resolveStatus(true, true, true, true, false, false)
        )
        assertEquals(
            BardoCapabilityRegistry.Status.INFERENCE_PASSED,
            BardoCapabilityEvidenceStore.resolveStatus(true, true, true, true, true, false)
        )
        assertEquals(
            BardoCapabilityRegistry.Status.DEVICE_VERIFIED,
            BardoCapabilityEvidenceStore.resolveStatus(true, true, true, true, true, true)
        )
    }

    @Test fun staleIdentityAndMissingModelsDowngradePriorProof() {
        assertEquals(
            BardoCapabilityRegistry.Status.MODEL_PRESENT,
            BardoCapabilityEvidenceStore.resolveStatus(true, false, true, true, true, true)
        )
        assertEquals(
            BardoCapabilityRegistry.Status.NEEDS_MODEL,
            BardoCapabilityEvidenceStore.resolveStatus(false, true, true, true, true, true)
        )
        assertEquals(
            BardoCapabilityRegistry.Status.LOADABLE,
            BardoCapabilityEvidenceStore.resolveStatus(true, true, true, true, false, true)
        )
    }
}
