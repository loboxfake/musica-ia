package com.musicaia.memoria

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoConversationStoreTest {
    private fun newStore(): Pair<BardoConversationStore, File> {
        val directory = Files.createTempDirectory("bardo-history-test").toFile()
        return BardoConversationStore(directory) to directory
    }

    @Test
    fun storesMessagesWithOriginalTimestampAndTimezone() {
        val (store, directory) = newStore()
        try {
            val conversation = store.createConversation("Prueba", now = 1_700_000_000_000L)
            val message = store.append(
                conversation.id,
                role = "user",
                content = "Hola BARDO",
                now = 1_700_000_123_000L,
                zoneId = "Europe/Madrid"
            )

            val restored = store.messages(conversation.id).single()
            assertEquals(message.id, restored.id)
            assertEquals(1_700_000_123_000L, restored.timestampEpochMillis)
            assertEquals("Europe/Madrid", restored.zoneId)
            assertTrue(store.exportJson().contains("\"timestampUtc\""))
            assertTrue(store.exportText().contains("Europe/Madrid"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun importingSameExportTwiceDoesNotDuplicateRecords() {
        val (source, sourceDir) = newStore()
        val (target, targetDir) = newStore()
        try {
            val conversation = source.createConversation("Importada")
            source.append(conversation.id, "assistant", "Respuesta local")
            val exported = source.exportJson()

            assertEquals(1, target.importJson(exported))
            assertEquals(0, target.importJson(exported))
            assertEquals(1, target.listConversations().size)
            assertEquals(1, target.messages(conversation.id).size)
        } finally {
            sourceDir.deleteRecursively()
            targetDir.deleteRecursively()
        }
    }

    @Test
    fun searchesMessagesAndReturnsTheirConversation() {
        val (store, directory) = newStore()
        try {
            val conversation = store.createConversation("Proyecto de prueba")
            store.append(conversation.id, "user", "Necesito una canción sobre la amistad")
            store.append(conversation.id, "assistant", "Podemos trabajar el tema de la amistad.")
            val results = store.searchMessages("amistad")
            assertEquals(2, results.size)
            assertEquals("Proyecto de prueba", results.first().conversation.title)
            assertTrue(store.searchMessages("término inexistente").isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsOrphanMessageWithoutChangingExistingHistory() {
        val (store, directory) = newStore()
        try {
            val existing = store.createConversation("Conservada")
            store.append(existing.id, "user", "No borrar")
            val invalid = JSONObject()
                .put("schemaVersion", 1)
                .put("conversations", org.json.JSONArray())
                .put("messages", org.json.JSONArray().put(
                    JSONObject()
                        .put("id", "orphan")
                        .put("conversationId", "missing")
                        .put("role", "assistant")
                        .put("content", "No debe entrar")
                        .put("timestampEpochMillis", 1L)
                        .put("zoneId", "UTC")
                ))

            val error = runCatching { store.importJson(invalid.toString()) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertEquals(1, store.listConversations().size)
            assertEquals("No borrar", store.messages(existing.id).single().content)
            assertFalse(store.messages(existing.id).any { it.id == "orphan" })
        } finally {
            directory.deleteRecursively()
        }
    }
}
