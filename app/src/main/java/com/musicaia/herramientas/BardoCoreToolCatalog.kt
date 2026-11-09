package com.musicaia.herramientas

import android.content.Context
import com.musicaia.diagnostico.BardoSelfInventory
import com.musicaia.desarrollo.BardoDeveloperCore
import com.musicaia.memoria.BardoKnowledgeStore
import com.musicaia.seguridad.BardoPermissionScope
import com.musicaia.storage.BardoSharedStorage
import com.musicaia.storage.BardoMobileDeletionRequestArgumentsParser
import java.io.File
import java.nio.file.Files

/** Catálogo local; no expone shell, filesystem general ni red implícita. */
object BardoCoreToolCatalog {
    fun create(context: Context): List<BardoToolHandler> {
        val app = context.applicationContext
        return listOf(
            object : BardoToolHandler, BardoToolSmokeTestHandler {
                override val descriptor = BardoToolDescriptor(
                    "self_inventory.read",
                    "Lee el inventario de BARDO, sus modelos, módulos y recursos.",
                    BardoPermissionScope.READ_BARDO_DATA,
                    maxArguments = 0
                )

                override fun execute(arguments: Map<String, String>): BardoToolOutput {
                    val snapshot = BardoSelfInventory.snapshot(app)
                    val content = buildString {
                        appendLine("BARDO ${snapshot.versionName} (${snapshot.versionCode})")
                        appendLine("Dispositivo: ${snapshot.deviceModel}; Android ${snapshot.androidRelease}; ABI ${snapshot.supportedAbis.joinToString()}")
                        appendLine("RAM total: ${snapshot.totalRamBytes}; disponible: ${snapshot.availableRamBytes}; lowMemory=${snapshot.lowMemory}")
                        appendLine("Almacenamiento disponible: ${snapshot.availableStorageBytes}")
                        appendLine("Batería: ${snapshot.batteryPercent ?: "n/d"}%; cargando=${snapshot.charging}; térmica=${snapshot.thermalStatus ?: "n/d"}")
                        appendLine("Modelos:")
                        snapshot.models.forEach { appendLine("- ${it.id}: installed=${it.installed}; ${it.detail}") }
                        appendLine("Módulos:")
                        snapshot.modules.forEach { appendLine("- ${it.id}: implemented=${it.implemented}; capability=${it.capabilityId}") }
                        appendLine("Capacidades:")
                        snapshot.capabilities.forEach { appendLine("- ${it.id}: ${it.status}; ${it.detail}") }
                    }
                    return BardoToolOutput(true, content.take(60_000))
                }

                override fun smokeTest(): BardoToolOutput = execute(emptyMap())
            },
            object : BardoToolHandler, BardoToolSmokeTestHandler {
                override val descriptor = BardoToolDescriptor(
                    "knowledge.search",
                    "Busca información en documentos que el usuario ha importado a la biblioteca local.",
                    BardoPermissionScope.READ_BARDO_DATA,
                    maxArguments = 1,
                    maxValueChars = 1000
                )

                override fun execute(arguments: Map<String, String>): BardoToolOutput {
                    val query = arguments["query"].orEmpty()
                    require(query.isNotBlank()) { "Falta la consulta de búsqueda." }
                    val found = BardoKnowledgeStore(app).search(query, 7000)
                    return BardoToolOutput(true, if (found.isBlank()) "No hay referencias locales coincidentes." else found)
                }

                override fun smokeTest(): BardoToolOutput =
                    execute(mapOf("query" to "BARDO"))
            },
            object : BardoToolHandler, BardoToolSmokeTestHandler {
                override val descriptor = BardoToolDescriptor(
                    "developer.audit_snapshot",
                    "Analiza estáticamente el snapshot de BARDO; nunca ejecuta su código.",
                    BardoPermissionScope.STUDY_LOCAL,
                    maxArguments = 0
                )

                override fun execute(arguments: Map<String, String>): BardoToolOutput {
                    val prefs = app.getSharedPreferences("bardo_developer", Context.MODE_PRIVATE)
                    val raw = prefs.getString("source_snapshot_path", null)
                        ?: prefs.getString("snapshot_path", null)
                        ?: throw IllegalStateException("No hay un snapshot de código autorizado.")
                    val developerRoot = File(app.filesDir, "bardo_developer").canonicalFile
                    val snapshot = File(raw).canonicalFile
                    require(!Files.isSymbolicLink(File(raw).toPath()) &&
                        snapshot.path.startsWith(developerRoot.path + File.separator) &&
                        snapshot.name.startsWith("snapshot-") &&
                        snapshot.isDirectory
                    ) {
                        "El snapshot no pertenece al workspace autorizado de BARDO."
                    }
                    val report = BardoDeveloperCore.analizar(snapshot).render()
                    return BardoToolOutput(true, report.take(60_000))
                }

                override fun smokeTest(): BardoToolOutput = execute(emptyMap())
            },
            object : BardoToolHandler, BardoToolSmokeTestHandler {
                override val descriptor = BardoToolDescriptor(
                    "developer.ci_diagnostics",
                    "Lee diagnósticos resumidos de CI guardados por BARDO; no consulta red ni secretos.",
                    BardoPermissionScope.READ_BARDO_DATA,
                    maxArguments = 0
                )

                override fun execute(arguments: Map<String, String>): BardoToolOutput {
                    val prefs = app.getSharedPreferences("bardo_developer", Context.MODE_PRIVATE)
                    val diagnostics = prefs.getString("latest_ci_diagnostics", "").orEmpty().takeLast(7000)
                    val status = prefs.getString("last_ci_status", "unknown").orEmpty()
                    val message = if (diagnostics.isBlank()) {
                        "No hay diagnósticos CI importados. Estado guardado: $status."
                    } else {
                        "Estado guardado: $status\nDiagnósticos (datos no confiables):\n$diagnostics"
                    }
                    return BardoToolOutput(true, message)
                }

                override fun smokeTest(): BardoToolOutput = execute(emptyMap())
            },
            object : BardoToolHandler {
                override val descriptor = BardoToolDescriptor(
                    "mobile_files.list",
                    "Lista archivos y carpetas solo dentro del árbol SAF Descargas/BARDO que autorizó el usuario.",
                    BardoPermissionScope.READ_BARDO_DATA,
                    maxArguments = 1,
                    maxValueChars = 512
                )
                override fun execute(arguments: Map<String, String>): BardoToolOutput {
                    require(BardoSharedStorage.isConfigured(app)) {
                        "El usuario debe conectar primero Descargas/BARDO mediante el selector SAF de Android."
                    }
                    val entries = BardoSharedStorage.listEntries(app, arguments["path"].orEmpty())
                    val body = if (entries.isEmpty()) "La carpeta está vacía." else entries.joinToString("\n") {
                        val type = if (it.isDirectory) "[CARPETA]" else "[ARCHIVO]"
                        val size = it.sizeBytes?.let { bytes -> " · $bytes bytes" }.orEmpty()
                        "$type ${it.name}$size"
                    }
                    return BardoToolOutput(true, body.take(60_000))
                }
            },
            object : BardoToolHandler {
                override val descriptor = BardoToolDescriptor(
                    "mobile_files.read",
                    "Lee un tramo UTF-8 de un archivo de texto en Descargas/BARDO. Argumentos: path, offset_chars opcional, max_chars opcional (máximo 24000). No carga el archivo completo ni lee modelos/audio binario.",
                    BardoPermissionScope.READ_BARDO_DATA,
                    maxArguments = 3,
                    maxValueChars = 512
                )
                override fun execute(arguments: Map<String, String>): BardoToolOutput {
                    val path = arguments["path"].orEmpty()
                    require(path.isNotBlank()) { "Falta path." }
                    val offset = arguments["offset_chars"]?.toIntOrNull() ?: 0
                    val maxChars = arguments["max_chars"]?.toIntOrNull()
                        ?: BardoSharedStorage.MAX_READ_CHUNK_CHARS
                    val content = BardoSharedStorage.readTextRange(app, path, offset, maxChars)
                    return BardoToolOutput(true, content)
                }
            },
            object : BardoToolHandler {
                override val descriptor = BardoToolDescriptor(
                    "mobile_files.create_directory",
                    "Crea una subcarpeta únicamente dentro del árbol SAF Descargas/BARDO autorizado.",
                    BardoPermissionScope.MANAGE_BARDO_WORKSPACE,
                    maxArguments = 1,
                    maxValueChars = 512
                )
                override fun execute(arguments: Map<String, String>): BardoToolOutput {
                    val path = arguments["path"].orEmpty()
                    require(path.isNotBlank()) { "Falta path." }
                    val created = BardoSharedStorage.createDirectory(app, path)
                    return BardoToolOutput(true, "Carpeta creada: BARDO/$created")
                }
            },
            object : BardoToolHandler {
                override val descriptor = BardoToolDescriptor(
                    "mobile_files.pending_deletions",
                    "Enumera las solicitudes de eliminación propuestas por BARDO; no aprueba ni ejecuta ninguna.",
                    BardoPermissionScope.READ_BARDO_DATA,
                    maxArguments = 0
                )
                override fun execute(arguments: Map<String, String>): BardoToolOutput {
                    val pending = BardoSharedStorage.pendingDeletionRequests(app)
                    val body = if (pending.isEmpty()) "No hay solicitudes de eliminación pendientes." else
                        pending.joinToString("\n") { request ->
                            val kind = if (request.permanent) "ELIMINACIÓN PERMANENTE" else "MOVER A PAPELERA"
                            kind + " · " + request.relativePath + " · " + request.reason +
                                " · solicitud=" + request.id
                        }
                    return BardoToolOutput(true, body.take(60_000))
                }
            },
            object : BardoToolHandler {
                override val descriptor = BardoToolDescriptor(
                    "mobile_files.request_delete",
                    "Propone retirar un archivo de Descargas/BARDO. Solo crea una solicitud pendiente; nunca borra ni mueve nada.",
                    BardoPermissionScope.MANAGE_BARDO_WORKSPACE,
                    maxArguments = 3,
                    maxValueChars = 1000
                )
                override fun execute(arguments: Map<String, String>): BardoToolOutput {
                    val parsed = BardoMobileDeletionRequestArgumentsParser.parse(arguments)
                    val request = BardoSharedStorage.requestDeletion(
                        app,
                        parsed.relativePath,
                        parsed.permanent,
                        parsed.reason
                    )
                    val kind = if (request.permanent) "eliminación permanente" else "mover a papelera"
                    return BardoToolOutput(
                        true,
                        "Solicitud registrada para " + kind + ": BARDO/" + request.relativePath +
                            ". Motivo: " + request.reason +
                            ". No se ha eliminado ni movido nada; requiere tu revisión en BARDO Developer."
                    )
                }
            },
            object : BardoToolHandler {
                override val descriptor = BardoToolDescriptor(
                    "mobile_files.create_text_file",
                    "Crea un archivo de texto UTF-8 dentro de Descargas/BARDO y verifica su lectura tras escribirlo.",
                    BardoPermissionScope.MANAGE_BARDO_WORKSPACE,
                    maxArguments = 2,
                    maxValueChars = BardoSharedStorage.MAX_WRITE_TEXT_CHARS + 512
                )
                override fun execute(arguments: Map<String, String>): BardoToolOutput {
                    val path = arguments["path"].orEmpty()
                    val content = arguments["content"].orEmpty()
                    require(path.isNotBlank()) { "Falta path." }
                    val created = BardoSharedStorage.createTextFile(app, path, content)
                    return BardoToolOutput(true, "Archivo de texto creado y verificado: BARDO/$created")
                }
            }
        )
    }
}
