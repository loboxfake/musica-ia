package com.musicaia

import com.musicaia.desarrollo.BardoCandidatePromptPrivacyPolicy
import com.musicaia.desarrollo.BardoLocalOnlyModelContext
import com.musicaia.desarrollo.BardoSnapshotOriginPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoCandidatePromptPrivacyPolicyTest {
    @Test
    fun trustedBardoSourceNeverLoadsPrivateContext() {
        var privateContextLoaded = false
        val result = BardoCandidatePromptPrivacyPolicy.create(
            BardoSnapshotOriginPolicy.BUNDLED_BARDO
        ) {
            privateContextLoaded = true
            BardoLocalOnlyModelContext(
                deviceInventory = "PRIVATE_DEVICE_METRICS_123456",
                authorizedReferences = "PRIVATE_USER_DOCUMENT_abcdef",
                previousTechnicalMemory = "PRIVATE_MEMORY_999999",
                latestCiDiagnostics = "PRIVATE_CI_LOG_888888"
            )
        }

        assertFalse(privateContextLoaded)
        assertTrue(result.githubSubmissionEligible)
        assertEquals("", result.authorizedReferences)
        assertEquals("", result.previousTechnicalMemory)
        assertEquals("", result.latestCiDiagnostics)
        assertFalse(result.inventoryContext.contains("PRIVATE_DEVICE_METRICS_123456"))
        assertFalse(result.inventoryContext.contains("PRIVATE_USER_DOCUMENT_abcdef"))
        assertFalse(result.inventoryContext.contains("PRIVATE_MEMORY_999999"))
        assertFalse(result.inventoryContext.contains("PRIVATE_CI_LOG_888888"))
        assertTrue(result.inventoryContext.contains("omitido deliberadamente"))
    }

    @Test
    fun privateRepositorySourceAlsoUsesRedactedSubmissionContext() {
        var privateContextLoaded = false
        val result = BardoCandidatePromptPrivacyPolicy.create(
            BardoSnapshotOriginPolicy.PRIVATE_GITHUB_MAIN
        ) {
            privateContextLoaded = true
            error("Private context supplier must not run for trusted source.")
        }

        assertFalse(privateContextLoaded)
        assertTrue(result.githubSubmissionEligible)
        assertEquals("", result.authorizedReferences)
        assertEquals("", result.previousTechnicalMemory)
        assertEquals("", result.latestCiDiagnostics)
    }

    @Test
    fun importedProjectCanUsePrivateContextLocallyButIsNeverGithubEligible() {
        var privateContextLoaded = false
        val localContext = BardoLocalOnlyModelContext(
            deviceInventory = "DEVICE_METRICS_LOCAL_ONLY",
            authorizedReferences = "USER_DOCUMENT_LOCAL_ONLY",
            previousTechnicalMemory = "PRIVATE_MEMORY_LOCAL_ONLY",
            latestCiDiagnostics = "CI_DIAGNOSTICS_LOCAL_ONLY"
        )
        val result = BardoCandidatePromptPrivacyPolicy.create(
            BardoSnapshotOriginPolicy.USER_IMPORTED_PROJECT
        ) {
            privateContextLoaded = true
            localContext
        }

        assertTrue(privateContextLoaded)
        assertFalse(result.githubSubmissionEligible)
        assertEquals(localContext.deviceInventory, result.inventoryContext)
        assertEquals(localContext.authorizedReferences, result.authorizedReferences)
        assertEquals(localContext.previousTechnicalMemory, result.previousTechnicalMemory)
        assertEquals(localContext.latestCiDiagnostics, result.latestCiDiagnostics)
    }

    @Test
    fun missingOrUnknownOriginFailsClosed() {
        listOf(null, "", "unknown").forEach { origin ->
            val result = BardoCandidatePromptPrivacyPolicy.create(origin) {
                BardoLocalOnlyModelContext("local", "refs", "memory", "diagnostics")
            }
            assertFalse(result.githubSubmissionEligible)
        }
    }
}
