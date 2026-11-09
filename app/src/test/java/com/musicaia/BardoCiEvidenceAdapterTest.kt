package com.musicaia

import com.musicaia.evaluacion.BardoCheck
import com.musicaia.evaluacion.BardoCiEvidenceAdapter
import com.musicaia.evaluacion.BardoCiEvidenceState
import com.musicaia.evaluacion.BardoEvidenceOrigin
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoCiEvidenceAdapterTest {
    private val runId = 42637296964L
    private val runNumber = 750L
    private val sha = "601ebe58316f48d2468c593cb9e0cf64af0411ea"

    private val stepNames = listOf(
        "Scan complete Git history with redacted findings",
        "Lint all GitHub Actions workflows",
        "Run unit tests and compile debug APK",
        "Verify candidate APK exists",
        "Upload branch validation APK (not a release)"
    )

    private fun run(
        status: String = "completed",
        conclusion: String = "success",
        headSha: String = sha,
        workflowPath: String = ".github/workflows/bardo-validation.yml@refs/pull/3/merge",
        repository: String = BardoCiEvidenceAdapter.REPOSITORY,
        branch: String = "bardo/integral-autonomy-20261109"
    ) = JSONObject()
        .put("id", runId)
        .put("run_number", runNumber)
        .put("head_sha", headSha)
        .put("status", status)
        .put("conclusion", conclusion)
        .put("path", workflowPath)
        .put("head_branch", branch)
        .put("event", "pull_request")
        .put("html_url", "https://github.com/" + BardoCiEvidenceAdapter.REPOSITORY + "/actions/runs/" + runId)
        .put("repository", JSONObject().put("full_name", repository))

    private fun jobs(
        conclusion: String = "success",
        failedStep: String? = null
    ): JSONObject {
        val steps = JSONArray()
        stepNames.forEach { name ->
            steps.put(
                JSONObject()
                    .put("name", name)
                    .put("status", "completed")
                    .put("conclusion", if (name == failedStep) "failure" else "success")
            )
        }
        val job = JSONObject()
            .put("id", 987654321L)
            .put("run_id", runId)
            .put("name", "validate")
            .put("status", "completed")
            .put("conclusion", conclusion)
            .put("steps", steps)
        return JSONObject().put("jobs", JSONArray().put(job))
    }

    private fun artifacts(
        artifactRunId: Long = runId,
        artifactSha: String = sha,
        expired: Boolean = false
    ): JSONObject {
        val artifact = JSONObject()
            .put("id", 13683878079L)
            .put("name", "bardo-debug-candidate-" + runNumber)
            .put("size_in_bytes", 107829709L)
            .put("expired", expired)
            .put(
                "workflow_run",
                JSONObject()
                    .put("id", artifactRunId)
                    .put("head_sha", artifactSha)
            )
        return JSONObject().put("artifacts", JSONArray().put(artifact))
    }

    private fun collect(
        run: JSONObject = run(),
        jobs: JSONObject = jobs(),
        artifacts: JSONObject = artifacts(),
        expectedSha: String = sha
    ) = BardoCiEvidenceAdapter.collect(run, jobs, artifacts, expectedSha)

    @Test
    fun acceptsOnlyTheExactSuccessfulWorkflowAndArtifactForTheCommit() {
        val result = collect()
        assertEquals(BardoCiEvidenceState.CI_PASSED, result.state)
        assertEquals(runId, result.runId)
        assertEquals(sha, result.headSha)
        assertEquals(
            setOf(
                BardoCheck.GITLEAKS,
                BardoCheck.ACTIONLINT,
                BardoCheck.UNIT_TESTS,
                BardoCheck.DEBUG_BUILD
            ),
            result.evidence.map { it.check }.toSet()
        )
        assertTrue(result.evidence.all { it.passed })
        assertTrue(result.evidence.all { it.origin == BardoEvidenceOrigin.GITHUB_ACTIONS })
        assertTrue(result.evidence.all {
            it.evidenceRef == "https://github.com/loboxfake/musica-ia/actions/runs/" + runId
        })
        assertFalse(result.evidence.any {
            it.check in setOf(
                BardoCheck.DEVICE_INSTALL,
                BardoCheck.GEMMA_INFERENCE,
                BardoCheck.OFFLINE_OPERATION,
                BardoCheck.WAV_GENERATION,
                BardoCheck.WAV_LISTENED,
                BardoCheck.USER_VOICE_MIX,
                BardoCheck.APK_AUDIT
            )
        })
    }

    @Test
    fun rejectsRunForAnotherCommit() {
        val result = collect(run = run(headSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertEquals(BardoCiEvidenceState.INVALID, result.state)
        assertTrue(result.evidence.isEmpty())
    }

    @Test
    fun rejectsAnotherRepositoryOrWorkflow() {
        val wrongRepository = collect(run = run(repository = "someone/else"))
        assertEquals(BardoCiEvidenceState.INVALID, wrongRepository.state)

        val wrongWorkflow = collect(run = run(workflowPath = ".github/workflows/full-build.yml@refs/heads/main"))
        assertEquals(BardoCiEvidenceState.INVALID, wrongWorkflow.state)
    }

    @Test
    fun rejectsBranchesOutsideBardoNamespace() {
        val result = collect(run = run(branch = "main"))
        assertEquals(BardoCiEvidenceState.INVALID, result.state)
        assertTrue(result.evidence.isEmpty())
    }

    @Test
    fun pendingRunDoesNotProduceEvidence() {
        val result = collect(run = run(status = "in_progress", conclusion = ""))
        assertEquals(BardoCiEvidenceState.PENDING, result.state)
        assertTrue(result.evidence.isEmpty())
    }

    @Test
    fun failedStepProducesFailedEvidenceInsteadOfPassingSummary() {
        val result = collect(
            run = run(conclusion = "failure"),
            jobs = jobs(conclusion = "failure", failedStep = stepNames[0])
        )
        assertEquals(BardoCiEvidenceState.CI_FAILED, result.state)
        val gitleaks = result.evidence.single { it.check == BardoCheck.GITLEAKS }
        assertFalse(gitleaks.passed)
        assertTrue(result.problems.isNotEmpty())
    }

    @Test
    fun artifactMustBelongToTheSameRunAndCommitAndMustNotBeExpired() {
        val wrongRun = collect(artifacts = artifacts(artifactRunId = runId + 1))
        assertEquals(BardoCiEvidenceState.CI_FAILED, wrongRun.state)
        assertFalse(wrongRun.evidence.single { it.check == BardoCheck.DEBUG_BUILD }.passed)

        val wrongSha = collect(artifacts = artifacts(artifactSha = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
        assertEquals(BardoCiEvidenceState.CI_FAILED, wrongSha.state)

        val expired = collect(artifacts = artifacts(expired = true))
        assertEquals(BardoCiEvidenceState.CI_FAILED, expired.state)
    }

    @Test
    fun malformedExpectedShaCannotBeUsedToCreateEvidence() {
        val result = collect(expectedSha = "not-a-sha")
        assertEquals(BardoCiEvidenceState.INVALID, result.state)
        assertTrue(result.evidence.isEmpty())
    }
}
