package com.musicaia.importacion

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoOfficeImporterTest {
    @Test
    fun extractsWordParagraphsWithoutExecutingDocumentContent() {
        val file = officeFile("docx", mapOf(
            "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Primera idea</w:t></w:r></w:p><w:p><w:r><w:t>Segunda idea</w:t></w:r></w:p></w:body></w:document>"""
        ))
        try {
            val result = BardoOfficeImporter.extract(file)
            assertTrue(result.format == "Word")
            assertTrue(result.text.contains("Primera idea"))
            assertTrue(result.text.contains("Segunda idea"))
        } finally { file.delete() }
    }

    @Test
    fun extractsPowerPointSlideText() {
        val file = officeFile("pptx", mapOf(
            "ppt/slides/slide1.xml" to """<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><a:p><a:r><a:t>Resumen de proyecto</a:t></a:r></a:p></p:spTree></p:cSld></p:sld>"""
        ))
        try {
            val result = BardoOfficeImporter.extract(file)
            assertTrue(result.format == "PowerPoint")
            assertTrue(result.text.contains("Resumen de proyecto"))
        } finally { file.delete() }
    }

    @Test
    fun extractsExcelSharedStringsAndCellValues() {
        val file = officeFile("xlsx", mapOf(
            "xl/sharedStrings.xml" to """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>Ventas</t></si></sst>""",
            "xl/worksheets/sheet1.xml" to """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1"><v>125</v></c></row></sheetData></worksheet>"""
        ))
        try {
            val result = BardoOfficeImporter.extract(file)
            assertTrue(result.format == "Excel")
            assertTrue(result.text.contains("Ventas"))
            assertTrue(result.text.contains("125"))
        } finally { file.delete() }
    }

    private fun officeFile(extension: String, entries: Map<String, String>): File {
        val file = File.createTempFile("bardo-office-test-", ".$extension")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }
}
