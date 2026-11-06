package com.musicaia.importacion

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/** Extracción local de texto PDF; no realiza OCR ni interpreta imágenes escaneadas. */
object BardoPdfImporter {
    private val initLock = Any()
    @Volatile private var initialized = false

    fun extract(context: Context, file: File): String {
        require(file.isFile && file.length() > 0L) { "El PDF no existe o está vacío." }
        require(file.length() <= 50L * 1024L * 1024L) { "El PDF supera el límite de 50 MB para lectura segura." }
        synchronized(initLock) {
            if (!initialized) {
                PDFBoxResourceLoader.init(context.applicationContext)
                initialized = true
            }
        }
        PDDocument.load(file).use { document ->
            require(!document.isEncrypted) { "El PDF está cifrado o protegido por contraseña." }
            require(document.numberOfPages in 1..500) { "El PDF debe contener entre 1 y 500 páginas." }
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                startPage = 1
                endPage = minOf(document.numberOfPages, 500)
            }
            val text = stripper.getText(document).trim()
            if (text.isNotBlank()) return text.take(500_000)
            val ocr = BardoOcrImporter.extractScannedPdf(document)
            require(ocr.isNotBlank()) {
                "El PDF no contiene texto seleccionable y el OCR no encontró texto legible."
            }
            return ocr.take(500_000)
        }
    }
}
