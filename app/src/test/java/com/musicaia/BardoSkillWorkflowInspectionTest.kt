package com.musicaia

import com.musicaia.herramientas.BardoToolDescriptor
import com.musicaia.herramientas.BardoToolExecutor
import com.musicaia.herramientas.BardoToolHandler
import com.musicaia.herramientas.BardoToolOutput
import com.musicaia.seguridad.BardoPermissionScope
import com.musicaia.skills.BardoSkillLifecycle
import com.musicaia.skills.BardoSkillManager
import com.musicaia.skills.BardoSkillManifest
import com.musicaia.skills.BardoSkillPackageKind
import com.musicaia.skills.BardoSkillPermission
import com.musicaia.skills.BardoSkillSmokeTestReport
import com.musicaia.skills.BardoSkillSmokeTestRunner
import com.musicaia.skills.BardoSkillWorkflowExecutor
import com.musicaia.skills.BardoSkillWorkflowToolHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

class BardoSkillWorkflowInspectionTest {
    private class CountingSearch : BardoToolHandler {
        override val descriptor = BardoToolDescriptor(
            id = "knowledge.search",
            description = "Busca en conocimiento local.",
            requiredScope = BardoPermissionScope.READ_BARDO_DATA,
            maxArguments = 1,
            maxValueChars = 2_000
        )
        var calls = 0

        override fun execute(arguments: Map<String, String>): BardoToolOutput {
            calls++
            return BardoToolOutput(true, "resultado")
        }
    }

    private data class Fixture(
        val root: File,
        val manager: BardoSkillManager,
        val tools: BardoToolExecutor,
        val search: CountingSearch,
        val executor: BardoSkillWorkflowExecutor
    )

    private fun fixture(packageText: String): Fixture {
        val root = createTempDir(prefix = "bardo-workflow-inspection")
        val bytes = packageText.toByteArray(Charsets.UTF_8)
        val search = CountingSearch()
        val tools = BardoToolExecutor(listOf(search))
        val runner = BardoSkillSmokeTestRunner { _, _ ->
            BardoSkillSmokeTestReport(
                passed = true,
                verifierId = "test-core-runner-v1",
                evidenceRefs = listOf("test://workflow/inspection"),
                note = "Prueba de confianza simulada en test."
            )
        }
        val manager = BardoSkillManager(
            rootDirectory = File(root, "skills"),
            registeredActionIds = { setOf("knowledge.search") },
            smokeTestRunner = runner
        )
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val manifest = BardoSkillManifest(
            id = "skill.local.inspect",
            name = "Local inspect workflow",
            version = "1.0.0",
            apiMinimum = 1,
            apiMaximum = 1,
            description = "Workflow declarativo para pruebas de inspección.",
            source = "local://unit-test",
            license = "Apache-2.0",
            packageSha256 = digest,
            packageSizeBytes = bytes.size.toLong(),
            kind = BardoSkillPackageKind.DECLARATIVE_TOOLCHAIN,
            actions = setOf("knowledge.search"),
            permissions = setOf(BardoSkillPermission.READ_BARDO_DATA),
            licenseAcceptedByUser = true
        )
        val imported = manager.importToQuarantine(manifest, ByteArrayInputStream(bytes), true)
        check(imported.succeeded) { imported.message }
        val installed = manager.install(manifest.id, manifest.version, true)
        check(installed.succeeded) { installed.message }
        val enabled = manager.enable(
            manifest.id, manifest.version, true, manifest.permissions
        )
        check(enabled.succeeded) { enabled.message }
        return Fixture(
            root, manager, tools, search,
            BardoSkillWorkflowExecutor(manager, tools)
        )
    }

    private fun validWorkflow() = """
        {
          "schema_version": 1,
          "steps": [
            {
              "action_id": "knowledge.search",
              "arguments": { "query": "{{input.query}}" }
            }
          ]
        }
    """.trimIndent()

    @Test fun inspectionReportsPlanWithoutExecutingAnyAction() {
        val fixture = fixture(validWorkflow())
        try {
            val report = fixture.executor.inspect("skill.local.inspect", "1.0.0")

            assertTrue(report.error.orEmpty(), report.succeeded)
            assertEquals(1, report.stepCount)
            assertEquals(listOf("knowledge.search"), report.actionIds)
            assertEquals(setOf("query"), report.requiredInputs)
            assertTrue(report.packageSha256.orEmpty().matches(Regex("^[0-9a-f]{64}$")))
            assertTrue(report.summary.contains("No se ejecutó"))
            assertEquals(0, fixture.search.calls)
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test fun malformedMarkerInLaterStepIsRejectedBeforeFirstToolRuns() {
        val invalid = """
            {
              "schema_version": 1,
              "steps": [
                {
                  "action_id": "knowledge.search",
                  "arguments": { "query": "first-step" }
                },
                {
                  "action_id": "knowledge.search",
                  "arguments": { "query": "prefix-{{input.query}}" }
                }
              ]
            }
        """.trimIndent()
        val fixture = fixture(invalid)
        try {
            val report = fixture.executor.execute(
                "skill.local.inspect", "1.0.0", emptyMap()
            )

            assertFalse(report.succeeded)
            assertEquals(0, report.completedSteps)
            assertTrue(report.error.orEmpty().contains("Marcador de entrada mal formado"))
            assertEquals(0, fixture.search.calls)
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test fun inspectionRejectsUndeclaredActionWithoutExecutingTools() {
        val invalid = """
            {
              "schema_version": 1,
              "steps": [
                {
                  "action_id": "developer.audit_snapshot",
                  "arguments": {}
                }
              ]
            }
        """.trimIndent()
        val fixture = fixture(invalid)
        try {
            val report = fixture.executor.inspect("skill.local.inspect", "1.0.0")

            assertFalse(report.succeeded)
            assertTrue(report.error.orEmpty().contains("no declarada"))
            assertEquals(0, fixture.search.calls)
            assertEquals(BardoSkillLifecycle.ENABLED, fixture.manager.list().single().lifecycle)
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test fun plannerBridgeCanInspectWithoutExecutingWorkflow() {
        val fixture = fixture(validWorkflow())
        try {
            val handler = BardoSkillWorkflowToolHandler(fixture.executor)
            val result = handler.execute(
                mapOf(
                    "skill_id" to "skill.local.inspect",
                    "version" to "1.0.0",
                    "operation" to "inspect"
                )
            )

            assertTrue(result.error.orEmpty(), result.succeeded)
            assertTrue(result.content.contains("Entradas requeridas: query"))
            assertTrue(result.content.contains("SHA-256:"))
            assertEquals(0, fixture.search.calls)
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test fun inspectionRejectsPackageTamperingAndNeverRunsActions() {
        val fixture = fixture(validWorkflow())
        try {
            val installedPath = fixture.manager.list().single().installedPath!!
            File(installedPath).writeText(validWorkflow() + " ")
            val report = fixture.executor.inspect("skill.local.inspect", "1.0.0")

            assertFalse(report.succeeded)
            assertEquals(0, fixture.search.calls)
            assertEquals(BardoSkillLifecycle.REJECTED, fixture.manager.list().single().lifecycle)
        } finally {
            fixture.root.deleteRecursively()
        }
    }
}
