package com.musicaia.memoria

import java.nio.file.Files
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
            assertTrue(store.search("memoria local").contains("módulos verificables"))
            assertEquals(64, entry.sha256.length)
        } finally {
            directory.deleteRecursively()
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
}
