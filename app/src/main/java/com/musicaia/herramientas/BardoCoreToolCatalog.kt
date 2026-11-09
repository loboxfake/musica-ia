package com.musicaia.herramientas

import android.content.Context
import com.musicaia.diagnostico.BardoSelfInventory
import com.musicaia.desarrollo.BardoDeveloperCore
import com.musicaia.memoria.BardoKnowledgeStore
import com.musicaia.seguridad.BardoPermissionScope
import java.io.File
import java.nio.file.Files

/** Catálogo local; no expone shell, filesystem general ni red implícita. */
object BardoCoreToolCatalog {
    fun create(context: Context): List<BardoToolHandler> {
        val app = context.applicationContext
        return listOf(
            object : BardoToolHandler {
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
            },
            object : BardoToolHandler {
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
            },
            object : BardoToolHandler {
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
            },
            object : BardoToolHandler {
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
            }
        )
    }
}
