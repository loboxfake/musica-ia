package com.musicaia.memoria

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Copias locales de conversaciones y conocimiento; nunca incluye los modelos pesados. */
object BardoBackupManager {
    private const val MAX_BACKUP_BYTES = 100L * 1024L * 1024L
    private const val MAX_ENTRIES = 3000

    data class BackupInfo(
        val createdAt: Long,
        val conversationCount: Int,
        val knowledgeCount: Int,
        val includesActivity: Boolean
    )

    data class RestoreResult(
        val conversationsAdded: Int,
        val knowledgeAdded: Int,
        val warnings: List<String>
    )

    fun createBackup(
        context: Context,
        conversations: BardoConversationStore,
        knowledge: BardoKnowledgeStore
    ): File {
        val root = context.applicationContext.filesDir
        val stamp = System.currentTimeMillis()
        val output = File(context.cacheDir, "BARDO_Copia_$stamp.zip")
        val knowledgeDir = File(root, "bardo_knowledge")
        val activity = File(root, "bardo_activity_timeline.json")
        val prefs = File(context.applicationInfo.dataDir, "shared_prefs/musicalia_memoria_local.xml")
        val developerDir = File(root, "bardo_developer")
        val files = mutableListOf<Pair<String, File>>()
        if (activity.isFile) files += "activity/bardo_activity_timeline.json" to activity
        if (prefs.isFile) files += "preferences/musicalia_memoria_local.xml" to prefs
        knowledgeDir.listFiles()?.filter { it.isFile && it.extension in setOf("txt", "json") }
            ?.forEach { files += "knowledge/${it.name}" to it }
        developerDir.walkTopDown().filter { it.isFile && it.length() <= 5L * 1024L * 1024L }
            .take(200).forEach { file ->
                val relative = file.relativeTo(developerDir).invariantSeparatorsPath
                if (relative.isNotBlank() && !relative.split('/').contains("..")) {
                    files += "developer/$relative" to file
                }
            }

        require(files.size <= MAX_ENTRIES) { "La copia contiene demasiados archivos." }
        val conversationJson = conversations.exportJson()
        require(conversationJson.toByteArray(Charsets.UTF_8).size <= 20 * 1024 * 1024) {
            "El historial supera el límite de 20 MB para una copia."
        }
        val total = files.sumOf { it.second.length() } + conversationJson.toByteArray(Charsets.UTF_8).size
        require(total <= MAX_BACKUP_BYTES) {
            "Los datos de respaldo ocupan ${total / (1024L * 1024L)} MB; el límite de esta copia es 100 MB."
        }

        val manifest = JSONObject()
            .put("schemaVersion", 1)
            .put("createdAt", stamp)
            .put("appVersion", runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull().orEmpty())
            .put("conversationCount", conversations.listConversations().size)
            .put("knowledgeCount", knowledge.list().size)
            .put("includesActivity", activity.isFile)
            .put("note", "Gemma y los archivos multimedia no se duplican. Los modelos permanecen en Descargas/bardo/Modelos y los audios en sus carpetas BARDO.")
        try {
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("conversations.json"))
                zip.write(conversationJson.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                for ((entryName, file) in files) {
                    require(!entryName.startsWith("/") && !entryName.split('/').contains("..")) {
                        "Ruta de copia no válida."
                    }
                    zip.putNextEntry(ZipEntry(entryName))
                    file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            check(output.isFile && output.length() > 100L && output.length() <= MAX_BACKUP_BYTES) {
                "La copia resultante está vacía o supera 100 MB."
            }
            return output
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    fun inspectBackup(file: File): BackupInfo {
        require(file.isFile && file.length() in 1..MAX_BACKUP_BYTES) { "El archivo de copia no existe o supera 100 MB." }
        ZipFile(file).use { zip ->
            val manifestEntry = zip.getEntry("manifest.json")
                ?: throw IllegalArgumentException("El ZIP no contiene el manifiesto de BARDO.")
            require(manifestEntry.size in 1..(128L * 1024L)) { "Manifiesto de copia demasiado grande." }
            val manifest = zip.getInputStream(manifestEntry).bufferedReader().use { JSONObject(it.readText()) }
            require(manifest.optInt("schemaVersion") == 1) { "Versión de copia de BARDO no compatible." }
            require(zip.getEntry("conversations.json") != null) { "La copia no contiene conversaciones." }
            return BackupInfo(
                manifest.optLong("createdAt"),
                manifest.optInt("conversationCount"),
                manifest.optInt("knowledgeCount"),
                manifest.optBoolean("includesActivity")
            )
        }
    }

    fun restoreBackup(
        file: File,
        conversations: BardoConversationStore,
        knowledge: BardoKnowledgeStore
    ): RestoreResult {
        inspectBackup(file)
        val warnings = mutableListOf<String>()
        var addedConversations = 0
        var addedKnowledge = 0
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            var count = 0
            var uncompressed = 0L
            val names = mutableSetOf<String>()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                count++
                require(count <= MAX_ENTRIES) { "La copia contiene demasiadas entradas." }
                require(!entry.name.startsWith("/") && !entry.name.split('/').contains("..")) {
                    "La copia contiene una ruta no válida."
                }
                names += entry.name
                if (entry.size > 0L) {
                    uncompressed += entry.size
                    require(uncompressed <= MAX_BACKUP_BYTES) { "El contenido descomprimido supera 100 MB." }
                }
            }

            val conversationsEntry = zip.getEntry("conversations.json")
                ?: throw IllegalArgumentException("Falta el historial de conversaciones.")
            require(conversationsEntry.size in 1..(20L * 1024L * 1024L)) { "El historial supera el límite permitido." }
            val conversationJson = zip.getInputStream(conversationsEntry).bufferedReader().use { it.readText() }
            addedConversations = conversations.importJson(conversationJson)

            val existentes = knowledge.list().map { it.sha256.lowercase() }.toMutableSet()
            val metaNames = names.filter { it.startsWith("knowledge/") && it.endsWith(".json") }
            for (metaName in metaNames) {
                val base = metaName.removeSuffix(".json")
                val textName = "$base.txt"
                val metaEntry = zip.getEntry(metaName) ?: continue
                val textEntry = zip.getEntry(textName)
                if (textEntry == null) {
                    warnings += "Falta el texto de una referencia: ${metaName.substringAfterLast('/')}"
                    continue
                }
                runCatching {
                    require(metaEntry.size in 1..(128L * 1024L)) { "Ficha de referencia demasiado grande." }
                    require(textEntry.size in 1..(500L * 1024L)) { "Texto de referencia demasiado grande." }
                    val meta = zip.getInputStream(metaEntry).bufferedReader().use { JSONObject(it.readText()) }
                    val text = zip.getInputStream(textEntry).bufferedReader().use { it.readText() }
                    val digest = MessageDigest.getInstance("SHA-256")
                        .digest(text.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    require(digest.equals(meta.optString("sha256"), ignoreCase = true)) {
                        "La huella de la referencia no coincide."
                    }
                    if (digest.lowercase() !in existentes) {
                        knowledge.importText(
                            meta.optString("title", "Referencia importada"),
                            meta.optString("source", "Copia BARDO"),
                            text
                        )
                        existentes += digest.lowercase()
                        addedKnowledge++
                    }
                }.onFailure { warnings += "Referencia omitida (${metaName.substringAfterLast('/')}): ${it.message}" }
            }
        }
        return RestoreResult(addedConversations, addedKnowledge, warnings)
    }
}
