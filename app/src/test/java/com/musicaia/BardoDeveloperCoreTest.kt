package com.musicaia

import com.musicaia.desarrollo.BardoDeveloperCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BardoDeveloperCoreTest {
    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test fun importsOnlySafeSourceFilesAndSkipsTraversal() {
        val base = createTempDir(prefix = "bardo-dev-test")
        try {
            val imported = BardoDeveloperCore.importarZip(
                ByteArrayInputStream(zip("app/src/Main.kt" to "fun main() {}", "../outside.kt" to "bad", "image.png" to "skip")),
                base
            )
            assertEquals(1, imported.files)
            assertTrue(File(imported.directory, "app/src/Main.kt").isFile)
            assertTrue(!File(base.parentFile, "outside.kt").exists())
            assertEquals(2, imported.skipped)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test fun failedImportRemovesItsPartialSnapshot() {
        val base = createTempDir(prefix = "bardo-dev-partial")
        try {
            val oversized = "x".repeat(2 * 1024 * 1024 + 1)
            val error = runCatching {
                BardoDeveloperCore.importarZip(
                    ByteArrayInputStream(zip("first.kt" to "valid", "too-large.kt" to oversized)),
                    base
                )
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(base.listFiles().isNullOrEmpty())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test fun reportsKnownStaticRiskPatterns() {
        val base = createTempDir(prefix = "bardo-dev-scan")
        try {
            File(base, "build.gradle.kts").writeText("implementation(\"org.example:lib:+\")")
            File(base, "AndroidManifest.xml").writeText("<manifest/>")
            // Construye el token ficticio en tiempo de ejecución: el escáner de la APK
            // no debe encontrar una credencial contigua dentro del propio código de test.
            val fakeToken = "github_pat_" + "123456789012345678901234"
            File(base, "Unsafe.kt").writeText(
                "fun run() {\n  try { work() } catch (e: Exception) {}\n  Thread.sleep(100)\n  val token = \"" + fakeToken + "\"\n}"
            )
            val report = BardoDeveloperCore.analizar(base)
            assertTrue(report.findings.any { it.title == "Excepción posiblemente silenciada" })
            assertTrue(report.findings.any { it.title == "Espera bloqueante" })
            assertTrue(report.findings.any { it.title == "Posible credencial incrustada" })
            assertTrue(report.findings.any { it.title == "Dependencia no fijada" })
            assertTrue(BardoDeveloperCore.extractos(base, report).contains("Unsafe.kt"))
        } finally {
            base.deleteRecursively()
        }
    }
}
