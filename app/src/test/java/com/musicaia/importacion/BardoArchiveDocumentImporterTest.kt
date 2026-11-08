package com.musicaia.importacion

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoArchiveDocumentImporterTest {
    @Test
    fun extractsOpenDocumentXmlWithoutExecutingEmbeddedContent() {
        val file = createTempFile(suffix = ".odt")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("content.xml"))
                zip.write("""<?xml version="1.0"?><office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"><office:body><office:text><text:p>Documento de prueba</text:p><text:p>Segundo párrafo</text:p></office:text></office:body></office:document>""".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("Scripts/macro.py"))
                zip.write("raise Exception('must not execute')".toByteArray())
                zip.closeEntry()
            }
            val result = BardoArchiveDocumentImporter.extract(file)
            assertTrue(result.contains("Documento de prueba"))
            assertTrue(result.contains("Segundo párrafo"))
            assertFalse(result.contains("must not execute"))
        } finally { file.delete() }
    }

    @Test
    fun extractsEpubHtmlTextAndDropsScriptContent() {
        val file = createTempFile(suffix = ".epub")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("OEBPS/Text/chapter1.xhtml"))
                zip.write("""<html xmlns="http://www.w3.org/1999/xhtml"><body><h1>Capítulo</h1><p>Texto visible</p><script>steal()</script></body></html>""".toByteArray())
                zip.closeEntry()
            }
            val result = BardoArchiveDocumentImporter.extract(file)
            assertTrue(result.contains("Capítulo"))
            assertTrue(result.contains("Texto visible"))
            assertFalse(result.contains("steal"))
        } finally { file.delete() }
    }

    @Test
    fun extractsHtmlAndRtfAsPlainText() {
        val html = createTempFile(suffix = ".html")
        val rtf = createTempFile(suffix = ".rtf")
        try {
            html.writeText("<html><script>secret()</script><h1>Título</h1><p>Contenido &amp; datos</p></html>")
            rtf.writeText("{\\rtf1\\ansi Texto visible\\par Segundo párrafo}")
            val htmlText = BardoArchiveDocumentImporter.extractHtml(html)
            val rtfText = BardoArchiveDocumentImporter.extractRtf(rtf)
            assertTrue(htmlText.contains("Título"))
            assertTrue(htmlText.contains("Contenido & datos"))
            assertFalse(htmlText.contains("secret"))
            assertTrue(rtfText.contains("Texto visible"))
            assertTrue(rtfText.contains("Segundo párrafo"))
        } finally { html.delete(); rtf.delete() }
    }

    @Test
    fun rejectsArchiveTraversal() {
        val file = createTempFile(suffix = ".odt")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("../escape.xml"))
                zip.write("<text:p>no</text:p>".toByteArray())
                zip.closeEntry()
            }
            val error = runCatching { BardoArchiveDocumentImporter.extract(file) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
        } finally { file.delete() }
    }
}
