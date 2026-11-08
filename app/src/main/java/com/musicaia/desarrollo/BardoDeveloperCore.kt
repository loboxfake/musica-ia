package com.musicaia.desarrollo

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Auditoría estática de snapshots importados. No ejecuta código ni modifica fuentes. */
object BardoDeveloperCore {
    private val extensiones = setOf("kt", "kts", "java", "xml", "gradle", "properties", "md", "yml", "yaml", "toml", "json", "cpp", "h", "hpp", "c", "cmake", "txt")
    private const val MAX_ARCHIVOS = 3000
    private const val MAX_ENTRADAS = 10000
    private const val MAX_ARCHIVO_BYTES = 2 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 60L * 1024L * 1024L

    data class ImportResult(val directory: File, val files: Int, val bytes: Long, val skipped: Int)
    data class Finding(val severity: String, val file: String, val line: Int, val title: String, val detail: String)
    data class Report(val files: Int, val lines: Int, val findings: List<Finding>) {
        fun render(): String = buildString {
            appendLine("BARDO · AUDITORÍA ESTÁTICA DE CÓDIGO")
            appendLine("Archivos analizados: $files · Líneas: $lines · Hallazgos: ${findings.size}")
            appendLine("Este informe es orientativo: no ejecuta el proyecto ni demuestra que compile.")
            if (findings.isEmpty()) appendLine("No se detectaron patrones de riesgo de la lista actual. Esto no demuestra ausencia de errores.")
            findings.forEachIndexed { index, f ->
                appendLine()
                appendLine("${index + 1}. [${f.severity}] ${f.title}")
                appendLine("${f.file}:${f.line}")
                appendLine(f.detail)
            }
        }
    }

