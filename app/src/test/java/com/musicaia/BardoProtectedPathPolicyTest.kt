package com.musicaia

import com.musicaia.desarrollo.BardoProtectedPathPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoProtectedPathPolicyTest {
    @Test fun keepsModo3PermanentlyBlocked() {
        assertEquals(
            BardoProtectedPathPolicy.PathClass.FORBIDDEN,
            BardoProtectedPathPolicy.classify(BardoProtectedPathPolicy.MODO3_PATH)
        )
        assertEquals(
            BardoProtectedPathPolicy.PathClass.FORBIDDEN,
            BardoProtectedPathPolicy.classify("copied/Modo3MezclaVozUsuario.kt")
        )
    }

    @Test fun protectsCoreAndWorkflowsForHumanReview() {
        assertEquals(
            BardoProtectedPathPolicy.PathClass.HUMAN_REVIEW_REQUIRED,
            BardoProtectedPathPolicy.classify("app/src/main/java/com/musicaia/MainActivity.kt")
        )
        assertEquals(
            BardoProtectedPathPolicy.PathClass.HUMAN_REVIEW_REQUIRED,
            BardoProtectedPathPolicy.classify(".github/workflows/full-build.yml")
        )
        assertEquals(
            BardoProtectedPathPolicy.PathClass.HUMAN_REVIEW_REQUIRED,
            BardoProtectedPathPolicy.classify("app/src/main/java/com/musicaia/desarrollo/BardoWorkspaceManager.kt")
        )
    }

    @Test fun existingTestsAreImmutableAndNewTestsAreAdditiveOnly() {
        val path = "app/src/test/java/com/musicaia/NewAutonomyTest.kt"
        assertEquals(
            BardoProtectedPathPolicy.PathClass.ADDITIVE_TEST_ONLY,
            BardoProtectedPathPolicy.classify(path)
        )
        assertFalse(BardoProtectedPathPolicy.canModifyExisting(path))
        assertTrue(BardoProtectedPathPolicy.canAddNewTest(path))
    }

    @Test fun permitsSafeImplementationAreasButNotBuildConfiguration() {
        assertTrue(
            BardoProtectedPathPolicy.canModifyExisting(
                "app/src/main/java/com/musicaia/modulos/Example.kt"
            )
        )
        assertTrue(BardoProtectedPathPolicy.canModifyExisting("docs/notes.md"))
        assertFalse(BardoProtectedPathPolicy.canModifyExisting("app/build.gradle.kts"))
        assertFalse(BardoProtectedPathPolicy.canModifyExisting("secrets.env"))
    }

    @Test fun protectsAuthorityModelsSkillsToolsAndEvaluatorPolicies() {
        val protected = listOf(
            "app/src/main/java/com/musicaia/seguridad/BardoAuthorityPolicy.kt",
            "app/src/main/java/com/musicaia/seguridad/BardoAuthorityLedger.kt",
            "app/src/main/java/com/musicaia/modelos/BardoModelProvider.kt",
            "app/src/main/java/com/musicaia/skills/BardoSkillManifest.kt",
            "app/src/main/java/com/musicaia/herramientas/BardoToolExecutor.kt",
            "app/src/main/java/com/musicaia/evaluacion/BardoIndependentEvaluator.kt"
        )
        protected.forEach { path ->
            assertEquals(
                BardoProtectedPathPolicy.PathClass.HUMAN_REVIEW_REQUIRED,
                BardoProtectedPathPolicy.classify(path)
            )
        }
    }

    @Test fun protectsMobileStorageAndSkillLifecycleSecurityPaths() {
        val protected = listOf(
            "app/src/main/java/com/musicaia/storage/BardoMobileFilePolicy.kt",
            "app/src/main/java/com/musicaia/storage/BardoSharedStorage.kt",
            "app/src/main/java/com/musicaia/skills/BardoSkillManager.kt",
            "app/src/main/java/com/musicaia/skills/BardoSkillWorkflowExecutor.kt",
            "app/src/main/java/com/musicaia/skills/BardoSkillMaintenancePlanner.kt",
            "app/src/main/java/com/musicaia/skills/BardoSkillDraftBuilder.kt",
            "app/src/main/java/com/musicaia/skills/BardoSkillDraftToolHandler.kt",
            "app/src/main/java/com/musicaia/skills/BardoCoreSkillSmokeTestRunner.kt"
        )
        protected.forEach { path ->
            assertEquals(
                BardoProtectedPathPolicy.PathClass.HUMAN_REVIEW_REQUIRED,
                BardoProtectedPathPolicy.classify(path)
            )
        }
    }

    @Test fun rejectsTraversalAndAmbiguousPaths() {
        listOf(
            "../MainActivity.kt",
            "/tmp/file.kt",
            "C:/temp/file.kt",
            "a\\b.kt",
            "a//b.kt",
            "a/./b.kt"
        ).forEach { path ->
            assertEquals(
                BardoProtectedPathPolicy.PathClass.FORBIDDEN,
                BardoProtectedPathPolicy.classify(path)
            )
        }
    }
}
