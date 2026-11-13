package com.musicaia

import com.musicaia.desarrollo.BardoProtectedPathPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regresiones de los límites que protegen los cambios autónomos de BARDO.
 * Estas pruebas son aditivas: no alteran las pruebas existentes ni el código protegido.
 */
class BardoProtectedPathPolicyBoundaryTest {

    @Test
    fun autonomousChangeBatchMustBeBoundedAndUnique() {
        assertTrue(runCatching {
            BardoProtectedPathPolicy.validateAutonomousChanges(emptyList())
        }.isFailure)

        assertTrue(runCatching {
            BardoProtectedPathPolicy.validateAutonomousChanges(
                (1..31).map { "docs/nota-$it.md" }
            )
        }.isFailure)

        assertTrue(runCatching {
            BardoProtectedPathPolicy.validateAutonomousChanges(
                listOf("docs/nota.md", "docs/nota.md")
            )
        }.isFailure)

        assertTrue(runCatching {
            BardoProtectedPathPolicy.validateAutonomousChanges(
                listOf("docs/nota.md", "app/src/main/java/com/musicaia/modulos/ModuloSeguro.kt")
            )
        }.isSuccess)
    }

    @Test
    fun newTestBatchMustBeBoundedUniqueAndKotlinOnly() {
        assertTrue(runCatching {
            BardoProtectedPathPolicy.validateNewTests(emptyList())
        }.isFailure)

        assertTrue(runCatching {
            BardoProtectedPathPolicy.validateNewTests(
                (1..51).map { "app/src/test/java/com/musicaia/Nueva$it.kt" }
            )
        }.isFailure)

        assertTrue(runCatching {
            BardoProtectedPathPolicy.validateNewTests(
                listOf(
                    "app/src/test/java/com/musicaia/NuevaTest.kt",
                    "app/src/test/java/com/musicaia/NuevaTest.kt"
                )
            )
        }.isFailure)

        assertTrue(runCatching {
            BardoProtectedPathPolicy.validateNewTests(
                listOf("app/src/test/java/com/musicaia/NuevaTest.java")
            )
        }.isFailure)

        assertTrue(runCatching {
            BardoProtectedPathPolicy.validateNewTests(
                listOf("app/src/test/java/com/musicaia/NuevaTest.kt")
            )
        }.isSuccess)
    }

    @Test
    fun modo3CannotBeReintroducedThroughNestedOrCaseChangedPaths() {
        val paths = listOf(
            BardoProtectedPathPolicy.MODO3_PATH,
            "candidate/Modo3MezclaVozUsuario.kt",
            "candidate/modo3mezclavozusuario.KT"
        )

        paths.forEach { path ->
            assertFalse(
                "Modo3 debe permanecer fuera de los cambios autónomos: $path",
                BardoProtectedPathPolicy.canModifyExisting(path)
            )
            assertFalse(
                "Modo3 tampoco puede incorporarse como una prueba nueva: $path",
                BardoProtectedPathPolicy.canAddNewTest(path)
            )
        }
    }

    @Test
    fun pathNormalizationRejectsTraversalAndControlCharactersBeforeClassification() {
        val invalid = listOf(
            "../docs/nota.md",
            "docs/../app/src/main/java/com/musicaia/modulos/Modulo.kt",
            "docs/nota\u0000.md",
            " docs/nota.md",
            "docs//nota.md"
        )

        invalid.forEach { path ->
            assertFalse(BardoProtectedPathPolicy.canModifyExisting(path))
            assertFalse(BardoProtectedPathPolicy.canAddNewTest(path))
        }
    }
}
