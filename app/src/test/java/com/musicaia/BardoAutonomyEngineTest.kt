package com.musicaia

import com.musicaia.autonomia.BardoActionPlan
import com.musicaia.autonomia.BardoAutonomyEngine
import com.musicaia.autonomia.BardoAutonomyStatus
import com.musicaia.autonomia.BardoTaskJournal
import com.musicaia.autonomia.BardoTaskPlanner
import com.musicaia.autonomia.BardoTaskStep
import com.musicaia.herramientas.BardoToolDescriptor
import com.musicaia.herramientas.BardoToolExecutor
import com.musicaia.herramientas.BardoToolHandler
import com.musicaia.herramientas.BardoToolOutput
import com.musicaia.seguridad.BardoPermissionScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class BardoAutonomyEngineTest {
    private class EchoTool : BardoToolHandler {
        override val descriptor = BardoToolDescriptor(
            "test.echo", "Returns a fixed response", BardoPermissionScope.READ_OWN_PROJECT
        )
        override fun execute(arguments: Map<String, String>) =
            BardoToolOutput(true, arguments["value"].orEmpty())
    }

    private fun tools() = BardoToolExecutor(listOf(EchoTool()))

    @Test fun planActObserveEvaluateCompletesWithObjectiveCriterion() {
        val root = createTempDir(prefix = "bardo-autonomy-success")
        try {
            val planner = BardoTaskPlanner { _, _, _ ->
                BardoActionPlan(listOf(BardoTaskStep(
                    "test.echo", mapOf("value" to "verified"), "Echo verification", "verified"
                )), 0)
            }
            val result = BardoAutonomyEngine(planner, tools(), BardoTaskJournal(root)).run("Verify a value")
            assertEquals(BardoAutonomyStatus.COMPLETED, result.status)
            assertTrue(result.observations.single().succeeded)
            assertTrue(BardoTaskJournal(root).readRecent().any { it.optString("event") == "task_finished" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun toolSuccessWithoutOutputEvidenceIsRejected() {
        val root = createTempDir(prefix = "bardo-autonomy-empty-output")
        try {
            val planner = BardoTaskPlanner { _, _, _ ->
                BardoActionPlan(listOf(BardoTaskStep(
                    "test.echo", mapOf("value" to ""), "Require observable evidence"
                )), 0)
            }
            val result = BardoAutonomyEngine(planner, tools(), BardoTaskJournal(root)).run("Verify output")
            assertEquals(BardoAutonomyStatus.PARTIAL, result.status)
            assertTrue(!result.observations.single().succeeded)
            assertTrue(result.observations.single().error.orEmpty().contains("evidencia"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun failedCriterionInvokesOneCorrectionAndCanComplete() {
        val root = createTempDir(prefix = "bardo-autonomy-correction")
        try {
            val calls = AtomicInteger()
            val planner = BardoTaskPlanner { _, observations, reason ->
                if (calls.getAndIncrement() == 0) {
                    BardoActionPlan(listOf(BardoTaskStep(
                        "test.echo", mapOf("value" to "wrong"), "First attempt", "right"
                    )), 1)
                } else {
                    assertEquals(1, observations.size)
                    assertTrue(reason.orEmpty().contains("criterio"))
                    BardoActionPlan(listOf(BardoTaskStep(
                        "test.echo", mapOf("value" to "right"), "Correction", "right"
                    )), 0)
                }
            }
            val result = BardoAutonomyEngine(planner, tools(), BardoTaskJournal(root)).run("Verify a value")
            assertEquals(BardoAutonomyStatus.COMPLETED, result.status)
            assertEquals(1, result.correctionsUsed)
            assertEquals(2, result.observations.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun refusesPlansWithTooManyStepsOrNoPurpose() {
        val root = createTempDir(prefix = "bardo-autonomy-invalid")
        try {
            val tooMany = BardoTaskPlanner { _, _, _ ->
                BardoActionPlan((1..13).map {
                    BardoTaskStep("test.echo", emptyMap(), "step")
                })
            }
            assertEquals(
                BardoAutonomyStatus.BLOCKED,
                BardoAutonomyEngine(tooMany, tools(), BardoTaskJournal(root)).run("Goal").status
            )

            val noPurpose = BardoTaskPlanner { _, _, _ ->
                BardoActionPlan(listOf(BardoTaskStep("test.echo", emptyMap(), "")))
            }
            assertEquals(
                BardoAutonomyStatus.BLOCKED,
                BardoAutonomyEngine(noPurpose, tools(), BardoTaskJournal(root)).run("Goal").status
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun journalDoesNotExposeToolArgumentsOrOutputContents() {
        val root = createTempDir(prefix = "bardo-autonomy-journal")
        try {
            val planner = BardoTaskPlanner { _, _, _ ->
                BardoActionPlan(listOf(BardoTaskStep(
                    "test.echo", mapOf("value" to "PRIVATE-CONTENT"), "Echo"
                )), 0)
            }
            BardoAutonomyEngine(planner, tools(), BardoTaskJournal(root)).run("Goal")
            val contents = File(root, "bardo_task_journal.jsonl").readText()
            assertTrue(!contents.contains("PRIVATE-CONTENT"))
        } finally {
            root.deleteRecursively()
        }
    }
}
