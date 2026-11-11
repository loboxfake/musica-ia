package com.musicaia

import com.musicaia.desarrollo.BardoGitHubPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoGitHubPolicyTest {
    @Test
    fun acceptsOnlyAFullGitCommitShaAndCandidateId() {
        BardoGitHubPolicy.validateBaseSha("0123456789abcdef0123456789abcdef01234567")
        BardoGitHubPolicy.validateCandidateId("0123456789abcdef0123456789abcdef")
        assertTrue(runCatching { BardoGitHubPolicy.validateBaseSha("main") }.isFailure)
        assertTrue(runCatching { BardoGitHubPolicy.validateBaseSha("0123") }.isFailure)
        assertTrue(runCatching { BardoGitHubPolicy.validateCandidateId("candidate") }.isFailure)
    }

    @Test
    fun allowsOnlyExistingCodeTestResourceAndDocumentationPaths() {
        BardoGitHubPolicy.validateChangedPaths(
            listOf(
                "app/src/main/java/com/musicaia/modulos/ModuloLetra.kt",
                "app/src/test/java/com/musicaia/ModuloLetraTest.kt",
                "app/src/main/res/values/strings.xml",
                "docs/DEVELOPER.md"
            )
        )
        assertEquals("loboxfake/musica-ia", BardoGitHubPolicy.REPOSITORY)
    }

    @Test
    fun refusesModo3DeveloperCoreWorkflowsAndBuildConfiguration() {
        val blocked = listOf(
            "app/src/main/java/com/musicaia/modulos/Modo3MezclaVozUsuario.kt",
            "app/src/main/java/com/musicaia/desarrollo/BardoDeveloperWorker.kt",
            "app/src/main/java/com/musicaia/desarrollo/BardoPatchTextExtractor.kt",
            "app/src/main/java/com/musicaia/modelo/BardoGuidedProgressPolicy.kt",
            "app/src/main/java/com/musicaia/modelo/BardoIntentRouter.kt",
            "app/src/main/java/com/musicaia/autonomia/BardoAutonomyEngine.kt",
            "app/src/main/java/com/musicaia/BardoDeveloperActivity.kt",
            "app/src/main/java/com/musicaia/BardoGitHubDevelopmentActivity.kt",
            "app/src/main/java/com/musicaia/desarrollo/BardoGitHubAutomation.kt",
            ".github/workflows/full-build.yml",
            "app/build.gradle.kts"
        )
        blocked.forEach { path ->
            assertTrue("Debe bloquear $path", runCatching {
                BardoGitHubPolicy.validateChangedPaths(listOf(path))
            }.isFailure)
        }
    }

    @Test
    fun refusesTraversalOutsideTheAllowlistAndDuplicatePaths() {
        listOf(
            "../MainActivity.kt",
            "app/src/main/java/../../secrets.txt",
            "gradle/wrapper/gradle-wrapper.properties",
            "app/src/main/assets/model.pte"
        ).forEach { path ->
            assertTrue("Debe bloquear $path", runCatching {
                BardoGitHubPolicy.validateChangedPaths(listOf(path))
            }.isFailure)
        }
        assertTrue(runCatching {
            BardoGitHubPolicy.validateChangedPaths(listOf("docs/A.md", "docs/A.md"))
        }.isFailure)
    }

    @Test
    fun refusesEmptyMalformedAndOversizedDiffs() {
        assertTrue(runCatching { BardoGitHubPolicy.validatePatch("") }.isFailure)
        assertTrue(runCatching { BardoGitHubPolicy.validatePatch("texto sin diff") }.isFailure)
        assertTrue(runCatching {
            BardoGitHubPolicy.validatePatch("--- a/docs/a.md\n+++ b/docs/a.md\n" + "x".repeat(BardoGitHubPolicy.MAX_PATCH_BYTES + 1))
        }.isFailure)
    }
}
