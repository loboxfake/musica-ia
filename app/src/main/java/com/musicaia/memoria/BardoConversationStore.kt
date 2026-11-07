package com.musicaia.memoria

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Almacén local del historial conversacional de BARDO.
 *
 * Cada mensaje se persiste como registro independiente con instante UTC y zona
 * horaria de origen. El historial no depende del TextView de la interfaz.
 *
 * Este primer almacén usa JSON atómico para no añadir una dependencia de base
 * de datos antes de integrar y migrar el historial existente. La API permite
 * sustituir internamente el backend por Room sin cambiar los consumidores.
 */
class BardoConversationStore internal constructor(private val directory: File) {
    constructor(context: Context) : this(context.filesDir)
    data class Message(
        val id: String,
        val conversationId: String,
        val role: String,
        val content: String,
        val timestampEpochMillis: Long,
        val zoneId: String
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("conversationId", conversationId)
            .put("role", role)
            .put("content", content)
            .put("timestampEpochMillis", timestampEpochMillis)
            .put("zoneId", zoneId)
            .put("timestampUtc", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(timestampEpochMillis)))
    }

    data class Conversation(
        val id: String,
        val title: String,
        val createdAtEpochMillis: Long,
        val updatedAtEpochMillis: Long
    )

    private val lock = Any()
    private val file = File(directory, "bardo_conversation_history.json")
    private val backup = File(directory, "bardo_conversation_history.json.bak")

    fun createConversation(title: String = "Nueva conversación", now: Long = System.currentTimeMillis()): Conversation =
        synchronized(lock) {
            val id = UUID.randomUUID().toString()
            val conversation = Conversation(id, title.ifBlank { "Nueva conversación" }, now, now)
            val root = readRoot()
            root.getJSONArray("conversations").put(
                JSONObject()
                    .put("id", conversation.id)
                    .put("title", conversation.title)
                    .put("createdAtEpochMillis", now)
                    .put("updatedAtEpochMillis", now)
            )
            writeRoot(root)
            conversation
        }

    fun append(
        conversationId: String,
        role: String,
        content: String,
        now: Long = System.currentTimeMillis(),
        zoneId: String = ZoneId.systemDefault().id
    ): Message = synchronized(lock) {
        require(role in setOf("user", "assistant", "system", "tool")) { "Rol de mensaje no válido." }
        require(content.isNotBlank()) { "El mensaje no puede estar vacío." }
        val root = readRoot()
        val conversations = root.getJSONArray("conversations")
        val conversation = (0 until conversations.length())
            .map { conversations.getJSONObject(it) }
            .firstOrNull { it.optString("id") == conversationId }
            ?: throw IllegalArgumentException("La conversación no existe.")
        val message = Message(UUID.randomUUID().toString(), conversationId, role, content, now, zoneId)
        root.getJSONArray("messages").put(message.toJson())
        conversation.put("updatedAtEpochMillis", now)
        writeRoot(root)
        message
    }

    fun listConversations(): List<Conversation> = synchronized(lock) {
        val array = readRoot().getJSONArray("conversations")
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Conversation(
                item.getString("id"),
                item.optString("title", "Conversación"),
                item.optLong("createdAtEpochMillis"),
                item.optLong("updatedAtEpochMillis")
            )
        }.sortedByDescending { it.updatedAtEpochMillis }
    }

    fun messages(conversationId: String): List<Message> = synchronized(lock) {
        val array = readRoot().getJSONArray("messages")
        (0 until array.length()).mapNotNull { index ->
            val item = array.getJSONObject(index)
            if (item.optString("conversationId") != conversationId) return@mapNotNull null
            Message(
                item.getString("id"),
                item.getString("conversationId"),
                item.getString("role"),
                item.getString("content"),
                item.getLong("timestampEpochMillis"),
                item.optString("zoneId", "UTC")
            )
        }.sortedWith(compareBy<Message> { it.timestampEpochMillis }.thenBy { it.id })
    }

    data class SearchResult(val conversation: Conversation, val message: Message)

    fun searchMessages(query: String): List<SearchResult> = synchronized(lock) {
        val needle = query.trim()
        if (needle.isEmpty()) return@synchronized emptyList()
        val root = readRoot()
        val conversationArray = root.getJSONArray("conversations")
        val conversations = (0 until conversationArray.length()).associate { index ->
            val item = conversationArray.getJSONObject(index)
            val conversation = Conversation(
                item.getString("id"),
                item.optString("title", "Conversación"),
                item.optLong("createdAtEpochMillis"),
                item.optLong("updatedAtEpochMillis")
            )
            conversation.id to conversation
        }
        val messageArray = root.getJSONArray("messages")
        (0 until messageArray.length()).mapNotNull { index ->
            val item = messageArray.getJSONObject(index)
            val content = item.optString("content")
            if (!content.contains(needle, ignoreCase = true)) return@mapNotNull null
            val conversationId = item.optString("conversationId")
            val conversation = conversations[conversationId] ?: return@mapNotNull null
            val message = Message(
                item.getString("id"),
                conversationId,
                item.optString("role"),
                content,
                item.optLong("timestampEpochMillis"),
                item.optString("zoneId", "UTC")
            )
            SearchResult(conversation, message)
        }.sortedByDescending { it.message.timestampEpochMillis }
    }

    /** Exportación íntegra: conserva IDs, orden, UTC y zona horaria original. */
    fun exportJson(): String = synchronized(lock) {
        readRoot().toString(2)
    }

    /** Exportación legible para el usuario, con fecha/hora local por mensaje. */
    fun exportText(): String = synchronized(lock) {
        val root = readRoot()
        val conversations = root.getJSONArray("conversations")
        val messages = root.getJSONArray("messages")
        buildString {
            appendLine("BARDO — Historial de conversaciones")
            appendLine("Exportado: ${Instant.now()}")
            for (i in 0 until conversations.length()) {
                val conversation = conversations.getJSONObject(i)
                val id = conversation.getString("id")
                appendLine()
                appendLine("=== ${conversation.optString("title", "Conversación")} ===")
                for (j in 0 until messages.length()) {
                    val message = messages.getJSONObject(j)
                    if (message.optString("conversationId") != id) continue
                    val instant = Instant.ofEpochMilli(message.getLong("timestampEpochMillis"))
                    val zone = runCatching { ZoneId.of(message.optString("zoneId", "UTC")) }.getOrDefault(ZoneId.of("UTC"))
                    val date = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                        .withZone(zone).format(instant)
                    val speaker = when (message.optString("role")) {
                        "user" -> "Tú"
                        "assistant" -> "BARDO"
                        "system" -> "Sistema"
                        else -> "Herramienta"
                    }
                    appendLine("[$date ${zone.id}] $speaker:")
                    appendLine(message.optString("content"))
                }
            }
        }
    }

    /**
     * Importación conservadora: valida el formato y los campos antes de guardar.
     * El llamador debe mostrar una vista previa y solicitar autorización antes
     * de invocar este método. No ejecuta instrucciones contenidas en los mensajes.
     */
    fun importJson(json: String): Int = synchronized(lock) {
        val incoming = JSONObject(json)
        require(incoming.optInt("schemaVersion") == 1) { "Versión de historial no compatible." }
        val incomingConversations = incoming.optJSONArray("conversations")
            ?: throw IllegalArgumentException("Falta la lista de conversaciones.")
        val incomingMessages = incoming.optJSONArray("messages")
            ?: throw IllegalArgumentException("Falta la lista de mensajes.")
        val current = readRoot()
        val currentConversations = current.getJSONArray("conversations")
        val currentMessages = current.getJSONArray("messages")
        val knownConversationIds = (0 until currentConversations.length())
            .map { currentConversations.getJSONObject(it).optString("id") }.toMutableSet()
        val knownMessageIds = (0 until currentMessages.length())
            .map { currentMessages.getJSONObject(it).optString("id") }.toMutableSet()

        // Validar el documento completo antes de mutar el historial local.
        val incomingIds = mutableSetOf<String>()
        for (i in 0 until incomingConversations.length()) {
            val item = incomingConversations.getJSONObject(i)
            val id = item.optString("id").trim()
            require(id.isNotEmpty()) { "Una conversación importada no tiene identificador." }
            require(incomingIds.add(id)) { "El archivo contiene identificadores de conversación duplicados." }
            require(item.optString("title").length <= 200) { "El título de una conversación es demasiado largo." }
            require(item.has("createdAtEpochMillis") && item.has("updatedAtEpochMillis")) {
                "Una conversación importada no contiene sus fechas."
            }
        }
        val allConversationIds = knownConversationIds + incomingIds
        val validatedMessages = (0 until incomingMessages.length()).map { index ->
            val item = incomingMessages.getJSONObject(index)
            val id = item.optString("id").trim()
            val conversationId = item.optString("conversationId").trim()
            val role = item.optString("role")
            val body = item.optString("content")
            require(id.isNotEmpty()) { "Un mensaje importado no tiene identificador." }
            require(conversationId in allConversationIds) { "El archivo contiene un mensaje sin conversación asociada." }
            require(role in setOf("user", "assistant", "system", "tool")) { "Rol de mensaje importado no válido." }
            require(body.isNotBlank() && body.length <= 200_000) { "El contenido de un mensaje está vacío o supera el límite permitido." }
            require(item.has("timestampEpochMillis")) { "Un mensaje importado no contiene fecha." }
            require(runCatching { ZoneId.of(item.optString("zoneId", "UTC")) }.isSuccess) {
                "Un mensaje importado contiene una zona horaria no válida."
            }
            JSONObject(item.toString())
        }

        var added = 0
        for (i in 0 until incomingConversations.length()) {
            val item = incomingConversations.getJSONObject(i)
            if (knownConversationIds.add(item.getString("id"))) {
                currentConversations.put(JSONObject(item.toString()))
            }
        }
        for (item in validatedMessages) {
            if (knownMessageIds.add(item.getString("id"))) {
                currentMessages.put(item)
                added++
            }
        }
        writeRoot(current)
        added
    }

    /** Elimina el historial completo solo cuando el usuario confirma el borrado desde la interfaz. */
    fun clearAll() = synchronized(lock) {
        file.delete()
        backup.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }

    private fun readRoot(): JSONObject {
        val source = when {
            file.isFile -> file
            backup.isFile -> backup
            else -> null
        }
        if (source == null) return emptyRoot()
        return runCatching { JSONObject(source.readText(Charsets.UTF_8)) }
            .getOrElse {
                if (source == file && backup.isFile) {
                    runCatching { JSONObject(backup.readText(Charsets.UTF_8)) }.getOrElse { emptyRoot() }
                } else emptyRoot()
            }
    }

    private fun emptyRoot() = JSONObject()
        .put("schemaVersion", 1)
        .put("conversations", JSONArray())
        .put("messages", JSONArray())

    private fun writeRoot(root: JSONObject) {
        root.put("schemaVersion", 1)
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(root.toString(), Charsets.UTF_8)
        if (file.isFile) {
            if (backup.exists()) backup.delete()
            file.copyTo(backup, overwrite = true)
        }
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }
}
