package com.musicaia

import com.musicaia.desarrollo.BardoGitHubIntegrationPolicy
import com.musicaia.desarrollo.BardoGitHubActionsClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoGitHubIntegrationPolicyTest {
    @Test fun clientRejectsLegacyIntegrationBeforeAnyNetworkRequest() {
        val client = BardoGitHubActionsClient("github_pat_" + "x".repeat(48))
        val error = runCatching {
            client.dispatchPullRequest(
                baseSha = "a".repeat(40),
                candidateId = "b".repeat(32),
                packedPatch = "candidate-patch",
                validationRunId = 123456L
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("push directo a main"))
    }

    @Test fun directMainWritingWorkflowCannotBeDispatchedAsPullRequestCreation() {
        assertFalse(BardoGitHubIntegrationPolicy.DRAFT_PR_WORKFLOW_ENABLED)
        assertTrue(BardoGitHubIntegrationPolicy.BLOCK_REASON.contains("push directo a main"))
        assertTrue(BardoGitHubIntegrationPolicy.BLOCK_REASON.contains("PR borrador"))
    }
}
