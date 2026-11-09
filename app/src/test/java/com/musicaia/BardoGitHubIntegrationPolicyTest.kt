package com.musicaia

import com.musicaia.desarrollo.BardoGitHubIntegrationPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoGitHubIntegrationPolicyTest {
    @Test fun directMainWritingWorkflowCannotBeDispatchedAsPullRequestCreation() {
        assertFalse(BardoGitHubIntegrationPolicy.DRAFT_PR_WORKFLOW_ENABLED)
        assertTrue(BardoGitHubIntegrationPolicy.BLOCK_REASON.contains("push directo a main"))
        assertTrue(BardoGitHubIntegrationPolicy.BLOCK_REASON.contains("PR borrador"))
    }
}
