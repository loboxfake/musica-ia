package com.musicaia

import com.musicaia.herramientas.BardoToolDescriptor
import com.musicaia.herramientas.BardoToolExecutor
import com.musicaia.herramientas.BardoToolHandler
import com.musicaia.herramientas.BardoToolOutput
import com.musicaia.seguridad.BardoPermissionScope
import com.musicaia.skills.BardoSkillManager
import com.musicaia.skills.BardoSkillManifest
import com.musicaia.skills.BardoSkillPackageKind
import com.musicaia.skills.BardoSkillPermission
import com.musicaia.skills.BardoSkillSmokeTestReport
import com.musicaia.skills.BardoSkillWorkflowExecutor
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

class BardoSkillWorkflowExecutorTest {
    private class EchoTool(
        override val descriptor: BardoToolDescriptor,
        private val response: String,
        private val fail: Boolean = false
    ) : BardoToolHandler {
        var calls = 0
        var lastArguments: Map<String, String> = emptyMap()

        override fun execute(arguments: Map<String, String>): BardoToolOutput {
            calls++
            lastArguments = arguments
            return if (fail) BardoToolOutput(false, "", "forced-failure")
            else BardoToolOutput(true, response + arguments.values.joinToString())
        }
    }

    private data class Fixture(
        val root: File,
        val manager: BardoSkillManager,
        val tools: BardoToolExecutor,
        val search: EchoTool,
        val inventory: EchoTool
    )

    private fun fixture(packageBytes: ByteArray): Fixture {
        val root = createTempDir(prefix = "bardo-skill-workflow")
        val search = EchoTool(
            BardoToolDescriptor(
                "knowledge.search", "Busca en conocimiento local.",
                BardoPermissionScope.READ_BARDO_DATA, maxArguments = 1
            ),
            "found:"
        )
        val inventory = EchoTool(
            BardoToolDescriptor(
                "self_inventory.read", "Lee el inventario propio.",
                BardoPermissionScope.READ_BARDO_DATA, maxArguments = 0
            ),
            "inventory:"
        )
        val tools = BardoToolExecutor(listOf(search, inventory))
        val runner = com.musicaia.skills.BardoSkillSmokeTestRunner { _, _ ->
            BardoSkillSmokeTestReport(
                true, "workflow-smoke-v1",
                listOf("test://workflow/trusted-smoke"),
                "Trusted handler smoke-test passed."
            )
        }
        val manager = BardoSkillManager(
            File(root, "skills"),
            registeredActionIds = { tools.registeredTools().map { it.id }.toSet() },
            smokeTestRunner = runner
        )
        val manifest = BardoSkillManifest(
            id = "skill.local.research",
            name = "Local research workflow",
            version = "1.0.0",
            apiMinimum = 1,
            apiMaximum = 1,
            description = "Secuencia declarativa de lectura local.",
            source = "local://unit-test",
            license = "Apache-2.0",
            packageSha256 = MessageDigest.getInstance("SHA-256").digest(packageBytes)
                .joinToString("") { "%02x".format(it) },
            packageSizeBytes = packageBytes.size.toLong(),
            kind = BardoSkillPackageKind.DECLARATIVE_TOOLCHAIN,
            actions = setOf("knowledge.search", "self_inventory.read"),
            permissions = setOf(BardoSkillPermission.READ_BARDO_DATA),
            licenseAcceptedByUser = true
        )
        val imported = manager.importToQuarantine(manifest, ByteArrayInputStream(packageBytes), true)
        check(imported.succeeded) { imported.message }
        val installed = manager.install(manifest.id, manifest.version, true)
        check(installed.succeeded) { installed.message }
        val enabled = manager.enable(
            manifest.id, manifest.version, true, manifest.permissions
        )
        check(enabled.succeeded) { enabled.message }
        return Fixture(root, manager, tools, search, inventory)
    }

    private fun validPackage() = """
        {
          "schema_version": 1,
          "steps": [
            {
              "action_id": "knowledge.search",
              "arguments": { "query": "{{input.query}}" }
            },
            {
              "action_id": "self_inventory.read",
              "arguments": {}
            }
          ]
        }
    """.trimIndent().toByteArray(Charsets.UTF_8)

    @Test fun executesOnlyDeclaredWorkflowActionsAndResolvesTypedInputs() {
        val fixture = fixture(validPackage())
        try {
            val report = BardoSkillWorkflowExecutor(fixture.manager, fixture.tools).execute(
                "skill.local.research", "1.0.0", mapOf("query" to "audio offline")
            )

            assertTrue(report.error.orEmpty(), report.succeeded)
            assertEquals(2, report.completedSteps)
            assertEquals(1, fixture.search.calls)
            assertEquals("audio offline", fixture.search.lastArguments["query"])
            assertEquals(1, fixture.inventory.calls)
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test fun refusesMissingWorkflowInputsBeforeExecutingAnyAction() {
        val fixture = fixture(validPackage())
        try {
            val report = BardoSkillWorkflowExecutor(fixture.manager, fixture.tools).execute(
                "skill.local.research", "1.0.0", emptyMap()
            )

            assertFalse(report.succeeded)
            assertEquals(0, fixture.search.calls)
            assertEquals(0, fixture.inventory.calls)
            assertTrue(report.error.orEmpty().contains("query"))
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test fun rejectsWorkflowActionThatIsNotDeclaredInManifest() {
        val invalid = """
            {
              "schema_version": 1,
              "steps": [
                { "action_id": "developer.audit_snapshot", "arguments": {} },
                { "action_id": "self_inventory.read", "arguments": {} }
              ]
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val fixture = fixture(invalid)
        try {
            val report = BardoSkillWorkflowExecutor(fixture.manager, fixture.tools).execute(
                "skill.local.research", "1.0.0"
            )
            assertFalse(report.succeeded)
            assertEquals(0, fixture.search.calls)
            assertTrue(report.error.orEmpty().contains("no declarada"))
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test fun stopsImmediatelyWhenAToolFails() {
        val bytes = """
            {
              "schema_version": 1,
              "steps": [
                { "action_id": "knowledge.search", "arguments": { "query": "BARDO" } },
                { "action_id": "self_inventory.read", "arguments": {} }
              ]
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val fixture = fixture(bytes)
        try {
            fixture.search.descriptor
            // The test handler is replaced only in a separate executor to force a tool-level denial.
            val failing = EchoTool(
                BardoToolDescriptor(
                    "knowledge.search", "Busca en conocimiento local.",
                    BardoPermissionScope.READ_BARDO_DATA, maxArguments = 1
                ),
                "", fail = true
            )
            val failingTools = BardoToolExecutor(listOf(failing, fixture.inventory))
            val report = BardoSkillWorkflowExecutor(fixture.manager, failingTools).execute(
                "skill.local.research", "1.0.0"
            )
            assertFalse(report.succeeded)
            assertEquals(1, report.completedSteps)
            assertEquals(1, failing.calls)
            assertEquals(0, fixture.inventory.calls)
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test fun packageTamperingRevokesSkillBeforeWorkflowRuns() {
        val fixture = fixture(validPackage())
        try {
            val installedPath = fixture.manager.list().single().installedPath!!
            File(installedPath).writeText("{}")
            val report = BardoSkillWorkflowExecutor(fixture.manager, fixture.tools).execute(
                "skill.local.research", "1.0.0", mapOf("query" to "BARDO")
            )
            assertFalse(report.succeeded)
            assertEquals(0, fixture.search.calls)
            assertEquals(0, fixture.inventory.calls)
        } finally {
            fixture.root.deleteRecursively()
        }
    }
}
