package com.musicaia.importacion

import java.io.File
import java.io.ByteArrayInputStream
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Extractor local y pasivo de texto de Office Open XML.
 * No ejecuta macros, enlaces, fórmulas ni contenido incrustado.
 */
object BardoOfficeImporter {
    data class ImportedDocument(val format: String, val text: String)

    private const val MAX_TOTAL_XML_BYTES = 20 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
    private const val MAX_TEXT_CHARS = 500_000

    fun extract(file: File): ImportedDocument {
        require(file.isFile) { "El documento no existe." }
        val extension = file.extension.lowercase()
        val format = when (extension) {
            "docx" -> "Word"
            "pptx" -> "PowerPoint"
            "xlsx" -> "Excel"
            else -> throw IllegalArgumentException("Formato Office no compatible: .$extension")
        }

        val wanted = when (extension) {
            "docx" -> Regex("word/(document|header[0-9]*|footer[0-9]*|footnotes|endnotes)\\.xml")
            "pptx" -> Regex("ppt/slides/slide[0-9]+\\.xml")
            else -> Regex("xl/(sharedStrings|worksheets/sheet[0-9]+)\\.xml")
        }

        val xml = linkedMapOf<String, ByteArray>()
        var totalBytes = 0
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            var count = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                count++
                require(count <= 2000) { "El paquete contiene demasiadas entradas." }
                if (entry.isDirectory || !wanted.matches(entry.name)) continue
                require(!entry.name.startsWith("/") && !entry.name.split('/').contains("..")) {
                    "Ruta interna no válida en el documento."
                }
                val bytes = zip.getInputStream(entry).use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var entryBytes = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        entryBytes += read
                        totalBytes += read
                        require(entryBytes <= MAX_ENTRY_BYTES && totalBytes <= MAX_TOTAL_XML_BYTES) {
                            "El contenido XML del documento supera el límite de seguridad."
                        }
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                xml[entry.name] = bytes
            }
        }

        val text = when (extension) {
            "docx" -> xml.toSortedMap().values.joinToString("\n") { paragraphText(it, "p") }
            "pptx" -> xml.toSortedMap().values.joinToString("\n") { paragraphText(it, "p") }
            else -> extractSpreadsheet(xml)
        }.trim().take(MAX_TEXT_CHARS)

        require(text.isNotBlank()) { "No se encontró texto extraíble en el documento." }
        return ImportedDocument(format, text)
    }

    private fun extractSpreadsheet(xml: Map<String, ByteArray>): String {
        val shared = xml["xl/sharedStrings.xml"]?.let(::allTextNodes).orEmpty()
        val sheets = xml.filterKeys { it.startsWith("xl/worksheets/sheet") }.toSortedMap()
        return sheets.values.joinToString("\n") { bytes ->
            val document = parseXml(bytes)
            val rows = document.getElementsByTagNameNS("*", "row")
            buildString {
                for (rowIndex in 0 until rows.length) {
                    val row = rows.item(rowIndex) as? Element ?: continue
                    val cells = row.getElementsByTagNameNS("*", "c")
                    val values = ArrayList<String>(cells.length)
                    for (cellIndex in 0 until cells.length) {
                        val cell = cells.item(cellIndex) as? Element ?: continue
                        val type = cell.getAttribute("t")
                        val value = when (type) {
                            "s" -> {
                                val raw = firstChildText(cell, "v")
                                shared.getOrNull(raw.toIntOrNull() ?: -1).orEmpty()
                            }
                            "inlineStr" -> descendantsText(cell, "t")
                            else -> firstChildText(cell, "v").ifBlank { descendantsText(cell, "t") }
                        }
                        values += value
                    }
                    if (values.any { it.isNotBlank() }) append(values.joinToString("\t")).append('\n')
                }
            }
        }
    }

    private fun paragraphText(bytes: ByteArray, paragraphTag: String): String {
        val document = parseXml(bytes)
        val paragraphs = document.getElementsByTagNameNS("*", paragraphTag)
        return buildString {
            for (index in 0 until paragraphs.length) {
                val paragraph = paragraphs.item(index) as? Element ?: continue
                val value = descendantsText(paragraph, "t")
                if (value.isNotBlank()) append(value).append('\n')
            }
        }
    }

    private fun allTextNodes(bytes: ByteArray): List<String> {
        val document = parseXml(bytes)
        val nodes = document.getElementsByTagNameNS("*", "si")
        return (0 until nodes.length).map { index ->
            descendantsText(nodes.item(index) as Element, "t")
        }
    }

    private fun firstChildText(element: Element, localName: String): String {
        val nodes = element.getElementsByTagNameNS("*", localName)
        return if (nodes.length == 0) "" else nodes.item(0).textContent.orEmpty()
    }

    private fun descendantsText(node: Node, localName: String): String {
        val elements = (node as? Element)?.getElementsByTagNameNS("*", localName) ?: return ""
        return buildString {
            for (index in 0 until elements.length) append(elements.item(index).textContent.orEmpty())
        }
    }

    private fun parseXml(bytes: ByteArray): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }
}
