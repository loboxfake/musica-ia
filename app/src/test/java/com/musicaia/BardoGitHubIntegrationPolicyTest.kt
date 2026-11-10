package com.musicaia

import com.musicaia.desarrollo.BardoGitHubIntegrationPolicy
import com.musicaia.desarrollo.BardoGitHubActionsClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
        assertTrue(BardoGitHubIntegrationPolicy.BLOCK_REASON.contains("creación de ramas"))
        assertTrue(BardoGitHubIntegrationPolicy.BLOCK_REASON.contains("rama única"))
        assertTrue(BardoGitHubIntegrationPolicy.BLOCK_REASON.contains("main"))
        assertEquals(true, BardoGitHubIntegrationPolicy.MAIN_COMMIT_WORKFLOW_ENABLED)
        assertEquals(
            "bardo-main-commit-candidate.yml",
            BardoGitHubActionsClient.MAIN_COMMIT_WORKFLOW
        )
        assertTrue(
            runCatching {
                BardoGitHubIntegrationPolicy.requireMainCommitWorkflowAvailable()
            }.isSuccess
        )
    }

    @Test
    fun directMainCommitRequiresExplicitApprovalBeforeAnyGitHubRequest() {
        val client = BardoGitHubActionsClient("github_pat_test")
        val error = assertThrows(IllegalArgumentException::class.java) {
            client.dispatchMainCommit(
                baseSha = "",
                candidateId = "",
                packedPatch = "",
                validationRunId = 0L,
                explicitlyApprovedByUser = false
            )
        }
        assertTrue(error.message.orEmpty().contains("aprobación explícita"))
    }

}
