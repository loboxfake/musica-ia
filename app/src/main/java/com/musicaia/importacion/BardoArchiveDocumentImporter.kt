package com.musicaia.importacion

import java.io.File
import java.util.zip.ZipFile
import org.w3c.dom.Node
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Extractor pasivo y acotado para EPUB y OpenDocument.
 * Nunca extrae ficheros al disco ni ejecuta macros, scripts o contenido incrustado.
 */
object BardoArchiveDocumentImporter {
    private const val MAX_ENTRIES = 5000
    private const val MAX_ENTRY_BYTES = 4 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 20 * 1024 * 1024
    private const val MAX_TEXT_CHARS = 500_000

    fun extract(file: File): String {
        require(file.isFile && file.length() in 1..(100L * 1024L * 1024L)) {
            "El documento comprimido no existe o supera 100 MB."
        }
        val extension = file.extension.lowercase()
        require(extension in setOf("epub", "odt", "ods", "odp")) {
            "Formato de documento comprimido no compatible: .$extension"
        }

        var total = 0
        var count = 0
        val selected = mutableListOf<Pair<String, ByteArray>>()
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                count++
                require(count <= MAX_ENTRIES) { "El paquete contiene demasiadas entradas." }
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/')
                if (name.startsWith("/") || name.split('/').any { it == ".." } || name.contains(':')) {
                    throw IllegalArgumentException("El paquete contiene una ruta interna no válida.")
                }
                val wanted = when (extension) {
                    "epub" -> {
                        val lower = name.lowercase()
                        (lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")) &&
                            !lower.contains("nav")
                    }
                    else -> name == "content.xml"
                }
                if (!wanted) continue
                val bytes = zip.getInputStream(entry).use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var entryBytes = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        entryBytes += read
                        total += read
                        require(entryBytes <= MAX_ENTRY_BYTES && total <= MAX_TOTAL_BYTES) {
                            "El contenido del documento supera los límites de seguridad."
                        }
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                selected += name to bytes
            }
        }
        require(selected.isNotEmpty()) { "No se encontró contenido textual compatible en el paquete." }

        val result = buildString {
            selected.sortedBy { it.first }.forEach { (name, bytes) ->
                append("\n[").append(name).append("]\n")
                append(extractXmlText(bytes))
                append('\n')
                require(length <= MAX_TEXT_CHARS) { "El texto extraído supera 500.000 caracteres." }
            }
        }.trim()
        require(result.isNotBlank()) { "El documento no contiene texto legible." }
        return result.take(MAX_TEXT_CHARS)
    }

    /** RTF: elimina grupos de metadatos y controles de formato; conserva texto visible. */
    fun extractRtf(file: File): String {
        require(file.isFile && file.length() in 1..(20L * 1024L * 1024L)) {
            "El RTF no existe o supera 20 MB."
        }
        val source = file.readText(Charsets.ISO_8859_1)
        val out = StringBuilder()
        val skippedGroups = mutableListOf<Boolean>()
        var skipDepth = 0
        var depth = 0
        var i = 0
        while (i < source.length && out.length < MAX_TEXT_CHARS) {
            val c = source[i]
            when {
                c == '{' -> {
                    depth++
                    val header = source.substring(i, minOf(source.length, i + 40))
                    val skipThisGroup = header.startsWith("{\\*") ||
                        header.startsWith("{\\fonttbl") || header.startsWith("{\\colortbl") ||
                        header.startsWith("{\\stylesheet") || header.startsWith("{\\info")
                    skippedGroups += skipThisGroup
                    if (skipThisGroup) skipDepth++
                    i++
                }
                c == '}' -> {
                    if (skippedGroups.isNotEmpty() && skippedGroups.removeAt(skippedGroups.lastIndex)) {
                        skipDepth = (skipDepth - 1).coerceAtLeast(0)
                    }
                    depth = (depth - 1).coerceAtLeast(0)
                    i++
                }
                c == '\\' && i + 1 < source.length -> {
                    val next = source[i + 1]
                    when {
                        next == '\\' || next == '{' || next == '}' -> {
                            if (skipDepth == 0 && depth > 0) out.append(next)
                            i += 2
                        }
                        next == '\'' && i + 3 < source.length -> {
                            val hex = source.substring(i + 2, i + 4)
                            if (skipDepth == 0) runCatching { out.append(hex.toInt(16).toChar()) }
                            i += 4
                        }
                        next.isLetter() -> {
                            var endWord = i + 1
                            while (endWord < source.length && source[endWord].isLetter()) endWord++
                            val word = source.substring(i + 1, endWord)
                            if (endWord < source.length && source[endWord] == '-') endWord++
                            while (endWord < source.length && source[endWord].isDigit()) endWord++
                            if (skipDepth == 0 && (word == "par" || word == "line")) out.append('\n')
                            if (skipDepth == 0 && word == "tab") out.append('\t')
                            i = if (endWord < source.length && source[endWord] == ' ') endWord + 1 else endWord
                        }
                        else -> i += 2
                    }
                }
                c == '\r' || c == '\n' -> i++
                depth > 0 && skipDepth == 0 -> {
                    if (c.code >= 32) out.append(c)
                    i++
                }
                else -> i++
            }
        }
        return out.toString().replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\\n\\n").trim()
            .also { require(it.isNotBlank()) { "No se encontró texto legible en el RTF." } }
    }

    fun extractHtml(file: File): String {
        require(file.isFile && file.length() in 1..(20L * 1024L * 1024L)) {
            "El HTML no existe o supera 20 MB."
        }
        val raw = file.readText(Charsets.UTF_8)
        return raw.replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</(p|div|h[1-6]|li|tr|section|article)>"), "\\n")
            .replace(Regex("(?s)<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\\n\\n")
            .trim().take(MAX_TEXT_CHARS)
            .also { require(it.isNotBlank()) { "No se encontró texto visible en el HTML." } }
    }

    private fun extractXmlText(bytes: ByteArray): String {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setXIncludeAware(false)
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(bytes.inputStream())
        val blockTags = setOf(
            "p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "tr", "title",
            "table-cell", "table-header-cell", "caption", "blockquote"
        )
        val ignoredTags = setOf("script", "style", "noscript", "nav", "iframe", "object")
        return buildString {
            fun walk(node: Node) {
                if (node.nodeType == Node.ELEMENT_NODE) {
                    val name = node.localName ?: node.nodeName.substringAfter(':')
                    if (name.lowercase() in ignoredTags) return
                    val block = name.lowercase() in blockTags
                    if (block && isNotEmpty() && last() != '\n') append('\n')
                    val children = node.childNodes
                    for (index in 0 until children.length) walk(children.item(index))
                    if (block && isNotEmpty() && last() != '\n') append('\n')
                } else if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
                    val value = node.nodeValue.orEmpty().replace(Regex("\\s+"), " ")
                    if (value.isNotBlank()) append(value)
                }
            }
            walk(document.documentElement)
        }.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\\n\\n").trim()
    }
}
