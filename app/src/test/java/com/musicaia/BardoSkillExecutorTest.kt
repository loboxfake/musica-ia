package com.musicaia

import com.musicaia.herramientas.BardoToolDescriptor
import com.musicaia.herramientas.BardoToolExecutor
import com.musicaia.herramientas.BardoToolHandler
import com.musicaia.herramientas.BardoToolOutput
import com.musicaia.herramientas.BardoToolSmokeTestHandler
import com.musicaia.seguridad.BardoPermissionScope
import com.musicaia.skills.BardoSkillActionInvocation
import com.musicaia.skills.BardoSkillExecutionStatus
import com.musicaia.skills.BardoSkillExecutor
import com.musicaia.skills.BardoSkillLifecycle
import com.musicaia.skills.BardoSkillManager
import com.musicaia.skills.BardoSkillToolHandler
import com.musicaia.skills.BardoSkillManifest
import com.musicaia.skills.BardoSkillManifestValidator
import com.musicaia.skills.BardoSkillPackageKind
import com.musicaia.skills.BardoSkillPermission
import com.musicaia.skills.BardoSkillSmokeTestReport
import com.musicaia.skills.BardoSkillSmokeTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class BardoSkillExecutorTest {
    private class EchoTool(
        override val descriptor: BardoToolDescriptor,
        private val output: String
    ) : BardoToolHandler, BardoToolSmokeTestHandler {
        override fun execute(arguments: Map<String, String>): BardoToolOutput =
            BardoToolOutput(true, output + arguments.values.joinToString())

        override fun smokeTest(): BardoToolOutput =
            BardoToolOutput(true, "trusted APK smoke-test passed")
    }

    private fun manifest(bytes: ByteArray, actions: Set<String>) = BardoSkillManifest(
        id = "skill.read.bundle",
        name = "Read bundle",
        version = "1.0.0",
        apiMinimum = 1,
        apiMaximum = 1,
        description = "Conjunto declarativo de herramientas de lectura.",
        source = "local://test",
        license = "Apache-2.0",
        packageSha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) },
        packageSizeBytes = bytes.size.toLong(),
        kind = BardoSkillPackageKind.DECLARATIVE_TOOLCHAIN,
        actions = actions,
        permissions = setOf(BardoSkillPermission.READ_BARDO_DATA),
        licenseAcceptedByUser = true
    )

    private fun setup(root: File, scope: BardoPermissionScope = BardoPermissionScope.READ_BARDO_DATA):
        Pair<BardoSkillManager, BardoToolExecutor> {
        val tools = BardoToolExecutor(listOf(
            EchoTool(
                BardoToolDescriptor(
                    "self_inventory.read", "inventory", scope, maxArguments = 0
                ),
                "inventory-ok"
            ),
            EchoTool(
                BardoToolDescriptor(
                    "knowledge.search", "knowledge", BardoPermissionScope.READ_BARDO_DATA,
                    maxArguments = 1
                ),
                "knowledge:"
            ),
            EchoTool(
                BardoToolDescriptor(
                    "developer.ci_diagnostics", "diagnostics",
                    BardoPermissionScope.READ_BARDO_DATA, maxArguments = 0
                ),
                "diagnostics-ok"
            )
        ))
        val runner = BardoSkillSmokeTestRunner { _, _ ->
            BardoSkillSmokeTestReport(
                true,
                "unit-skill-smoke-v1",
                listOf("test://trusted-smoke/one"),
                "Trusted smoke-test passed."
            )
        }
        val manager = BardoSkillManager(
            root,
            registeredActionIds = { tools.registeredSkillToolIds() },
            smokeTestRunner = runner
        )
        return manager to tools
    }

    private fun installAndEnable(
        manager: BardoSkillManager,
        actions: Set<String>
    ): BardoSkillManifest {
        val bytes = "declarative-read-skill-v1".toByteArray()
        val manifest = manifest(bytes, actions)
        val imported = manager.importToQuarantine(manifest, ByteArrayInputStream(bytes), true)
        check(imported.succeeded) { imported.message }
        val installed = manager.install(manifest.id, manifest.version, true)
        check(installed.succeeded) { installed.message }
        val enabled = manager.enable(
            manifest.id,
            manifest.version,
            explicitlyApproved = true,
            approvedPermissions = manifest.permissions
        )
        check(enabled.succeeded) { enabled.message }
        return manifest
    }

    @Test fun executesOnlyDeclaredRegisteredActionsInSequence() {
        val root = createTempDir(prefix = "bardo-skill-run-ok")
        try {
            val (manager, tools) = setup(root)
            val actions = setOf("self_inventory.read", "knowledge.search")
            val manifest = installAndEnable(manager, actions)

            val report = BardoSkillExecutor(manager, tools).execute(
                manifest.id,
                manifest.version,
                listOf(
                    BardoSkillActionInvocation("self_inventory.read"),
                    BardoSkillActionInvocation("knowledge.search", mapOf("query" to "BARDO"))
                )
            )

            assertEquals(BardoSkillExecutionStatus.COMPLETED, report.status)
            assertEquals(2, report.steps.size)
            assertTrue(report.steps.all { it.succeeded })
            assertTrue(report.steps[0].output.contains("inventory-ok"))
            assertTrue(report.steps[1].output.contains("knowledge:BARDO"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun refusesAnActionNotDeclaredByTheSkillEvenWhenToolIsRegistered() {
        val root = createTempDir(prefix = "bardo-skill-run-undeclared")
        try {
            val (manager, tools) = setup(root)
            val manifest = installAndEnable(manager, setOf("self_inventory.read"))

            val report = BardoSkillExecutor(manager, tools).execute(
                manifest.id,
                manifest.version,
                listOf(BardoSkillActionInvocation("developer.ci_diagnostics"))
            )

            assertEquals(BardoSkillExecutionStatus.REJECTED, report.status)
            assertTrue(report.summary.contains("no declaradas"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun legacySkillCannotExecuteAnActionWhoseHandlerLostTrustedSmokeTest() {
        val root = createTempDir(prefix = "bardo-skill-run-smoke-revoked")
        try {
            val (manager, trustedTools) = setup(root)
            val manifest = installAndEnable(manager, setOf("knowledge.search"))
            var calls = 0
            val untrustedReplacement = object : BardoToolHandler {
                override val descriptor = BardoToolDescriptor(
                    "knowledge.search", "replacement without trusted smoke test",
                    BardoPermissionScope.READ_BARDO_DATA, maxArguments = 1
                )
                override fun execute(arguments: Map<String, String>): BardoToolOutput {
                    calls++
                    return BardoToolOutput(true, "must-not-run")
                }
            }
            val trustedInventory = object : BardoToolHandler, BardoToolSmokeTestHandler {
                override val descriptor = BardoToolDescriptor(
                    "self_inventory.read", "trusted inventory",
                    BardoPermissionScope.READ_BARDO_DATA, maxArguments = 0
                )
                override fun execute(arguments: Map<String, String>) =
                    BardoToolOutput(true, "inventory")
                override fun smokeTest() = BardoToolOutput(true, "trusted")
            }
            val downgradedCatalog = BardoToolExecutor(listOf(untrustedReplacement, trustedInventory))
            val report = BardoSkillExecutor(manager, downgradedCatalog).execute(
                manifest.id,
                manifest.version,
                listOf(BardoSkillActionInvocation("knowledge.search", mapOf("query" to "legacy")))
            )
            assertEquals(BardoSkillExecutionStatus.REJECTED, report.status)
            assertEquals(0, calls)
            assertTrue(report.summary.contains("registradas"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun enabledSkillIsBlockedWhenTrustedSmokeTestStartsFailing() {
        val root = createTempDir(prefix = "bardo-skill-run-smoke-failed")
        try {
            val (manager, _) = setup(root)
            val manifest = installAndEnable(manager, setOf("knowledge.search"))
            var calls = 0
            val failingSmokeTool = object : BardoToolHandler, BardoToolSmokeTestHandler {
                override val descriptor = BardoToolDescriptor(
                    "knowledge.search", "search with failing smoke test",
                    BardoPermissionScope.READ_BARDO_DATA, maxArguments = 1
                )
                override fun execute(arguments: Map<String, String>): BardoToolOutput {
                    calls++
                    return BardoToolOutput(true, "must-not-run")
                }
                override fun smokeTest() = BardoToolOutput(false, "", "regression")
            }
            val trustedInventory = object : BardoToolHandler, BardoToolSmokeTestHandler {
                override val descriptor = BardoToolDescriptor(
                    "self_inventory.read", "trusted inventory",
                    BardoPermissionScope.READ_BARDO_DATA, 0
                )
                override fun execute(arguments: Map<String, String>) = BardoToolOutput(true, "inventory")
                override fun smokeTest() = BardoToolOutput(true, "trusted")
            }
            val runtimeTools = BardoToolExecutor(listOf(failingSmokeTool, trustedInventory))
            val report = BardoSkillExecutor(manager, runtimeTools).execute(
                manifest.id, manifest.version,
                listOf(BardoSkillActionInvocation("knowledge.search", mapOf("query" to "test")))
            )
            assertEquals(BardoSkillExecutionStatus.REJECTED, report.status)
            assertEquals(0, calls)
            assertTrue(report.summary.contains("no están verificadas"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun globalBardoAuthorityStillBlocksToolScopesOutsideAutonomousSafeSet() {
        val root = createTempDir(prefix = "bardo-skill-run-scope")
        try {
            val (manager, tools) = setup(root, BardoPermissionScope.RESEARCH_NETWORK)
            val manifest = installAndEnable(manager, setOf("self_inventory.read"))

            val report = BardoSkillExecutor(manager, tools).execute(
                manifest.id,
                manifest.version,
                listOf(BardoSkillActionInvocation("self_inventory.read"))
            )

            assertEquals(BardoSkillExecutionStatus.FAILED, report.status)
            assertFalse(report.steps.single().succeeded)
            assertTrue(report.steps.single().error.orEmpty().contains("BARDO no puede"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun alteredInstalledPackageRevokesEnabledStateBeforeExecution() {
        val root = createTempDir(prefix = "bardo-skill-run-tamper")
        try {
            val (manager, tools) = setup(root)
            val manifest = installAndEnable(manager, setOf("self_inventory.read"))
            val installedPath = manager.list().single().installedPath!!
            File(installedPath).writeText("tampered")

            val report = BardoSkillExecutor(manager, tools).execute(
                manifest.id,
                manifest.version,
                listOf(BardoSkillActionInvocation("self_inventory.read"))
            )

            assertEquals(BardoSkillExecutionStatus.REJECTED, report.status)
            assertEquals(BardoSkillLifecycle.REJECTED, manager.list().single().lifecycle)
        } finally {
            root.deleteRecursively()
        }
    }
    @Test fun plannerBridgeRunsOnlyAnApprovedEnabledSkillAction() {
        val root = createTempDir(prefix = "bardo-skill-bridge")
        try {
            val (manager, coreTools) = setup(root)
            val manifest = installAndEnable(manager, setOf("knowledge.search"))
            val bridge = BardoSkillToolHandler(BardoSkillExecutor(manager, coreTools))

            val result = bridge.execute(
                mapOf(
                    "skill_id" to manifest.id,
                    "version" to manifest.version,
                    "action_id" to "knowledge.search",
                    "action_arguments_json" to """{"query":"BARDO"}"""
                )
            )

            assertTrue(result.error.orEmpty(), result.succeeded)
            assertTrue(result.content.contains("knowledge:BARDO"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun plannerBridgeRejectsMalformedActionArguments() {
        val root = createTempDir(prefix = "bardo-skill-bridge-invalid")
        try {
            val (manager, coreTools) = setup(root)
            val manifest = installAndEnable(manager, setOf("knowledge.search"))
            val bridge = BardoSkillToolHandler(BardoSkillExecutor(manager, coreTools))

            val result = bridge.execute(
                mapOf(
                    "skill_id" to manifest.id,
                    "version" to manifest.version,
                    "action_id" to "knowledge.search",
                    "action_arguments_json" to """{"query":42}"""
                )
            )

            assertFalse(result.succeeded)
            assertTrue(result.error.orEmpty().contains("cadenas acotadas"))
        } finally {
            root.deleteRecursively()
        }
    }

}
