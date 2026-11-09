package com.musicaia

import com.musicaia.skills.BardoSkillManifest
import com.musicaia.skills.BardoSkillManifestValidator
import com.musicaia.skills.BardoSkillPackageKind
import com.musicaia.skills.BardoSkillPermission
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoSkillManifestValidatorTest {
    private fun validManifest() = BardoSkillManifest(
        id = "skill.audio.helper",
        name = "Audio helper",
        version = "1.0.0",
        apiMinimum = 1,
        apiMaximum = 1,
        description = "Skill declarativa de ejemplo.",
        source = "https://example.invalid/skill.zip",
        license = "Apache-2.0",
        packageSha256 = "a".repeat(64),
        packageSizeBytes = 1024,
        kind = BardoSkillPackageKind.DECLARATIVE_TOOLCHAIN,
        actions = setOf("audio.mix_user_voice"),
        permissions = setOf(
            BardoSkillPermission.READ_BARDO_DATA,
            BardoSkillPermission.WRITE_BARDO_WORKSPACE,
            BardoSkillPermission.AUDIO_FILE_ACCESS
        ),
        licenseAcceptedByUser = true
    )

    @Test fun acceptsValidDeclarativeManifest() {
        val result = BardoSkillManifestValidator.validate(validManifest())
        assertTrue(result.errors.joinToString(), result.accepted)
        assertTrue(result.requiredPermissions.contains(BardoSkillPermission.AUDIO_FILE_ACCESS))
    }

    @Test fun rejectsExecutablePackagesAndUnknownActions() {
        val executable = validManifest().copy(kind = BardoSkillPackageKind.EXECUTABLE_CODE)
        assertFalse(BardoSkillManifestValidator.validate(executable).accepted)
        val unknown = validManifest().copy(actions = setOf("shell.execute"))
        assertFalse(BardoSkillManifestValidator.validate(unknown).accepted)
    }

    @Test fun rejectsMissingLicenseAcceptancePermissionsAndIntegrity() {
        val invalid = validManifest().copy(
            licenseAcceptedByUser = false,
            permissions = emptySet(),
            packageSha256 = "not-a-hash"
        )
        val result = BardoSkillManifestValidator.validate(invalid)
        assertFalse(result.accepted)
        assertTrue(result.errors.size >= 3)
    }

    @Test fun rejectsUnsupportedApiAndInvalidOrigin() {
        val invalid = validManifest().copy(
            apiMinimum = 2,
            apiMaximum = 3,
            source = "file:///sdcard/secret.zip"
        )
        assertFalse(BardoSkillManifestValidator.validate(invalid).accepted)
    }

    @Test fun actionHandlersMustExistInTheRuntimeRegistry() {
        val result = BardoSkillManifestValidator.validate(
            validManifest(),
            registeredActionIds = emptySet()
        )
        assertFalse(result.accepted)
    }
}
