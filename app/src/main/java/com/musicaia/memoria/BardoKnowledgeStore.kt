package com.musicaia.memoria

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** Biblioteca local de documentos de referencia. No modifica los pesos del modelo. */
class BardoKnowledgeStore internal constructor(private val directory: File) {
    constructor(context: Context) : this(File(context.filesDir, "bardo_knowledge"))

    data class Entry(
        val id: String,
        val title: String,
        val source: String,
        val createdAt: Long,
        val characters: Int,
        val sha256: String
    )

    private val lock = Any()

    fun importText(title: String, source: String, text: String): Entry = synchronized(lock) {
        require(text.isNotBlank()) { "El documento está vacío." }
        require(text.length <= 500_000) { "El documento supera el límite de 500.000 caracteres." }
        directory.mkdirs()
        val id = UUID.randomUUID().toString()
        val bytes = text.toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val entry = Entry(id, title.take(160).ifBlank { "Referencia" }, source.take(240), System.currentTimeMillis(), text.length, hash)
        val data = File(directory, "$id.txt")
        val meta = File(directory, "$id.json")
        val tempData = File(directory, "$id.txt.tmp")
        val tempMeta = File(directory, "$id.json.tmp")
        try {
            tempData.writeBytes(bytes)
            tempMeta.writeText(JSONObject()
                .put("id", entry.id).put("title", entry.title).put("source", entry.source)
                .put("createdAt", entry.createdAt).put("characters", entry.characters)
                .put("sha256", entry.sha256).toString(), Charsets.UTF_8)
            check(tempData.renameTo(data)) { "No se pudo guardar el documento." }
            check(tempMeta.renameTo(meta)) { data.delete(); "No se pudo guardar la ficha." }
            entry
        } finally {
            tempData.delete()
            tempMeta.delete()
        }
    }

    fun list(): List<Entry> = synchronized(lock) {
        directory.listFiles { file -> file.isFile && file.extension == "json" }.orEmpty()
            .mapNotNull { file -> runCatching {
                val json = JSONObject(file.readText(Charsets.UTF_8))
                Entry(json.getString("id"), json.optString("title"), json.optString("source"),
                    json.optLong("createdAt"), json.optInt("characters"), json.optString("sha256"))
            }.getOrNull() }
            .sortedByDescending { it.createdAt }
    }

    fun search(query: String, maxChars: Int = 12000): String = synchronized(lock) {
        val terms = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()
        if (terms.isEmpty()) return@synchronized ""
        val ranked = list().mapNotNull { entry ->
            val file = File(directory, "${entry.id}.txt")
            if (!file.isFile) return@mapNotNull null
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return@mapNotNull null
            val searchable = (entry.title + " " + entry.source + " " + text).lowercase()
            val score = terms.count { searchable.contains(it) }
            if (score == 0) null else Triple(entry, text, score)
        }.sortedByDescending { it.third }
        buildString {
            ranked.forEach { (entry, text, score) ->
                if (length < maxChars) {
                    val header = "\n[Referencia: ${entry.title} · Fuente: ${entry.source} · coincidencias: $score]\n"
                    val room = maxChars - length - header.length
                    if (room > 0) { append(header); append(text.take(room)) }
                }
            }
        }
    }

    fun allForPrompt(maxChars: Int = 12000): String = synchronized(lock) {
        buildString {
            for (entry in list()) {
                if (length >= maxChars) break
                val file = File(directory, "${entry.id}.txt")
                val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
                val header = "\\n[Referencia autorizada: ${entry.title} · Fuente: ${entry.source}]\\n"
                val room = maxChars - length - header.length
                if (room > 0) {
                    append(header)
                    append(text.take(room))
                }
            }
        }
    }

    fun clearAll() = synchronized(lock) { directory.listFiles()?.forEach { it.delete() } }
}
