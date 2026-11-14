package com.musicaia.memoria

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoKnowledgeStoreTest {
    @Test
    fun importedReferenceIsPersistedAndSearchable() {
        val directory = Files.createTempDirectory("bardo-knowledge-test").toFile()
        try {
            val store = BardoKnowledgeStore(directory)
            val entry = store.importText("Arquitectura local", "documentacion.md", "BARDO utiliza memoria local y módulos verificables.")
            assertEquals(1, store.list().size)
            val searchResult = store.search("memoria local")
            val promptResult = store.allForPrompt()
            assertTrue(searchResult.contains("módulos verificables"))
            assertTrue(searchResult.startsWith("\n[Referencia:"))
            assertTrue(promptResult.contains("Arquitectura local"))
            assertTrue(promptResult.startsWith("\n[Referencia autorizada:"))
            assertEquals(64, entry.sha256.length)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun modifiedReferenceIsExcludedFromSearchAndPrompts() {
        val directory = Files.createTempDirectory("bardo-knowledge-integrity").toFile()
        try {
            val store = BardoKnowledgeStore(directory)
            val entry = store.importText("Referencia segura", "nota.txt", "contenido original autorizado")
            File(directory, "${entry.id}.txt").writeText("contenido manipulado")
            assertTrue(store.search("contenido manipulado").isEmpty())
            assertFalse(store.allForPrompt().contains("contenido manipulado"))
            assertTrue("Una referencia alterada no debe aparecer en la biblioteca", store.list().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun maliciousReferenceIdCannotEscapeKnowledgeDirectory() {
        val directory = Files.createTempDirectory("bardo-knowledge-path").toFile()
        try {
            val outside = File(directory.parentFile, "outside-bardo-reference.txt")
            val secret = "outside content must never be indexed"
            outside.writeText(secret)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(secret.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val metadata = JSONObject()
                .put("id", "../outside-bardo-reference")
                .put("title", "Referencia manipulada")
                .put("source", "test")
                .put("createdAt", System.currentTimeMillis())
                .put("characters", secret.length)
                .put("sha256", digest)
            File(directory, "malicious.json").writeText(metadata.toString())

            val store = BardoKnowledgeStore(directory)
            assertTrue(store.list().isEmpty())
            assertTrue(store.search("outside content").isEmpty())
            assertFalse(store.allForPrompt().contains(secret))
        } finally {
            directory.deleteRecursively()
            File(directory.parentFile, "outside-bardo-reference.txt").delete()
        }
    }

    @Test
    fun clearAllRemovesReferences() {
        val directory = Files.createTempDirectory("bardo-knowledge-clear").toFile()
        try {
            val store = BardoKnowledgeStore(directory)
            store.importText("Prueba", "nota.txt", "contenido de prueba")
            store.clearAll()
            assertTrue(store.list().isEmpty())
            assertFalse(directory.listFiles().orEmpty().any { it.extension == "json" })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun oversizedOrBlankDocumentsAreRejected() {
        val directory = Files.createTempDirectory("bardo-knowledge-limits").toFile()
        try {
            val store = BardoKnowledgeStore(directory)
            assertTrue(runCatching { store.importText("Vacío", "x.txt", "   ") }.isFailure)
            assertTrue(runCatching { store.importText("Grande", "x.txt", "x".repeat(500_001)) }.isFailure)
            assertTrue(store.list().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun separateInstancesSerializeConcurrentImportsAndClearOperations() {
        val directory = Files.createTempDirectory("bardo-knowledge-concurrency").toFile()
        val first = BardoKnowledgeStore(directory)
        val second = BardoKnowledgeStore(directory)
        val workers = 5
        val ready = java.util.concurrent.CountDownLatch(workers)
        val start = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(workers)
        try {
            val futures = (0 until workers).map { worker ->
                executor.submit {
                    ready.countDown()
                    check(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    repeat(35) { index ->
                        if (worker == workers - 1 && index % 3 == 0) {
                            second.clearAll()
                        } else {
                            val store = if (worker % 2 == 0) first else second
                            store.importText(
                                "Referencia $worker-$index",
                                "worker-$worker.txt",
                                "Contenido íntegro del trabajador $worker, elemento $index"
                            )
                        }
                    }
                }
            }
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }

            // Cada ficha publicada debe tener su documento correspondiente y una
            // huella correcta; nunca debe quedar una ficha huérfana por clear/import.
            directory.listFiles { file -> file.isFile && file.extension == "json" }.orEmpty().forEach { metadataFile ->
                val metadata = JSONObject(metadataFile.readText(Charsets.UTF_8))
                val id = metadata.getString("id")
                val dataFile = File(directory, "$id.txt")
                assertTrue("Falta el documento de $id", dataFile.isFile)
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(dataFile.readBytes())
                    .joinToString("") { "%02x".format(it) }
                assertEquals(metadata.getString("sha256"), digest)
            }
            assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        } finally {
            start.countDown()
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }

}
