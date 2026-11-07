package com.musicaia

import com.musicaia.desarrollo.BardoPatchApplier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BardoPatchApplierTest {
    @Test fun appliesPatchToCandidateWithoutChangingOriginal() {
        val base = createTempDir(prefix = "bardo-patch")
        try {
            val source = File(base, "source").apply { mkdirs() }
            File(source, "Hello.kt").writeText("fun hello() {\n    println(\"old\")\n}\n")
            val patch = """
                --- a/Hello.kt
                +++ b/Hello.kt
                @@ -1,3 +1,3 @@
                 fun hello() {
                -    println("old")
                +    println("new")
                 }
            """.trimIndent()
            val result = BardoPatchApplier.aplicar(source, patch, base)
            assertEquals(listOf("Hello.kt"), result.changedFiles)
            assertTrue(File(source, "Hello.kt").readText().contains("old"))
            assertTrue(File(result.directory, "Hello.kt").readText().contains("new"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test fun refusesProtectedCoreAndWorkflowFiles() {
        val base = createTempDir(prefix = "bardo-patch-core-protected")
        try {
            val cases = listOf(
                "app/src/main/java/com/musicaia/desarrollo/BardoDeveloperWorker.kt" to "class ProtectedWorker",
                "app/src/main/java/com/musicaia/BardoDeveloperActivity.kt" to "class ProtectedDeveloperActivity",
                "app/src/main/java/com/musicaia/BardoGitHubDevelopmentActivity.kt" to "class ProtectedGitHubActivity",
                "app/src/main/java/com/musicaia/desarrollo/BardoGitHubAutomation.kt" to "class ProtectedGitHubAutomation",
                ".github/workflows/full-build.yml" to "name: ProtectedWorkflow"
            )
            cases.forEachIndexed { index, (path, original) ->
                val source = File(base, "source-$index").apply { mkdirs() }
                val file = File(source, path).apply {
                    parentFile?.mkdirs()
                    writeText(original)
                }
                val patch = buildString {
                    appendLine("--- a/$path")
                    appendLine("+++ b/$path")
                    appendLine("@@ -1 +1 @@")
                    appendLine("-$original")
                    appendLine("+class Altered")
                }
                assertTrue("Debe rechazarse $path", runCatching {
                    BardoPatchApplier.aplicar(source, patch, base)
                }.isFailure)
                assertEquals(original, file.readText())
            }
        } finally {
            base.deleteRecursively()
        }
    }

    @Test fun refusesProtectedModo3File() {
        val base = createTempDir(prefix = "bardo-patch-protected")
        try {
            val source = File(base, "source").apply { mkdirs() }
            File(source, "Modo3MezclaVozUsuario.kt").writeText("class Modo3")
            val patch = """
                --- a/Modo3MezclaVozUsuario.kt
                +++ b/Modo3MezclaVozUsuario.kt
                @@ -1 +1 @@
                -class Modo3
                +class Alterado
            """.trimIndent()
            assertTrue(runCatching { BardoPatchApplier.aplicar(source, patch, base) }.isFailure)
            assertEquals("class Modo3", File(source, "Modo3MezclaVozUsuario.kt").readText())
        } finally {
            base.deleteRecursively()
        }
    }
}
