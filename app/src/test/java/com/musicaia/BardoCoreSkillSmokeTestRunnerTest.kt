package com.musicaia

import com.musicaia.herramientas.BardoToolDescriptor
import com.musicaia.herramientas.BardoToolExecutor
import com.musicaia.herramientas.BardoToolHandler
import com.musicaia.herramientas.BardoToolOutput
import com.musicaia.herramientas.BardoToolSmokeTestHandler
import com.musicaia.seguridad.BardoPermissionScope
import com.musicaia.skills.BardoCoreSkillSmokeTestRunner
import com.musicaia.skills.BardoSkillManifest
import com.musicaia.skills.BardoSkillPackageKind
import com.musicaia.skills.BardoSkillPermission
import com.musicaia.skills.BardoSkillSmokeTestReport
import org.junit.Assert.*
import org.junit.Test

class BardoCoreSkillSmokeTestRunnerTest {
    private class TestedTool(
        override val descriptor: BardoToolDescriptor,
        private val response: String = "verified"
    ) : BardoToolHandler, BardoToolSmokeTestHandler {
        override fun execute(arguments: Map<String, String>) = BardoToolOutput(true, response)
        override fun smokeTest() = execute(emptyMap())
    }

    private class UntestedTool(
        override val descriptor: BardoToolDescriptor
    ) : BardoToolHandler {
        override fun execute(arguments: Map<String, String>) = BardoToolOutput(true, "not tested")
    }

    private fun descriptor(id: String) = BardoToolDescriptor(
        id = id,
        description = "test",
        requiredScope = BardoPermissionScope.READ_BARDO_DATA,
        maxArguments = 0
    )

    private fun manifest(actions: Set<String>) = BardoSkillManifest(
        id = "skill.smoke.test",
        name = "Smoke test",
        version = "1.0.0",
        apiMinimum = 1,
        apiMaximum = 1,
        description = "Test de runner de skills.",
        source = "local://test",
        license = "Apache-2.0",
        packageSha256 = "a".repeat(64),
        packageSizeBytes = 1,
        kind = BardoSkillPackageKind.DECLARATIVE_TOOLCHAIN,
        actions = actions,
        permissions = setOf(BardoSkillPermission.READ_BARDO_DATA),
        licenseAcceptedByUser = true
    )

    @Test fun executesOnlySmokeTestsOwnedByRegisteredCodeAndReturnsEvidence() {
        val executor = BardoToolExecutor(listOf(
            TestedTool(descriptor("test.alpha"), "alpha-output"),
            TestedTool(descriptor("test.beta"), "beta-output")
        ))
        val runner = BardoCoreSkillSmokeTestRunner(executor)

        val report = runner.run(
            manifest(setOf("test.alpha", "test.beta")),
            setOf(BardoSkillPermission.READ_BARDO_DATA)
        )

        assertTrue(report.note, report.passed)
        assertEquals(BardoCoreSkillSmokeTestRunner.VERIFIER_ID, report.verifierId)
        assertEquals(2, report.evidenceRefs.size)
        assertTrue(report.evidenceRefs.all { it.startsWith("bardo://skill-smoke/") })
        assertTrue(report.evidenceRefs.distinct().size == 2)
    }

    @Test fun refusesActionsWithoutCodeOwnedSmokeTest() {
        val executor = BardoToolExecutor(listOf(UntestedTool(descriptor("test.untested"))))
        val report = BardoCoreSkillSmokeTestRunner(executor).run(
            manifest(setOf("test.untested")),
            setOf(BardoSkillPermission.READ_BARDO_DATA)
        )

        assertFalse(report.passed)
        assertTrue(report.note.contains("no tiene un smoke-test de confianza"))
        assertTrue(report.evidenceRefs.isEmpty())
    }

    @Test fun refusesActionsThatAreNotInTheRegisteredToolCatalog() {
        val executor = BardoToolExecutor(listOf(TestedTool(descriptor("test.known"))))
        val report = BardoCoreSkillSmokeTestRunner(executor).run(
            manifest(setOf("test.unknown")),
            setOf(BardoSkillPermission.READ_BARDO_DATA)
        )

        assertFalse(report.passed)
        assertTrue(report.note.contains("no registradas"))
    }

    @Test fun executorDoesNotAcceptAUserSuppliedSmokeTest() {
        val executor = BardoToolExecutor(listOf(UntestedTool(descriptor("test.untested"))))

        val result = executor.runTrustedSmokeTest("test.untested")

        assertFalse(result.succeeded)
        assertTrue(result.error.orEmpty().contains("no tiene un smoke-test"))
    }
}
