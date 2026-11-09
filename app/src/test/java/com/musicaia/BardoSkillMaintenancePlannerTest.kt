package com.musicaia

import com.musicaia.skills.*
import org.junit.Assert.assertEquals
import org.junit.Test

class BardoSkillMaintenancePlannerTest {
    private fun record(
        version: String,
        lifecycle: BardoSkillLifecycle,
        apiMinimum: Int = 1,
        apiMaximum: Int = 1,
        tested: Boolean = true,
        dependencies: List<BardoSkillDependency> = emptyList()
    ): BardoSkillRecord {
        val manifest = BardoSkillManifest(
            id = "skill.example",
            name = "Example",
            version = version,
            apiMinimum = apiMinimum,
            apiMaximum = apiMaximum,
            description = "Test skill",
            source = "local://test",
            license = "Apache-2.0",
            packageSha256 = "a".repeat(64),
            packageSizeBytes = 12,
            kind = BardoSkillPackageKind.DECLARATIVE_TOOLCHAIN,
            actions = setOf("knowledge.search"),
            permissions = setOf(BardoSkillPermission.READ_BARDO_DATA),
            dependencies = dependencies,
            licenseAcceptedByUser = true
        )
        return BardoSkillRecord(
            manifest = manifest,
            lifecycle = lifecycle,
            installedPath = null,
            lastTestPassed = tested,
            lastUpdatedAtMillis = 1_000,
            lastTestVerifierId = if (tested) "trusted.test" else null,
            lastTestEvidenceRefs = if (tested) listOf("test://evidence") else emptyList()
        )
    }

    @Test fun recommendsRemovingOlderVersionOnlyWhenNewerIsEnabled() {
        val findings = BardoSkillMaintenancePlanner.evaluate(
            listOf(
                record("1.0.0", BardoSkillLifecycle.DISABLED),
                record("2.0.0", BardoSkillLifecycle.ENABLED)
            ),
            setOf("knowledge.search")
        )
        assertEquals(BardoSkillMaintenanceAction.PROPOSE_REMOVE, findings.first { it.version == "1.0.0" }.action)
        assertEquals(BardoSkillMaintenanceAction.KEEP, findings.first { it.version == "2.0.0" }.action)
    }

    @Test fun incompatibleEnabledSkillIsProposedForDisableNotDeleted() {
        val findings = BardoSkillMaintenancePlanner.evaluate(
            listOf(record("1.0.0", BardoSkillLifecycle.ENABLED, apiMinimum = 2, apiMaximum = 3)),
            setOf("knowledge.search"),
            currentApi = 1
        )
        assertEquals(BardoSkillMaintenanceAction.PROPOSE_DISABLE, findings.single().action)
    }

    @Test fun missingIndependentTestEvidenceRequiresRetest() {
        val findings = BardoSkillMaintenancePlanner.evaluate(
            listOf(record("1.0.0", BardoSkillLifecycle.ENABLED, tested = false)),
            setOf("knowledge.search")
        )
        assertEquals(BardoSkillMaintenanceAction.RETEST_REQUIRED, findings.single().action)
    }

    @Test fun enabledSkillWithMissingDependencyIsProposedForDisable() {
        val dependent = record(
            "1.0.0",
            BardoSkillLifecycle.ENABLED,
            dependencies = listOf(BardoSkillDependency("skill.storage", "2.0.0"))
        )
        val findings = BardoSkillMaintenancePlanner.evaluate(
            listOf(dependent),
            setOf("knowledge.search")
        )
        assertEquals(BardoSkillMaintenanceAction.PROPOSE_DISABLE, findings.single().action)
        assertTrue(findings.single().reasons.any { it.contains("skill.storage") })
    }

    @Test fun newerEnabledDependencySatisfiesMinimumVersion() {
        val dependent = record(
            "1.0.0",
            BardoSkillLifecycle.ENABLED,
            dependencies = listOf(BardoSkillDependency("skill.storage", "1.2.0"))
        )
        val storage = record("2.0.0", BardoSkillLifecycle.ENABLED)
            .copy(manifest = record("2.0.0", BardoSkillLifecycle.ENABLED).manifest.copy(id = "skill.storage"))
        val findings = BardoSkillMaintenancePlanner.evaluate(
            listOf(dependent, storage),
            setOf("knowledge.search")
        )
        assertEquals(BardoSkillMaintenanceAction.KEEP, findings.first { it.skillId == "skill.example" }.action)
    }

    @Test fun missingRegisteredActionProposesDisable() {
        val findings = BardoSkillMaintenancePlanner.evaluate(
            listOf(record("1.0.0", BardoSkillLifecycle.ENABLED)),
            emptySet()
        )
        assertEquals(BardoSkillMaintenanceAction.PROPOSE_DISABLE, findings.single().action)
    }
}
