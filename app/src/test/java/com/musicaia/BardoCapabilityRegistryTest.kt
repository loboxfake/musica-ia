package com.musicaia

import com.musicaia.modelo.BardoCapabilityRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoCapabilityRegistryTest {
    @Test
    fun reportsMissingModelsAndNativeRuntimeHonestly() {
        val items = BardoCapabilityRegistry.snapshot(
            BardoCapabilityRegistry.RuntimeFacts(
                gemmaFilePresent = false,
                audioEnginePresent = false,
                mp3EncoderPresent = false
            )
        ).associateBy { it.id }
        assertEquals(BardoCapabilityRegistry.Status.NEEDS_MODEL, items.getValue("general_chat").status)
        assertEquals(BardoCapabilityRegistry.Status.NEEDS_FULL_APK, items.getValue("instrumental").status)
        assertEquals(BardoCapabilityRegistry.Status.NOT_IMPLEMENTED, items.getValue("voice_synthesis").status)
    }

    @Test
    fun blocksLocalModelWhenFreeMemoryIsBelowSafeThreshold() {
        val items = BardoCapabilityRegistry.snapshot(
            BardoCapabilityRegistry.RuntimeFacts(
                gemmaFilePresent = true,
                audioEnginePresent = true,
                mp3EncoderPresent = true,
                availableMemoryBytes = BardoCapabilityRegistry.MIN_GEMMA_FREE_MEMORY_BYTES - 1
            )
        ).associateBy { it.id }
        assertEquals(BardoCapabilityRegistry.Status.NEEDS_MEMORY, items.getValue("general_chat").status)
        assertEquals(BardoCapabilityRegistry.Status.NEEDS_MEMORY, items.getValue("lyrics").status)
        assertTrue(items.getValue("general_chat").detail.contains("4 GB"))
    }

    @Test
    fun blocksGemmaWhenBatteryOrThermalBudgetIsNotSafe() {
        val items = BardoCapabilityRegistry.snapshot(
            BardoCapabilityRegistry.RuntimeFacts(
                gemmaFilePresent = true,
                audioEnginePresent = true,
                mp3EncoderPresent = true,
                availableMemoryBytes = BardoCapabilityRegistry.MIN_GEMMA_FREE_MEMORY_BYTES,
                resourceBlockReason = "Batería baja (9%)."
            )
        ).associateBy { it.id }
        assertEquals(BardoCapabilityRegistry.Status.NEEDS_RESOURCES, items.getValue("general_chat").status)
        assertEquals("Batería baja (9%).", items.getValue("general_chat").detail)
    }

    @Test
    fun allowsLocalModelWhenMemoryThresholdIsMet() {
        val items = BardoCapabilityRegistry.snapshot(
            BardoCapabilityRegistry.RuntimeFacts(
                gemmaFilePresent = true,
                audioEnginePresent = true,
                mp3EncoderPresent = true,
                availableMemoryBytes = BardoCapabilityRegistry.MIN_GEMMA_FREE_MEMORY_BYTES
            )
        ).associateBy { it.id }
        assertEquals(BardoCapabilityRegistry.Status.AVAILABLE, items.getValue("general_chat").status)
        assertEquals(BardoCapabilityRegistry.Status.AVAILABLE, items.getValue("lyrics").status)
    }

    @Test
    fun distinguishesAvailableCapabilitiesFromApprovalGatedOnes() {
        val items = BardoCapabilityRegistry.snapshot(
            BardoCapabilityRegistry.RuntimeFacts(true, true, true)
        ).associateBy { it.id }
        assertEquals(BardoCapabilityRegistry.Status.AVAILABLE, items.getValue("history").status)
        assertEquals(BardoCapabilityRegistry.Status.AVAILABLE, items.getValue("knowledge").status)
        assertEquals(BardoCapabilityRegistry.Status.AVAILABLE, items.getValue("chat_attachments").status)
        assertEquals(BardoCapabilityRegistry.Status.AVAILABLE, items.getValue("user_authorized_learning").status)
        assertEquals(BardoCapabilityRegistry.Status.AVAILABLE, items.getValue("wav_mastering_basic").status)
        assertEquals(BardoCapabilityRegistry.Status.REQUIRES_USER_ACTION, items.getValue("research").status)
        assertEquals(BardoCapabilityRegistry.Status.REQUIRES_USER_ACTION, items.getValue("project_build").status)
        assertEquals(BardoCapabilityRegistry.Status.REQUIRES_USER_ACTION, items.getValue("github_development").status)
        assertTrue(items.getValue("project_build").detail.contains("token fine-grained"))
        assertTrue(items.getValue("github_development").detail.contains("No se incrustan credenciales"))
        assertTrue(items.getValue("self_install").detail.contains("Android"))
    }

    @Test
    fun enablesRemoteCandidateBuildAndApprovedMainIntegrationOnlyWhenGitHubIsConfigured() {
        val items = BardoCapabilityRegistry.snapshot(
            BardoCapabilityRegistry.RuntimeFacts(
                gemmaFilePresent = true,
                audioEnginePresent = true,
                mp3EncoderPresent = true,
                githubActionsConfigured = true
            )
        ).associateBy { it.id }
        assertEquals(BardoCapabilityRegistry.Status.AVAILABLE, items.getValue("project_build").status)
        assertEquals(BardoCapabilityRegistry.Status.AVAILABLE, items.getValue("github_development").status)
        assertTrue(items.getValue("project_build").detail.contains("runner efímero"))
        assertTrue(items.getValue("github_development").acceptanceCriteria.contains("aprobación explícita"))
    }

    @Test
    fun placeholderCapabilitiesRemainExplicitlyUnavailable() {
        val items = BardoCapabilityRegistry.snapshot(
            BardoCapabilityRegistry.RuntimeFacts(true, true, true)
        ).associateBy { it.id }
        assertEquals(BardoCapabilityRegistry.Status.NOT_IMPLEMENTED, items.getValue("voice_synthesis").status)
        assertEquals(BardoCapabilityRegistry.Status.NOT_IMPLEMENTED, items.getValue("mix_master").status)
        assertEquals(BardoCapabilityRegistry.Status.NOT_IMPLEMENTED, items.getValue("cover_art").status)
        assertTrue(items.getValue("self_install").detail.contains("403"))
        assertTrue(items.getValue("self_install").detail.contains("artefacto"))
    }
}
