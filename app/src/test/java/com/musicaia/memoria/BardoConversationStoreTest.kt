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
    fun titlesNewConversationFromFirstUserMessage() {
        val (store, directory) = newStore()
        try {
            val conversation = store.createConversation()
            store.append(conversation.id, "user", "  Quiero crear una canción sobre seguir adelante  ")
            assertEquals(
                "Quiero crear una canción sobre seguir adelante",
                store.listConversations().single().title
            )
            store.append(conversation.id, "assistant", "De acuerdo.")
            assertEquals(1, store.listConversations().size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun migratesLegacyTranscriptWhenOnlyEmptyConversationRecordsExist() {
        val (store, directory) = newStore()
        try {
            store.createConversation("Sesión vacía")
            val legacy = "[19:42] Tú: Hola BARDO\n[19:42] BARDO: Hola, ¿en qué puedo ayudarte?"
            val migrated = store.importLegacyTranscript(legacy, now = 1_800_000_000_000L)

            assertEquals("Historial anterior", migrated?.title)
            val messages = store.messages(migrated!!.id)
            assertEquals(1, messages.size)
            assertEquals("system", messages.single().role)
            assertTrue(messages.single().content.contains(legacy))
            assertEquals(null, store.importLegacyTranscript(legacy, now = 1_800_000_000_001L))
            assertEquals(1, store.messages(migrated.id).size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun doesNotImportLegacyTranscriptOverExistingMessages() {
        val (store, directory) = newStore()
        try {
            val existing = store.createConversation("Actual")
            store.append(existing.id, "user", "Conservar este mensaje")
            assertEquals(null, store.importLegacyTranscript("historial antiguo"))
            assertEquals("Conservar este mensaje", store.messages(existing.id).single().content)
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
    fun keepsValidBackupWhenPrimaryHistoryIsCorruptedAndThenWritesAgain() {
        val (store, directory) = newStore()
        try {
            val conversation = store.createConversation("Recuperación")
            store.append(conversation.id, "user", "Mensaje recuperable")
            val primary = File(directory, "bardo_conversation_history.json")
            val backup = File(directory, "bardo_conversation_history.json.bak")

            // Simulate a crash/corruption after a valid backup was created.
            primary.copyTo(backup, overwrite = true)
            primary.writeText("{ documento dañado", Charsets.UTF_8)

            // The next write must recover the backup and must not overwrite it
            // with the corrupt primary before the recovered data is persisted.
            store.append(conversation.id, "assistant", "Segundo mensaje")
            val messages = store.messages(conversation.id)
            assertEquals(2, messages.size)
            assertEquals("Mensaje recuperable", messages[0].content)
            assertEquals("Segundo mensaje", messages[1].content)
            assertTrue(JSONObject(primary.readText(Charsets.UTF_8)).has("messages"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun recoversFromStructurallyInvalidPrimaryHistory() {
        val (store, directory) = newStore()
        try {
            val conversation = store.createConversation("Esquema dañado")
            store.append(conversation.id, "user", "Mensaje del respaldo")
            val primary = File(directory, "bardo_conversation_history.json")
            val backup = File(directory, "bardo_conversation_history.json.bak")

            // Valid JSON, but missing the required messages array.
            primary.copyTo(backup, overwrite = true)
            primary.writeText(
                JSONObject()
                    .put("schemaVersion", 1)
                    .put("conversations", org.json.JSONArray())
                    .toString(),
                Charsets.UTF_8
            )

            store.append(conversation.id, "assistant", "Esquema recuperado")
            val messages = store.messages(conversation.id)
            assertEquals(2, messages.size)
            assertEquals("Mensaje del respaldo", messages[0].content)
            assertEquals("Esquema recuperado", messages[1].content)
            assertTrue(JSONObject(primary.readText(Charsets.UTF_8)).has("messages"))
        } finally {
            directory.deleteRecursively()
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
