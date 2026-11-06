package com.musicaia.memoria

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

    private fun file(context: Context) = File(context.applicationContext.filesDir, "bardo_activity_timeline.json")
    private val lock = Any()

    fun record(
        context: Context, taskId: String, category: String, state: String,
        stage: String, message: String, progress: Int? = null, artifact: String? = null
    ) = synchronized(lock) {
        val target = file(context)
        val lockFile = File(target.parentFile, "bardo_activity_timeline.lock")
        java.io.RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use {
                val root = runCatching {
                    if (target.isFile) JSONObject(target.readText()) else JSONObject()
                }.getOrDefault(JSONObject())
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
                val start = (events.length() - 1000).coerceAtLeast(0)
                for (i in start until events.length()) bounded.put(events.getJSONObject(i))
                root.put("schemaVersion", 1).put("events", bounded)
                val temp = File(target.parentFile, target.name + ".tmp")
                temp.writeText(root.toString())
                if (!temp.renameTo(target)) {
                    target.writeText(root.toString())
                    temp.delete()
                }
            }
        }
    }

    fun recent(context: Context, limit: Int = 250): List<Event> = synchronized(lock) {
        val target = file(context)
        val lockFile = File(target.parentFile, "bardo_activity_timeline.lock")
        java.io.RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use fileLock@{
                val events = runCatching { JSONObject(target.readText()).optJSONArray("events") }.getOrNull()
                    ?: return@fileLock emptyList<Event>()
                (0 until events.length()).mapNotNull { index ->
                    val item = events.optJSONObject(index) ?: return@mapNotNull null
                    Event(
                        item.optString("id"), item.optString("taskId"), item.optString("category"),
                        item.optString("state"), item.optString("stage"), item.optString("message"),
                        if (item.has("progress")) item.optInt("progress") else null,
                        item.optLong("timestamp"), item.optString("artifact").takeIf { it.isNotBlank() }
                    )
                }.takeLast(limit).asReversed()
            }
        }
    }
}
