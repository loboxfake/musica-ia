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

    // Las distintas pantallas/servicios pueden crear almacenes independientes para
    // el mismo directorio. Compartir el lock evita carreras entre import, lectura y borrado.
    private val lock = processLocks.computeIfAbsent(directory.absoluteFile.canonicalPath) { Any() }

    fun importText(title: String, source: String, text: String): Entry = synchronized(lock) {
        require(text.isNotBlank()) { "El documento está vacío." }
        require(text.length <= 500_000) { "El documento supera el límite de 500.000 caracteres." }
        directory.mkdirs()
        val id = UUID.randomUUID().toString()
        val bytes = text.toByteArray(Charsets.UTF_8)
        val hash = sha256(bytes)
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
                val id = json.getString("id")
                if (!isValidId(id)) return@runCatching null
                Entry(id, json.optString("title"), json.optString("source"),
                    json.optLong("createdAt"), json.optInt("characters"), json.optString("sha256"))
            }.getOrNull() }
            // No mostrar fichas cuyo documento falte o cuya huella ya no coincida.
            .filter { entry -> readVerified(entry) != null }
            .sortedByDescending { it.createdAt }
    }

    private fun isValidId(value: String): Boolean =
        runCatching { UUID.fromString(value).toString().equals(value, ignoreCase = true) }.getOrDefault(false)

    /** Devuelve solo contenido cuya huella coincide con la ficha local. */
    private fun readVerified(entry: Entry): String? {
        if (!isValidId(entry.id)) return null
        if (!entry.sha256.matches(Regex("[0-9a-fA-F]{64}"))) return null
        val file = File(directory, "${entry.id}.txt")
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (!sha256(bytes).equals(entry.sha256, ignoreCase = true)) return null
        return runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun search(query: String, maxChars: Int = 12000): String = synchronized(lock) {
        val terms = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()
        if (terms.isEmpty()) return@synchronized ""
        val ranked = list().mapNotNull { entry ->
            val text = readVerified(entry) ?: return@mapNotNull null
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
                val text = readVerified(entry) ?: continue
                val header = "\n[Referencia autorizada: ${entry.title} · Fuente: ${entry.source}]\n"
                val room = maxChars - length - header.length
                if (room > 0) {
                    append(header)
                    append(text.take(room))
                }
            }
        }
    }

    fun clearAll() = synchronized(lock) { directory.listFiles()?.forEach { it.delete() } }

    companion object {
        private val processLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }
}
