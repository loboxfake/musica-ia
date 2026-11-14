package com.musicaia.memoria

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoActivityStoreTest {
    private fun context(directory: File): Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
    }

    private fun event(id: String, taskId: String = "task-1") = JSONObject()
        .put("id", id)
        .put("taskId", taskId)
        .put("category", "cancion")
        .put("state", "running")
        .put("stage", "generacion_audio")
        .put("message", "Evento $id")
        .put("timestamp", 1_800_000_000_000L)

    private fun root(vararg events: JSONObject) = JSONObject()
        .put("schemaVersion", 1)
        .put("events", JSONArray().apply { events.forEach(::put) })

    @Test
    fun damagedPrimaryIsPreservedAndNewEventsCanBeRecorded() {
        val directory = Files.createTempDirectory("bardo-activity-corrupt").toFile()
        try {
            val target = File(directory, "bardo_activity_timeline.json")
            val damaged = "{ this is not valid JSON"
            target.writeText(damaged)

            assertTrue(BardoActivityStore.recent(context(directory)).isEmpty())
            BardoActivityStore.record(context(directory), "task-new", "cancion", "running", "inicio", "Nuevo evento")

            assertTrue(directory.listFiles().orEmpty().any { it.name.startsWith(target.name + ".corrupt-") })
            assertEquals("Nuevo evento", BardoActivityStore.recent(context(directory)).single().message)
            assertTrue(JSONObject(target.readText()).optJSONArray("events")!!.length() == 1)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun validBackupRecoversWhenPrimaryIsDamagedAndRemainsAvailable() {
        val directory = Files.createTempDirectory("bardo-activity-backup").toFile()
        try {
            val target = File(directory, "bardo_activity_timeline.json")
            val backup = File(directory, "bardo_activity_timeline.json.bak")
            backup.writeText(root(event("recovered")).toString())
            target.writeText("truncated")

            assertEquals("recovered", BardoActivityStore.recent(context(directory)).single().id)
            BardoActivityStore.record(context(directory), "task-2", "cancion", "running", "letra", "Evento nuevo")

            val restored = BardoActivityStore.recent(context(directory)).map { it.id }.toSet()
            assertEquals(setOf("recovered", "nuevo"), restored.intersect(setOf("recovered", "nuevo")))
            assertTrue(JSONObject(backup.readText()).optJSONArray("events")!!.length() == 1)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun previousValidPrimaryIsKeptAsBackupBeforeReplacement() {
        val directory = Files.createTempDirectory("bardo-activity-rotate").toFile()
        try {
            val context = context(directory)
            val backup = File(directory, "bardo_activity_timeline.json.bak")
            BardoActivityStore.record(context, "task-1", "cancion", "running", "inicio", "Primero")
            BardoActivityStore.record(context, "task-1", "cancion", "running", "audio", "Segundo")

            assertEquals(1, JSONObject(backup.readText()).optJSONArray("events")!!.length())
            File(directory, "bardo_activity_timeline.json").writeText("broken")
            assertEquals("Primero", BardoActivityStore.recent(context).single().message)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun timelineIsBoundedToTheMostRecentThousandEvents() {
        val directory = Files.createTempDirectory("bardo-activity-bound").toFile()
        try {
            val context = context(directory)
            repeat(1005) { index ->
                BardoActivityStore.record(context, "task-$index", "cancion", "running", "audio", "Evento $index")
            }
            val events = BardoActivityStore.recent(context, 1000)
            assertEquals(1000, events.size)
            assertEquals("Evento 1004", events.first().message)
            assertEquals("Evento 5", events.last().message)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun concurrentWritersDoNotLoseEvents() {
        val directory = Files.createTempDirectory("bardo-activity-concurrent").toFile()
        val workers = 5
        val perWorker = 20
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workers)
        try {
            val futures = (0 until workers).map { worker ->
                executor.submit {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    repeat(perWorker) { index ->
                        BardoActivityStore.record(
                            context(directory), "task-$worker", "cancion", "running",
                            "audio", "worker-$worker-event-$index"
                        )
                    }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
            assertEquals(workers * perWorker, BardoActivityStore.recent(context(directory), 1000).size)
        } finally {
            start.countDown()
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test
    fun negativeRecentLimitIsSafelyClamped() {
        val directory = Files.createTempDirectory("bardo-activity-limit").toFile()
        try {
            BardoActivityStore.record(context(directory), "task-1", "cancion", "running", "inicio", "Evento")
            assertTrue(BardoActivityStore.recent(context(directory), -10).isEmpty())
            assertFalse(BardoActivityStore.recent(context(directory), 1).isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }
}