    fun importarZip(input: InputStream, base: File): ImportResult {
        val root = File(base, "snapshot-${System.currentTimeMillis()}")
        check(root.mkdirs()) { "No se pudo crear el espacio de trabajo privado." }
        try {
            var count = 0
            var skipped = 0
            var total = 0L
            var entries = 0
            val seenPaths = mutableSetOf<String>()
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    require(entries <= MAX_ENTRADAS) { "El ZIP contiene demasiadas entradas." }
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    val normalized = entry.name.replace(92.toChar(), '/')
                    val parts = normalized.split('/')
                    val ext = normalized.substringAfterLast('.', "").lowercase()
                    val safe = normalized.isNotBlank() && !normalized.startsWith("/") &&
                        !normalized.contains(':') && parts.none { it.isBlank() || it == "." || it == ".." }
                    if (!safe || ext !in extensiones || count >= MAX_ARCHIVOS) {
                        skipped++
                        zip.closeEntry()
                        continue
                    }
                    require(seenPaths.add(normalized)) { "El ZIP contiene una ruta duplicada: $normalized" }
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var size = 0
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        size += n
                        total += n
                        require(size <= MAX_ARCHIVO_BYTES) { "Archivo demasiado grande en ZIP: ${entry.name}" }
                        require(total <= MAX_TOTAL_BYTES) { "El proyecto supera el límite de 60 MB de fuentes." }
                        output.write(buffer, 0, n)
                    }
                    val destination = File(root, normalized).canonicalFile
                    require(destination.path.startsWith(root.canonicalPath + File.separator)) { "Ruta insegura en ZIP." }
                    destination.parentFile?.mkdirs()
                    destination.writeBytes(output.toByteArray())
                    count++
                    zip.closeEntry()
                }
            }
            require(count > 0) { "El ZIP no contiene archivos de código reconocidos." }
            return ImportResult(root, count, total, skipped)
        } catch (error: Throwable) {
            // Nunca dejar un snapshot parcial que el siguiente autoestudio pueda confundir con el original.
            root.deleteRecursively()
            throw error
        }
    }

    fun analizar(root: File): Report {
        require(root.isDirectory) { "No hay un snapshot de código importado." }
        val files = mutableListOf<File>()
        fun collect(dir: File) {
            dir.listFiles()?.sortedBy { it.name }?.forEach { child ->
                if (child.isDirectory) collect(child)
                else if (child.isFile && child.extension.lowercase() in extensiones && child.length() <= MAX_ARCHIVO_BYTES) files += child
            }
        }
        collect(root)
        val findings = mutableListOf<Finding>()
        var linesTotal = 0
        val emptyCatch = Regex("""catch\s*\([^)]*\)\s*\{\s*}""")
        val secret = Regex("""(?i)(gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16})""")
        val dynamicVersion = Regex("""(?i)(implementation|api)\s*\([^)]*:[^:]+:(\+|latest\.release)""")
        files.forEach { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            val content = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return@forEach
            val lines = content.lines()
            linesTotal += lines.size
            lines.forEachIndexed { index, line ->
                if (line.contains("TODO") || line.contains("FIXME")) {
                    findings += Finding("INFO", relative, index + 1, "Pendiente documentado", line.trim().take(240))
                }
                if (secret.containsMatchIn(line)) {
                    findings += Finding("CRÍTICO", relative, index + 1, "Posible credencial incrustada", "Hay una cadena con formato parecido a token o clave. Revísala y rótala si es real.")
                }
                if (line.contains("Thread.sleep(")) {
                    findings += Finding("AVISO", relative, index + 1, "Espera bloqueante", "Comprueba que no se ejecuta en el hilo principal ni dentro de una ruta crítica.")
                }
                if (dynamicVersion.containsMatchIn(line)) {
                    findings += Finding("AVISO", relative, index + 1, "Dependencia no fijada", "Una versión dinámica puede cambiar entre compilaciones; fija una versión concreta y verificable.")
                }
                if (line.contains("allowAll") || (line.contains("TrustManager") && line.contains("X509"))) {
                    findings += Finding("CRÍTICO", relative, index + 1, "Posible relajación TLS", "Comprueba que no se desactiva la validación de certificados.")
                }
            }
            if (emptyCatch.containsMatchIn(content)) {
                val line = lines.indexOfFirst { it.contains("catch") && it.contains("{") }
                findings += Finding("AVISO", relative, (line + 1).coerceAtLeast(1), "Excepción posiblemente silenciada", "Hay al menos un catch vacío. Conserva el error en el registro y comunica el fallo.")
            }
        }
        if (files.none { it.name == "AndroidManifest.xml" }) {
            findings += Finding("INFO", "(proyecto)", 1, "No se encontró AndroidManifest.xml", "El ZIP puede ser parcial o no corresponder a un proyecto Android.")
        }
        if (files.none { it.name == "build.gradle" || it.name == "build.gradle.kts" }) {
            findings += Finding("INFO", "(proyecto)", 1, "No se encontró build.gradle", "No se puede identificar la configuración de compilación del proyecto.")
        }
        return Report(files.size, linesTotal, findings.sortedWith(compareByDescending<Finding> { prioridad(it.severity) }.thenBy { it.file }.thenBy { it.line }))
    }

    fun extractos(root: File, report: Report, maxChars: Int = 14000): String {
        val out = StringBuilder()
        val referenced = report.findings.filter { it.file != "(proyecto)" }.map { it.file }.distinct()
        val candidates = if (referenced.isNotEmpty()) referenced else {
            val found = mutableListOf<File>()
            fun collect(dir: File) {
                dir.listFiles()?.sortedBy { it.name }?.forEach { child ->
                    if (child.isDirectory) collect(child)
                    else if (child.isFile && child.extension.lowercase() in setOf("kt", "kts", "java", "xml", "gradle") && child.length() <= MAX_ARCHIVO_BYTES) found += child
                }
            }
            collect(root)
            found.take(6).map { it.relativeTo(root).invariantSeparatorsPath }
        }
        candidates.forEach { relative ->
            if (out.length >= maxChars) return@forEach
            val file = File(root, relative)
            if (!file.isFile) return@forEach
            val content = runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
            val room = maxChars - out.length
            out.appendLine()
            out.appendLine("--- $relative ---")
            out.append(content.take(room.coerceAtLeast(0)))
        }
        return out.toString()
    }

    private fun prioridad(value: String): Int = when (value) {
        "CRÍTICO" -> 3
        "AVISO" -> 2
        else -> 1
    }
}
