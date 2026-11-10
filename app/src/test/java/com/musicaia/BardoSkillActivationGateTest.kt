package com.musicaia

import com.musicaia.skills.BardoSkillActivationGate
import com.musicaia.skills.BardoSkillLifecycle
import com.musicaia.skills.BardoSkillManager
import com.musicaia.skills.BardoSkillManifest
import com.musicaia.skills.BardoSkillManifestValidator
import com.musicaia.skills.BardoSkillPackageKind
import com.musicaia.skills.BardoSkillPermission
import com.musicaia.skills.BardoSkillSmokeTestReport
import com.musicaia.skills.BardoSkillSmokeTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoSkillActivationGateTest {
    private val actions = setOf("knowledge.search")

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun workflow(query: String): ByteArray = """
        {
          "schema_version": 1,
          "steps": [
            {
              "action_id": "knowledge.search",
              "arguments": { "query": "$query" }
            }
          ]
        }
    """.trimIndent().toByteArray(Charsets.UTF_8)

    private fun manifest(bytes: ByteArray) = BardoSkillManifest(
        id = "skill.preflight.test",
        name = "Preflight test",
        version = "1.0.0",
        apiMinimum = 1,
        apiMaximum = 1,
        description = "Skill declarativa para verificar el gate previo a la activación.",
        source = "local://unit-test",
        license = "Apache-2.0",
        packageSha256 = sha256(bytes),
        packageSizeBytes = bytes.size.toLong(),
        kind = BardoSkillPackageKind.DECLARATIVE_TOOLCHAIN,
        actions = actions,
        permissions = setOf(BardoSkillPermission.READ_BARDO_DATA),
        licenseAcceptedByUser = true
    )

    private data class Fixture(
        val root: File,
        val manager: BardoSkillManager,
        val manifest: BardoSkillManifest
    )

    private fun fixture(bytes: ByteArray): Fixture {
        val root = createTempDir(prefix = "bardo-skill-activation-gate")
        val skillManifest = manifest(bytes)
        val manager = BardoSkillManager(
            rootDirectory = File(root, "skills"),
            registeredActionIds = { actions },
            smokeTestRunner = BardoSkillSmokeTestRunner { _, _ ->
                BardoSkillSmokeTestReport(
                    passed = true,
                    verifierId = "test-core-runner-v1",
                    evidenceRefs = listOf("test://skill/preflight/smoke"),
                    note = "Smoke-test de confianza simulado en test."
                )
            }
        )
        val imported = manager.importToQuarantine(
            skillManifest,
            ByteArrayInputStream(bytes),
            userAcceptedLicense = true
        )
        check(imported.succeeded) { imported.message }
        val installed = manager.install(skillManifest.id, skillManifest.version, true)
        check(installed.succeeded) { installed.message }
        return Fixture(root, manager, skillManifest)
    }

    @Test
    fun validStoredWorkflowPassesPreflightBeforeTrustedSmokeTestAndActivation() {
        val fixture = fixture(workflow("{{input:query}}"))
        try {
            val gate = BardoSkillActivationGate(fixture.manager, actions)
            val result = gate.enable(
                fixture.manifest.id,
                fixture.manifest.version,
                explicitlyApproved = true,
                approvedPermissions = fixture.manifest.permissions
            )

            assertTrue(result.message, result.succeeded)
            assertEquals(BardoSkillLifecycle.ENABLED, result.record?.lifecycle)
            assertEquals("test-core-runner-v1", result.record?.lastTestVerifierId)
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun malformedStoredWorkflowRemainsInstalledDisabledAndNeverActivates() {
        val fixture = fixture(workflow("prefix-{{input:query}}"))
        try {
            val result = BardoSkillActivationGate(fixture.manager, actions).enable(
                fixture.manifest.id,
                fixture.manifest.version,
                explicitlyApproved = true,
                approvedPermissions = fixture.manifest.permissions
            )

            assertFalse(result.succeeded)
            assertTrue(result.message.contains("Preflight del workflow rechazado"))
            assertTrue(result.message.contains("Marcador de entrada mal formado"))
            assertEquals(BardoSkillLifecycle.INSTALLED_DISABLED, fixture.manager.list().single().lifecycle)
            assertTrue(fixture.manager.enabled().isEmpty())
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun workflowCannotActivateWhenActionIsNoLongerRegistered() {
        val fixture = fixture(workflow("{{input:query}}"))
        try {
            val gate = BardoSkillActivationGate(fixture.manager, emptySet())
            val result = gate.enable(
                fixture.manifest.id,
                fixture.manifest.version,
                explicitlyApproved = true,
                approvedPermissions = fixture.manifest.permissions
            )

            assertFalse(result.succeeded)
            assertTrue(result.message.contains("no registrada"))
            assertEquals(BardoSkillLifecycle.INSTALLED_DISABLED, fixture.manager.list().single().lifecycle)
            assertTrue(fixture.manager.enabled().isEmpty())
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun activationStillRequiresExplicitUserApproval() {
        val fixture = fixture(workflow("{{input:query}}"))
        try {
            val result = BardoSkillActivationGate(fixture.manager, actions).enable(
                fixture.manifest.id,
                fixture.manifest.version,
                explicitlyApproved = false,
                approvedPermissions = fixture.manifest.permissions
            )

            assertFalse(result.succeeded)
            assertTrue(result.message.contains("aprobación explícita"))
            assertEquals(BardoSkillLifecycle.INSTALLED_DISABLED, fixture.manager.list().single().lifecycle)
        } finally {
            fixture.root.deleteRecursively()
        }
    }
}
