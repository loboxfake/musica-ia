package com.musicaia.memoria

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Registro cronológico local de tareas y etapas verificables de BARDO. */
object BardoActivityStore {
    data class Event(
        val id: String,
        val taskId: String,
        val category: String,
        val state: String,
        val stage: String,
        val message: String,
        val progress: Int?,
        val timestamp: Long,
        val artifact: String?
    )

    private const val MAX_EVENTS = 1000
    private const val MAX_FILE_BYTES = 8L * 1024L * 1024L
    private const val SCHEMA_VERSION = 1
    private val lock = Any()

    private fun file(context: Context) =
        File(context.applicationContext.filesDir, "bardo_activity_timeline.json")

    private fun backup(target: File) = File(target.parentFile, target.name + ".bak")

    fun record(
        context: Context, taskId: String, category: String, state: String,
        stage: String, message: String, progress: Int? = null, artifact: String? = null
    ) = synchronized(lock) {
        val target = file(context)
        val parent = target.parentFile ?: error("El directorio del historial no está disponible.")
        check(parent.isDirectory || parent.mkdirs()) { "No se pudo crear el directorio del historial." }
        val backupFile = backup(target)
        val lockFile = File(parent, "bardo_activity_timeline.lock")

        java.io.RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use {
                val primaryValid = isValid(target)
                val backupValid = isValid(backupFile)
                val root = when {
                    primaryValid -> readValidRoot(target)
                    backupValid -> readValidRoot(backupFile)
                    else -> emptyRoot()
                }

                // Nunca destruir silenciosamente un historial inválido: se conserva
                // para diagnóstico antes de publicar el nuevo documento recuperado.
                if (!primaryValid && target.exists()) archiveCorrupt(target)
                if (!backupValid && backupFile.exists()) archiveCorrupt(backupFile)

                val events = root.optJSONArray("events") ?: JSONArray()
                val event = JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("taskId", taskId)
                    .put("category", category)
                    .put("state", state)
                    .put("stage", stage)
                    .put("message", message.take(4000))
                    .put("timestamp", System.currentTimeMillis())
                if (progress != null) event.put("progress", progress.coerceIn(0, 100))
                if (!artifact.isNullOrBlank()) event.put("artifact", artifact)
                events.put(event)

                val bounded = JSONArray()
                val start = (events.length() - MAX_EVENTS).coerceAtLeast(0)
                for (index in start until events.length()) {
                    bounded.put(events.getJSONObject(index))
                }
                root.put("schemaVersion", SCHEMA_VERSION).put("events", bounded)

                // La copia anterior solo se actualiza desde un primario validado.
                // Si se recuperó desde .bak, esa copia válida se mantiene intacta.
                if (primaryValid) writeAtomically(backupFile, target.readBytes())
                writeAtomically(target, root.toString().toByteArray(Charsets.UTF_8))
            }
        }
    }

    fun recent(context: Context, limit: Int = 250): List<Event> = synchronized(lock) {
        val target = file(context)
        val lockFile = File(target.parentFile, "bardo_activity_timeline.lock")
        java.io.RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use fileLock@{
                val source = when {
                    isValid(target) -> target
                    isValid(backup(target)) -> backup(target)
                    else -> return@fileLock emptyList<Event>()
                }
                val events = readValidRoot(source).optJSONArray("events") ?: return@fileLock emptyList<Event>()
                (0 until events.length()).mapNotNull { index ->
                    val item = events.optJSONObject(index) ?: return@mapNotNull null
                    Event(
                        item.optString("id"), item.optString("taskId"), item.optString("category"),
                        item.optString("state"), item.optString("stage"), item.optString("message"),
                        if (item.has("progress")) item.optInt("progress") else null,
                        item.optLong("timestamp"), item.optString("artifact").takeIf { it.isNotBlank() }
                    )
                }.takeLast(limit.coerceIn(0, MAX_EVENTS)).asReversed()
            }
        }
    }

    private fun emptyRoot() = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("events", JSONArray())

    private fun isValid(candidate: File): Boolean {
        if (!candidate.isFile || candidate.length() <= 0L || candidate.length() > MAX_FILE_BYTES) return false
        return runCatching {
            val root = JSONObject(candidate.readText(Charsets.UTF_8))
            if (root.optInt("schemaVersion") != SCHEMA_VERSION) return@runCatching false
            val events = root.optJSONArray("events") ?: return@runCatching false
            if (events.length() > MAX_EVENTS) return@runCatching false
            for (index in 0 until events.length()) {
                val item = events.optJSONObject(index) ?: return@runCatching false
                if (item.optString("id").isBlank()) return@runCatching false
                if (item.optString("taskId").isBlank()) return@runCatching false
                if (item.optString("category").isBlank()) return@runCatching false
                if (item.optString("state").isBlank()) return@runCatching false
                if (item.optString("stage").isBlank()) return@runCatching false
                if (!item.has("timestamp") || item.optLong("timestamp") <= 0L) return@runCatching false
                if (item.optString("message").length > 4000) return@runCatching false
                if (item.has("progress") && (item.optInt("progress", -1) !in 0..100)) return@runCatching false
            }
            true
        }.getOrDefault(false)
    }

    private fun readValidRoot(source: File): JSONObject =
        JSONObject(source.readText(Charsets.UTF_8))

    private fun archiveCorrupt(source: File) {
        val archived = File(source.parentFile, source.name + ".corrupt-" + System.currentTimeMillis())
        try {
            Files.move(source.toPath(), archived.toPath())
        } catch (error: Exception) {
            throw IllegalStateException("No se pudo conservar el historial dañado: ${source.name}", error)
        }
    }

    private fun writeAtomically(destination: File, bytes: ByteArray) {
        val temp = File(destination.parentFile, destination.name + ".tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}
