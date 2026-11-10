package com.musicaia

import com.musicaia.desarrollo.BardoGitHubIntegrationPolicy
import com.musicaia.desarrollo.BardoGitHubActionsClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoGitHubIntegrationPolicyTest {
    @Test
    fun branchAndPullRequestFlowIsDisabledForMainOnlyDevelopment() {
        assertEquals(false, BardoGitHubIntegrationPolicy.DRAFT_PR_WORKFLOW_ENABLED)
        assertTrue(
            runCatching {
                BardoGitHubIntegrationPolicy.requireDraftPrWorkflowAvailable()
            }.isFailure
        )
        assertEquals(
            "bardo-commit-candidate.yml",
            BardoGitHubActionsClient.PR_WORKFLOW
        )
        assertTrue(BardoGitHubIntegrationPolicy.BLOCK_REASON.contains("rama única"))
        assertTrue(BardoGitHubIntegrationPolicy.BLOCK_REASON.contains("main"))
    }
}
