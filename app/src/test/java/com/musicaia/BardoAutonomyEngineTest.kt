package com.musicaia

import com.musicaia.autonomia.BardoActionPlan
import com.musicaia.autonomia.BardoAutonomyEngine
import com.musicaia.autonomia.BardoAutonomyBudget
import com.musicaia.autonomia.BardoTaskExecutionGuard
import com.musicaia.autonomia.BardoAutonomyStatus
import com.musicaia.autonomia.toActivityState
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
    @Test fun perTaskBudgetRejectsUnsafeValuesAndLimitsPlanSteps() {
        try {
            BardoAutonomyBudget(maxTaskMillis = 9_999L)
            throw AssertionError("Expected invalid time budget to be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            BardoAutonomyBudget(maxSteps = 13)
            throw AssertionError("Expected invalid step budget to be rejected")
        } catch (_: IllegalArgumentException) { }

        val root = createTempDir(prefix = "bardo-autonomy-budget")
        try {
            val planner = BardoTaskPlanner { _, _, _ ->
                BardoActionPlan((1..3).map {
                    BardoTaskStep("test.echo", mapOf("value" to "ok"), "Step $it", "ok")
                }, 2)
            }
            val result = BardoAutonomyEngine(
                planner, tools(), BardoTaskJournal(root),
                budget = BardoAutonomyBudget(maxTaskMillis = 30_000L, maxSteps = 2, maxCorrections = 0)
            ).run("Respect per-task budget")
            assertEquals(BardoAutonomyStatus.BLOCKED, result.status)
            assertTrue(result.observations.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun activityStateNeverReportsPartialBlockedOrFailedTasksAsCompleted() {
        assertEquals("completed", BardoAutonomyStatus.COMPLETED.toActivityState())
        assertEquals("partial", BardoAutonomyStatus.PARTIAL.toActivityState())
        assertEquals("blocked", BardoAutonomyStatus.BLOCKED.toActivityState())
        assertEquals("failed", BardoAutonomyStatus.FAILED.toActivityState())
    }

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

    @Test fun resourceGuardStopsBeforeTheNextToolAndPreservesPartialWork() {
        val root = createTempDir(prefix = "bardo-autonomy-resource-guard")
        try {
            val checks = AtomicInteger()
            val planner = BardoTaskPlanner { _, _, _ ->
                BardoActionPlan(
                    listOf(
                        BardoTaskStep("test.echo", mapOf("value" to "first"), "First safe step", "first"),
                        BardoTaskStep("test.echo", mapOf("value" to "second"), "Second step", "second")
                    ),
                    correctionBudget = 0
                )
            }
            val result = BardoAutonomyEngine(
                planner,
                tools(),
                BardoTaskJournal(root),
                executionGuard = BardoTaskExecutionGuard {
                    if (checks.getAndIncrement() == 0) null else "RAM libre insuficiente"
                }
            ).run("Stop when device resources become unsafe")

            assertEquals(BardoAutonomyStatus.PARTIAL, result.status)
            assertEquals(1, result.observations.size)
            assertEquals("first", result.observations.single().output)
            assertTrue(BardoTaskJournal(root).readRecent().any {
                it.optString("event") == "resource_blocked"
            })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun resourceGuardBlocksBeforeAnyToolWhenInitialResourcesAreUnsafe() {
        val root = createTempDir(prefix = "bardo-autonomy-resource-blocked")
        try {
            val executionCount = AtomicInteger()
            val countedTool = object : BardoToolHandler {
                override val descriptor = BardoToolDescriptor(
                    "test.echo", "Returns a fixed response", BardoPermissionScope.READ_OWN_PROJECT
                )
                override fun execute(arguments: Map<String, String>): BardoToolOutput {
                    executionCount.incrementAndGet()
                    return BardoToolOutput(true, arguments["value"].orEmpty())
                }
            }
            val planner = BardoTaskPlanner { _, _, _ ->
                BardoActionPlan(listOf(
                    BardoTaskStep("test.echo", mapOf("value" to "never"), "Must not run", "never")
                ), 0)
            }
            val result = BardoAutonomyEngine(
                planner,
                BardoToolExecutor(listOf(countedTool)),
                BardoTaskJournal(root),
                executionGuard = BardoTaskExecutionGuard {
                    "El teléfono está demasiado caliente"
                }
            ).run("Do not act while the device is too hot")

            assertEquals(BardoAutonomyStatus.BLOCKED, result.status)
            assertTrue(result.observations.isEmpty())
            assertEquals(0, executionCount.get())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun toolSuccessWithoutOutputEvidenceIsRejected() {
        val root = createTempDir(prefix = "bardo-autonomy-empty-output")
        try {
            val planner = BardoTaskPlanner { _, _, _ ->
                BardoActionPlan(listOf(BardoTaskStep(
                    "test.echo", mapOf("value" to ""), "Require observable evidence", "evidence"
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

    @Test fun stepBudgetCountsStepsAcrossCorrectionsForTheWholeTask() {
        val root = createTempDir(prefix = "bardo-autonomy-total-step-budget")
        try {
            val plannerCalls = AtomicInteger()
            val planner = BardoTaskPlanner { _, _, reason ->
                if (plannerCalls.getAndIncrement() == 0) {
                    BardoActionPlan(
                        listOf(BardoTaskStep(
                            "test.echo", mapOf("value" to "wrong"), "First attempt", "right"
                        )),
                        correctionBudget = 1
                    )
                } else {
                    assertTrue(reason.orEmpty().contains("criterio"))
                    BardoActionPlan(
                        listOf(
                            BardoTaskStep("test.echo", mapOf("value" to "second"), "Corrected step 1", "second"),
                            BardoTaskStep("test.echo", mapOf("value" to "third"), "Corrected step 2", "third")
                        ),
                        correctionBudget = 0
                    )
                }
            }
            val result = BardoAutonomyEngine(
                planner,
                tools(),
                BardoTaskJournal(root),
                budget = BardoAutonomyBudget(
                    maxTaskMillis = 30_000L,
                    maxSteps = 2,
                    maxCorrections = 1
                )
            ).run("Respect total step budget across corrections")

            assertEquals(BardoAutonomyStatus.PARTIAL, result.status)
            assertEquals(2, result.observations.size)
            assertTrue(BardoTaskJournal(root).readRecent().any {
                it.optString("event") == "step_limit"
            })
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

    @Test fun refusesPlansWithoutAnObjectiveOutputCriterion() {
        val root = createTempDir(prefix = "bardo-autonomy-no-criterion")
        try {
            val executions = AtomicInteger()
            val countedTool = object : BardoToolHandler {
                override val descriptor = BardoToolDescriptor(
                    "test.echo", "Returns a fixed response", BardoPermissionScope.READ_OWN_PROJECT
                )
                override fun execute(arguments: Map<String, String>): BardoToolOutput {
                    executions.incrementAndGet()
                    return BardoToolOutput(true, "A non-empty but unverified response")
                }
            }
            val planner = BardoTaskPlanner { _, _, _ ->
                BardoActionPlan(listOf(
                    BardoTaskStep("test.echo", emptyMap(), "Read something without a verifiable success condition")
                ), 0)
            }
            val result = BardoAutonomyEngine(
                planner, BardoToolExecutor(listOf(countedTool)), BardoTaskJournal(root)
            ).run("Never report success without an objective criterion")
            assertEquals(BardoAutonomyStatus.BLOCKED, result.status)
            assertTrue(result.observations.isEmpty())
            assertEquals(0, executions.get())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun refusesPlansWithTooManyStepsOrNoPurpose() {
        val root = createTempDir(prefix = "bardo-autonomy-invalid")
        try {
            val tooMany = BardoTaskPlanner { _, _, _ ->
                BardoActionPlan((1..13).map {
                    BardoTaskStep("test.echo", emptyMap(), "step", "unused")
                })
            }
            assertEquals(
                BardoAutonomyStatus.BLOCKED,
                BardoAutonomyEngine(tooMany, tools(), BardoTaskJournal(root)).run("Goal").status
            )

            val noPurpose = BardoTaskPlanner { _, _, _ ->
                BardoActionPlan(listOf(BardoTaskStep("test.echo", emptyMap(), "", "unused")))
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
                    "test.echo", mapOf("value" to "PRIVATE-CONTENT"), "Echo", "PRIVATE-CONTENT"
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
