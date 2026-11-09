package com.musicaia

import com.musicaia.actualizaciones.BardoIncrementalUpdateManifest
import com.musicaia.actualizaciones.BardoIncrementalUpdatePolicy
import com.musicaia.actualizaciones.BardoUpdateFile
import com.musicaia.actualizaciones.BardoUpdateLevel
import com.musicaia.actualizaciones.BardoUpdateOperation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoIncrementalUpdatePolicyTest {
    private fun validManifest(
        level: BardoUpdateLevel = BardoUpdateLevel.MODULE,
        path: String = "app/src/main/java/com/musicaia/modulos/Example.kt",
        required: Set<String> = emptySet(),
        approved: Set<String> = required
    ) = BardoIncrementalUpdateManifest(
        updateId = "update-20261109-01",
        fromVersion = "1.0.3",
        toVersion = "1.0.4",
        baseCommit = "a".repeat(40),
        targetCommit = "b".repeat(40),
        level = level,
        files = listOf(BardoUpdateFile(
            path = path,
            operation = BardoUpdateOperation.MODIFY,
            previousSha256 = "c".repeat(64),
            targetSha256 = "d".repeat(64),
            sizeBytes = 1024
        )),
        requiredPermissions = required,
        approvedPermissions = approved,
        evidenceRefs = listOf("bardo-audit://update/ci-001"),
        rollbackPlanRef = "bardo-rollback://update-20261109-01"
    )

    @Test fun acceptsSmallModuleUpdateAndRequiresApkRebuild() {
        val result = BardoIncrementalUpdatePolicy.validate(validManifest())
        assertTrue(result.accepted)
        assertFalse(result.requiresHumanReview)
        assertTrue(result.requiresFullApk)
        assertTrue(result.errors.isEmpty())
    }

    @Test fun blocksModo3AndProtectedPaths() {
        val result = BardoIncrementalUpdatePolicy.validate(
            validManifest(path = "app/src/main/java/com/musicaia/modulos/Modo3MezclaVozUsuario.kt")
        )
        assertFalse(result.accepted)
        assertTrue(result.errors.any { it.startsWith("PROTECTED_PATH:") })
    }

    @Test fun blocksUnapprovedPermissions() {
        val result = BardoIncrementalUpdatePolicy.validate(
            validManifest(required = setOf("MICROPHONE"), approved = emptySet())
        )
        assertFalse(result.accepted)
        assertTrue(result.errors.contains("PERMISSION_NOT_EXPLICITLY_APPROVED"))
    }

    @Test fun blocksPathTraversalAndMissingRollback() {
        val unsafe = validManifest(path = "app/src/main/java/../../secrets.txt").copy(rollbackPlanRef = "")
        val result = BardoIncrementalUpdatePolicy.validate(unsafe)
        assertFalse(result.accepted)
        assertTrue(result.errors.any { it.startsWith("UNSAFE_PATH:") })
        assertTrue(result.errors.contains("ROLLBACK_PLAN_MISSING"))
    }

    @Test fun structuralUpdatesAlwaysRequireHumanReview() {
        val result = BardoIncrementalUpdatePolicy.validate(
            validManifest(level = BardoUpdateLevel.STRUCTURAL)
        )
        assertTrue(result.accepted)
        assertTrue(result.requiresHumanReview)
        assertTrue(result.requiresFullApk)
    }

    @Test fun irreversibleDataMigrationIsRejected() {
        val result = BardoIncrementalUpdatePolicy.validate(
            validManifest().copy(dataMigrationId = "migration-01", migrationIsReversible = false)
        )
        assertFalse(result.accepted)
        assertTrue(result.errors.contains("DATA_MIGRATION_NOT_REVERSIBLE"))
    }

    @Test fun configLevelCannotContainCodeAndEvidenceMustBeTrusted() {
        val result = BardoIncrementalUpdatePolicy.validate(
            validManifest(level = BardoUpdateLevel.CONFIGURATION).copy(
                evidenceRefs = listOf("https://example.invalid/run/123")
            )
        )
        assertFalse(result.accepted)
        assertTrue(result.errors.contains("CONFIGURATION_LEVEL_CONTAINS_CODE"))
        assertTrue(result.errors.contains("EVIDENCE_REFERENCE_INVALID"))
    }
}
