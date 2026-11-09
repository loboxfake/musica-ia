package com.musicaia

import com.musicaia.skills.BardoSkillLifecycle
import com.musicaia.skills.BardoSkillManager
import com.musicaia.skills.BardoSkillManifest
import com.musicaia.skills.BardoSkillPackageKind
import com.musicaia.skills.BardoSkillPermission
import com.musicaia.skills.BardoSkillManifestValidator
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoSkillManagerTest {
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manifest(bytes: ByteArray) = BardoSkillManifest(
        id = "skill.audio.helper",
        name = "Audio helper",
        version = "1.0.0",
        apiMinimum = 1,
        apiMaximum = 1,
        description = "Skill declarativa.",
        source = "local://user-import",
        license = "Apache-2.0",
        packageSha256 = hash(bytes),
        packageSizeBytes = bytes.size.toLong(),
        kind = BardoSkillPackageKind.DECLARATIVE_TOOLCHAIN,
        actions = setOf("audio.mix_user_voice"),
        permissions = setOf(
            BardoSkillPermission.READ_BARDO_DATA,
            BardoSkillPermission.WRITE_BARDO_WORKSPACE,
            BardoSkillPermission.AUDIO_FILE_ACCESS
        )
    )

    private fun manager(root: File) = BardoSkillManager(root) {
        BardoSkillManifestValidator.supportedActionIds()
    }

    @Test fun importRequiresLicenseAndPreservesPackageInQuarantineBeforeInstall() {
        val root = createTempDir(prefix = "bardo-skill-import")
        try {
            val bytes = "declarative-skill-v1".toByteArray()
            val manager = manager(root)
            val rejected = manager.importToQuarantine(manifest(bytes), ByteArrayInputStream(bytes), false)
            assertFalse(rejected.succeeded)
            val imported = manager.importToQuarantine(manifest(bytes), ByteArrayInputStream(bytes), true)
            assertTrue(imported.message, imported.succeeded)
            assertEquals(BardoSkillLifecycle.QUARANTINED, imported.record?.lifecycle)
            assertFalse(manager.enabled().isNotEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun installAndEnableRequireSeparateApprovalAndSmokeTest() {
        val root = createTempDir(prefix = "bardo-skill-enable")
        try {
            val bytes = "declarative-skill-v1".toByteArray()
            val manager = manager(root)
            manager.importToQuarantine(manifest(bytes), ByteArrayInputStream(bytes), true)
            assertFalse(manager.install("skill.audio.helper", "1.0.0", false).succeeded)
            val installed = manager.install("skill.audio.helper", "1.0.0", true)
            assertTrue(installed.succeeded)
            assertEquals(BardoSkillLifecycle.INSTALLED_DISABLED, installed.record?.lifecycle)

            val permissions = manifest(bytes).permissions
            assertFalse(manager.enable("skill.audio.helper", "1.0.0", true, permissions, false).succeeded)
            val enabled = manager.enable("skill.audio.helper", "1.0.0", true, permissions, true)
            assertTrue(enabled.succeeded)
            assertEquals(BardoSkillLifecycle.ENABLED, enabled.record?.lifecycle)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun disableAndRemoveDoNotAffectTheOriginalInput() {
        val root = createTempDir(prefix = "bardo-skill-remove")
        try {
            val bytes = "declarative-skill-v1".toByteArray()
            val source = File(root, "original.skill").apply { writeBytes(bytes) }
            val manager = manager(File(root, "store").apply { mkdirs() })
            manager.importToQuarantine(manifest(bytes), source.inputStream(), true)
            manager.install("skill.audio.helper", "1.0.0", true)
            val permissions = manifest(bytes).permissions
            manager.enable("skill.audio.helper", "1.0.0", true, permissions, true)
            assertTrue(manager.disable("skill.audio.helper", "1.0.0").succeeded)
            assertTrue(manager.remove("skill.audio.helper", "1.0.0", true).succeeded)
            assertTrue(source.isFile)
            assertEquals(bytes.toList(), source.readBytes().toList())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun recordsArePersistentAcrossManagerInstances() {
        val root = createTempDir(prefix = "bardo-skill-persist")
        try {
            val bytes = "declarative-skill-v1".toByteArray()
            manager(root).importToQuarantine(manifest(bytes), ByteArrayInputStream(bytes), true)
            val recovered = manager(root).list()
            assertEquals(1, recovered.size)
            assertEquals(BardoSkillLifecycle.QUARANTINED, recovered.single().lifecycle)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun rejectsChangedContentAndUnregisteredActions() {
        val root = createTempDir(prefix = "bardo-skill-integrity")
        try {
            val bytes = "declared".toByteArray()
            val badManager = BardoSkillManager(root) { emptySet() }
            val result = badManager.importToQuarantine(manifest(bytes), ByteArrayInputStream(bytes), true)
            assertFalse(result.succeeded)
        } finally {
            root.deleteRecursively()
        }
    }
}
